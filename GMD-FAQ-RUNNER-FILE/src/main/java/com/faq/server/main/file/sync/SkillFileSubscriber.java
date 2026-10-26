package com.gmd.faq.server.main.file.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 描述：技能文件同步 Redis 订阅者
 * 监听 gmd-faq:skill:file:sync 频道，接收 skill 变更通知并执行对应操作
 * <p>
 * 消息格式（JSON）：
 * - 同步：{"skillId":123, "action":"sync"}
 * - 删除：{"skillName":"xxx", "tenantCode":"yyy", "action":"delete"}
 *
 **/
@Component
public class SkillFileSubscriber {

    private static final Logger log = LoggerFactory.getLogger(SkillFileSubscriber.class);

    private final SkillFileSyncService syncService;
    private final ObjectMapper objectMapper;

    public SkillFileSubscriber(SkillFileSyncService syncService) {
        this.syncService = syncService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 处理 Redis 消息
     *
     * @param channel 频道名称
     * @param message 消息内容（JSON）
     */
    public void onMessage(String channel, String message) {
        log.info("收到技能文件同步通知: channel={}, message={}", channel, message);

        Map<String, Object> payload;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(message, Map.class);
            payload = parsed;
        } catch (Exception e) {
            log.warn("解析同步消息失败，跳过: message={}", message, e);
            return;
        }

        String action = (String) payload.get("action");
        if (action == null) {
            log.warn("同步消息缺少 action 字段，跳过: message={}", message);
            return;
        }

        switch (action) {
            case "sync" -> handleSync(payload, message);
            case "delete" -> handleDelete(payload, message);
            default -> log.warn("未知的同步 action: {}, 跳过: message={}", action, message);
        }
    }

    /**
     * 处理同步操作
     */
    private void handleSync(Map<String, Object> payload, String message) {
        Object skillIdObj = payload.get("skillId");
        if (skillIdObj == null) {
            log.warn("同步消息缺少 skillId 字段，跳过: message={}", message);
            return;
        }
        long skillId = ((Number) skillIdObj).longValue();
        syncService.syncById(skillId);
    }

    /**
     * 处理删除操作
     */
    private void handleDelete(Map<String, Object> payload, String message) {
        String skillName = (String) payload.get("skillName");
        String tenantCode = (String) payload.get("tenantCode");

        if (skillName == null || tenantCode == null) {
            log.warn("删除消息缺少 skillName 或 tenantCode 字段，跳过: message={}", message);
            return;
        }
        syncService.removeSkill(tenantCode, skillName);
    }
}
