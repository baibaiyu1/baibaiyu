package com.gmd.faq.server.main.scheduler.scheduler;

import com.gmd.faq.server.main.account.service.TenantService;
import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.config.auth.PermsResolver;
import com.gmd.faq.server.main.common.entity.Tenant;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.vo.AccountVO;
import com.gmd.faq.server.main.common.wrapper.AgentJobWrapper;
import com.gmd.faq.server.main.node.base.request.ParamItem;
import com.gmd.faq.server.main.scheduler.consts.JobConst;
import com.gmd.faq.server.main.scheduler.core.job.QuartzJob;
import com.gmd.faq.server.main.workflowbiz.dto.WorkflowRunRequest;
import com.gmd.faq.server.main.workflowbiz.service.WorkflowRunService;
import com.gmd.faq.server.main.workflowbiz.vo.WorkflowRunResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.quartz.JobExecutionContext;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工作流调度器
 * 负责执行定时工作流任务
 *
 */
@Slf4j
public class WorkflowScheduler extends QuartzJob {

    @Override
    public Object doJob(JobExecutionContext context) {
        // 1. 获取任务参数
        AgentJobWrapper wrapper = getDataMap(JobConst.DATA_MAP_KEY, AgentJobWrapper.class);
        AccountVO userInfo = getDataMap(JobConst.USER_INFO_KEY, AccountVO.class);
        Long tenantId = getDataMap(JobConst.TENANT_ID_KEY, Long.class);

        // 2. 参数校验
        if (!validateParameters(wrapper, userInfo, tenantId)) {
            return false;
        }

        try {
            // 3. 构建用户详情
            UserDetail userDetail = buildUserDetail(userInfo, tenantId);
            if (userDetail == null) {
                log.error("Failed to build UserDetail for tenant: {}", tenantId);
                return false;
            }

            // 4. 执行工作流
            WorkflowRunService workflowRunService = getBean(WorkflowRunService.class);
            WorkflowRunRequest request = buildRunRequest(wrapper.getParams());

            log.info("Executing workflow, workflowId: {}, tenantId: {}, userId: {}",
                    wrapper.getBizId(), tenantId, userInfo.getId());

            WorkflowRunResult runResult = workflowRunService.run(
                    Long.parseLong(wrapper.getBizId()),
                    request,
                    userDetail
            );

            // 5. 记录关联关系
            if (runResult != null && runResult.getRun() != null) {
                setRecordRelation(runResult.getRun().getId());
                log.info("Workflow executed successfully, runId: {}", runResult.getRun().getId());
            } else {
                log.warn("Workflow execution completed but run result is null or incomplete");
            }

            // 6. 返回执行结果
            return true;

        } catch (NumberFormatException e) {
            log.error("Invalid workflow ID format: {}", wrapper.getBizId(), e);
            return false;
        } catch (Exception e) {
            log.error("Failed to execute workflow, workflowId: {}, error: {}",
                    wrapper.getBizId(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * 校验任务参数
     */
    private boolean validateParameters(AgentJobWrapper wrapper, AccountVO userInfo, Long tenantId) {
        if (wrapper == null) {
            log.warn("AgentJobWrapper is null");
            return false;
        }

        if (StringUtils.isBlank(wrapper.getBizId())) {
            log.warn("Workflow ID is blank");
            return false;
        }

        if (userInfo == null) {
            log.warn("UserInfo is null");
            return false;
        }

        if (tenantId == null) {
            log.warn("Tenant ID is null");
            return false;
        }

        return true;
    }

    /**
     * 构建用户详情
     * <p>
     * 后台调度没有 HTTP 请求上下文，权限点需显式解析并挂上（单轨权限）：
     * 先切到目标租户上下文，再解析「角色 → 菜单/按钮 perms」，避免任务执行路径里
     * {@code UserUtils.hasPerm(...)} 因取不到权限而误判。
     */
    private UserDetail buildUserDetail(AccountVO userInfo, Long tenantId) {
        try {
            TenantService tenantService = getBean(TenantService.class);
            Tenant tenant = tenantService.getById(tenantId);

            if (tenant == null) {
                log.error("Tenant not found for id: {}", tenantId);
                return null;
            }

            // 后台线程没有请求上下文：一次性解析「权限点 + 数据范围 + 部门」（都在目标租户上下文中完成）
            PermsResolver.AccessContext access =
                    resolveAccess(userInfo.getId(), tenant.getId(), tenant.getCode());

            return UserDetail.builder()
                    .id(userInfo.getId())
                    .name(Optional.ofNullable(userInfo.getNickname()).orElse(userInfo.getUsername()))
                    .username(userInfo.getUsername())
                    .email(userInfo.getEmail())
                    .tenantId(tenant.getId())
                    .tenantCode(tenant.getCode())
                    .tenantRole(userInfo.getTenantRole())
                    .perms(access == null || access.perms() == null ? List.of() : access.perms())
                    .dataScope(access == null ? null : access.dataScope().name())
                    .deptId(access == null ? null : access.deptId())
                    .build();

        } catch (Exception e) {
            log.error("Failed to build UserDetail, tenantId: {}", tenantId, e);
            return null;
        }
    }

    /**
     * 在目标租户上下文中解析访问上下文（后台线程没有请求上下文，必须显式切换租户，
     * 否则租户拦截器会补出 {@code tenant_id = null} 导致查不到角色）
     */
    private PermsResolver.AccessContext resolveAccess(Long accountId, Long tenantId, String tenantCode) {
        try {
            PermsResolver permsResolver = getBean(PermsResolver.class);
            TenantUtils.setCurrentTenant(tenantId, tenantCode);
            try {
                return permsResolver.resolveAccess(accountId, tenantId);
            } finally {
                TenantUtils.clear();
            }
        } catch (Exception e) {
            log.warn("解析访问上下文失败，accountId={} tenantId={}", accountId, tenantId, e);
            return null;
        }
    }

    /**
     * 构建工作流运行请求
     */
    private WorkflowRunRequest buildRunRequest(Map<String, Object> params) {
        WorkflowRunRequest request = new WorkflowRunRequest();

        if (MapUtils.isEmpty(params)) {
            request.setParams(Collections.emptyList());
            return request;
        }

        // 使用Stream将Map转换为ParamItem列表
        List<ParamItem> paramItems = params.entrySet().stream()
                .map(entry -> ParamItem.builder()
                        .name(entry.getKey())
                        .value(entry.getValue())
                        .build())
                .toList();

        request.setParams(paramItems);
        return request;
    }
}
