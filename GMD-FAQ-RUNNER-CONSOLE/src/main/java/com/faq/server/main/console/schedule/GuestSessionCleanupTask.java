package com.gmd.faq.server.main.console.schedule;

import com.gmd.faq.server.main.account.service.TenantService;
import com.gmd.faq.server.main.agent.service.ChatSessionService;
import com.gmd.faq.server.main.common.entity.Tenant;
import com.gmd.faq.server.main.common.util.TenantUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 游客会话清理定时任务
 *
 * <p>背景：智能体分享链接（chatKey）每次访问都会用随机 userId 现场建一个会话身份
 * （{@code AccountServiceImpl#chatKeyToken}），这个身份不落账号表，因此游客会话：
 * 既不会出现在任何登录用户的列表里，也没有常规清理入口，其工作空间目录
 * （{@code .baibaiyu/tenants/{tenantCode}/workspaces/{sessionId}}）会长期占盘。
 *
 * <p><b>默认关闭</b>：删除会话会连带删除工作空间文件且不可恢复，因此不默认替用户做决定。
 * 需要自动回收时显式设置环境变量：
 * <pre>
 *   GUEST_SESSION_RETENTION_DAYS=30   # 保留天数，设为正数即启用
 *   GUEST_SESSION_CLEAN_MAX=500       # 单次最多删除的会话数，默认 500
 * </pre>
 * 未设置或设为非正数时，本任务每个凌晨直接返回，不做任何删除。
 *
 * <p>与 {@code ChatMessageArchiveTask} 的区别：后者只把「消息」搬进归档分表、会话行本身留着；
 * 本任务是真的删除会话及其工作空间，两者互不替代。
 *
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "runtime", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GuestSessionCleanupTask {

    /** 保留天数环境变量；未设置或非正数表示不启用 */
    private static final String ENV_RETENTION_DAYS = "GUEST_SESSION_RETENTION_DAYS";

    /** 单次执行删除上限环境变量 */
    private static final String ENV_CLEAN_MAX = "GUEST_SESSION_CLEAN_MAX";

    /** 单次删除上限默认值：给定时任务一个可预期的执行时长边界 */
    private static final int DEFAULT_CLEAN_MAX = 500;

    private final ChatSessionService chatSessionService;
    private final TenantService tenantService;

    /**
     * 每天凌晨 4:00 执行（避开 3:00 的元数据清理与 3:30 的消息归档，避免同一时段抢 IO）
     */
    @Scheduled(cron = "0 0 4 * * ?")
    public void cleanupInactiveGuestSessions() {
        int retentionDays = resolvePositiveInt(ENV_RETENTION_DAYS, 0);
        if (retentionDays <= 0) {
            // 默认关闭：不打印 info 级日志，避免每天刷一条无意义记录
            log.debug("未配置 {}（或值非正数），跳过游客会话清理", ENV_RETENTION_DAYS);
            return;
        }

        int maxCount = resolvePositiveInt(ENV_CLEAN_MAX, DEFAULT_CLEAN_MAX);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        log.info("开始清理 {} 天前无活动的游客会话，cutoff={}, 单次上限={}", retentionDays, cutoff, maxCount);

        List<Tenant> tenants = tenantService.list();
        int total = 0;
        for (Tenant tenant : tenants) {
            if (tenant.getCode() == null) {
                continue;
            }
            // 租户上下文必须显式切换：工作空间目录按 tenantCode 拼路径
            // （SysConst.getWorkspacePath），不切就会去删别的租户的目录。
            TenantUtils.setCurrentTenant(tenant.getId(), tenant.getCode());
            try {
                int deleted = chatSessionService.deleteInactiveGuestSessions(cutoff, maxCount);
                if (deleted > 0) {
                    log.info("租户 {} 清理游客会话 {} 个", tenant.getCode(), deleted);
                }
                total += deleted;
            } catch (Exception e) {
                // 单个租户失败不影响其他租户，也不让异常冒泡打断整个调度
                log.error("租户 {} 清理游客会话失败: {}", tenant.getCode(), e.getMessage(), e);
            } finally {
                TenantUtils.clear();
            }
        }

        log.info("游客会话清理完成，共删除 {} 个会话", total);
        if (total >= maxCount) {
            // 达到上限说明存量可能还没清完，提示运维关注（下次执行会继续）
            log.warn("本次清理达到单次上限 {}，可能仍有存量游客会话，将在下次执行时继续清理", maxCount);
        }
    }

    /**
     * 读取正整数环境变量
     *
     * @param name         变量名
     * @param defaultValue 缺省值
     * @return 解析结果；无法解析或非正数时返回 defaultValue
     */
    private int resolvePositiveInt(String name, int defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : defaultValue;
        } catch (NumberFormatException e) {
            log.warn("环境变量 {} 的值 '{}' 不是整数，按 {} 处理", name, raw, defaultValue);
            return defaultValue;
        }
    }
}
