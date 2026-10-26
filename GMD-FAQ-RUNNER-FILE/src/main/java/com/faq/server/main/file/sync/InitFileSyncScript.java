package com.gmd.faq.server.main.file.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 描述：启动时技能文件全量同步脚本
 * 使用 Redis 分布式锁防止多节点重复执行，支持 console 不可用时重试
 *
 **/
@Component
public class InitFileSyncScript implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitFileSyncScript.class);

    /** 重试间隔（毫秒）：5s, 15s, 30s */
    private static final long[] RETRY_DELAYS = {5000, 15000, 30000};

    private final SkillFileSyncService syncService;

    public InitFileSyncScript(SkillFileSyncService syncService) {
        this.syncService = syncService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("技能文件同步服务启动，准备执行全量同步...");
        syncWithRetry();
    }

    /**
     * 带重试的全量同步
     */
    private void syncWithRetry() {
        int maxAttempts = RETRY_DELAYS.length + 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                syncService.syncAll();
                log.info("全量同步执行成功（第 {} 次尝试）", attempt);
                return;
            } catch (Exception e) {
                log.warn("全量同步第 {} 次尝试失败: {}", attempt, e.getMessage());
                if (attempt < maxAttempts) {
                    long delay = RETRY_DELAYS[attempt - 1];
                    log.info("等待 {}ms 后重试...", delay);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("全量同步重试被中断");
                        return;
                    }
                } else {
                    log.error("全量同步全部 {} 次尝试均失败，请检查 console 服务是否正常", maxAttempts);
                }
            }
        }
    }
}
