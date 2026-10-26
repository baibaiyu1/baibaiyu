package com.gmd.faq.server.main.gateway.deploy;

import com.gmd.faq.server.main.gateway.core.GatewayLifecycleManager;
import com.gmd.faq.server.main.gateway.service.GatewayDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 描述：网关数据面启动引导
 * Spring容器就绪后按数据库中的在线状态恢复所有应用与API路由
 *
 **/
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "gateway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GatewayBootstrap implements SmartInitializingSingleton {
    private final GatewayDataService dataService;
    private final GatewayLifecycleManager lifecycleManager;

    @Override
    public void afterSingletonsInstantiated() {
        // 恢复在线应用与API
        dataService.loadOnlineApps().forEach(lifecycleManager::onlineApp);

        log.info("网关数据面启动完成");
    }
}
