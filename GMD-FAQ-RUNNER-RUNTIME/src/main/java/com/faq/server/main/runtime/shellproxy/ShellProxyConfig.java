package com.gmd.faq.server.main.runtime.shellproxy;

import io.agentscope.core.tool.coding.ShellCommandTool;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 描述：Shell代理配置，在应用启动时将YAML配置写入ShellCommandTool静态字段
 *
 **/
@Configuration
public class ShellProxyConfig {

    private static final Logger log = LoggerFactory.getLogger(ShellProxyConfig.class);

    @Value("${shell-proxy.enabled:false}")
    private boolean enabled;

    @Value("${shell-proxy.base-url:http://localhost:3062}")
    private String baseUrl;

    /**
     * 应用启动后将配置注入ShellCommandTool静态字段
     */
    @PostConstruct
    public void init() {
        ShellCommandTool.proxyEnabled = enabled;
        ShellCommandTool.proxyBaseUrl = baseUrl;
        log.info("shell-proxy config initialized: enabled={}, baseUrl={}", enabled, baseUrl);
    }
}
