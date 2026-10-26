package com.gmd.faq.server.main.shellproxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 描述：Shell执行代理配置属性
 *
 **/
@Component
@ConfigurationProperties(prefix = "shell")
public class ShellProperties {

    /** 默认超时时间（秒） */
    private int defaultTimeout = 300;

    /** 最大超时时间（秒） */
    private int maxTimeout = 3600;

    /** 最大输出大小（字节），默认50MB */
    private long maxOutputSize = 50 * 1024 * 1024L;

    /** 是否在日志中记录命令原文，默认false避免敏感信息泄露 */
    private boolean logCommand = false;

    public int getDefaultTimeout() {
        return defaultTimeout;
    }

    public void setDefaultTimeout(int defaultTimeout) {
        this.defaultTimeout = defaultTimeout;
    }

    public int getMaxTimeout() {
        return maxTimeout;
    }

    public void setMaxTimeout(int maxTimeout) {
        this.maxTimeout = maxTimeout;
    }

    public long getMaxOutputSize() {
        return maxOutputSize;
    }

    public void setMaxOutputSize(long maxOutputSize) {
        this.maxOutputSize = maxOutputSize;
    }

    public boolean isLogCommand() {
        return logCommand;
    }

    public void setLogCommand(boolean logCommand) {
        this.logCommand = logCommand;
    }
}
