package com.gmd.faq.server.main.file.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 描述：技能文件同步服务配置属性
 *
 **/
@Component
@ConfigurationProperties(prefix = "skill-sync")
public class FileSyncProperties {

    /** console 服务基础 URL */
    private String consoleUrl = "http://localhost:3060";

    /** HTTP 下载超时时间（秒） */
    private int downloadTimeoutSeconds = 120;

    /** 本地 skill 文件根目录 */
    private String localBaseDir = ".baibaiyu";

    public String getConsoleUrl() {
        return consoleUrl;
    }

    public void setConsoleUrl(String consoleUrl) {
        this.consoleUrl = consoleUrl;
    }

    public int getDownloadTimeoutSeconds() {
        return downloadTimeoutSeconds;
    }

    public void setDownloadTimeoutSeconds(int downloadTimeoutSeconds) {
        this.downloadTimeoutSeconds = downloadTimeoutSeconds;
    }

    public String getLocalBaseDir() {
        return localBaseDir;
    }

    public void setLocalBaseDir(String localBaseDir) {
        this.localBaseDir = localBaseDir;
    }
}
