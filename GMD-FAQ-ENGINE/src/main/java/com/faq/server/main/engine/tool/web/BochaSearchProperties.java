package com.gmd.faq.server.main.engine.tool.web;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 博查凭据只在服务端配置，不进入工具参数、工具结果和日志。 */
@Component
@ConfigurationProperties(prefix = "gmd.faq.web-search.bocha")
public class BochaSearchProperties {
    private String apiKey = "";
    private String apiUrl = "https://api.bocha.cn/v1/web-search";
    private int timeoutSeconds = 20;
    private int maxResults = 10;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) {
        if (apiKey != null && !apiKey.isBlank() && !apiKey.strip().matches("[!-~]+")) {
            throw new IllegalArgumentException("博查 API Key 只能包含可打印的 ASCII 字符，不能包含空白");
        }
        this.apiKey = apiKey == null ? "" : apiKey.strip();
    }

    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String apiUrl) {
        URI uri = URI.create(apiUrl);
        if (!"https".equals(uri.getScheme())
                || uri.getHost() == null
                || !Set.of("api.bocha.cn", "api.bochaai.com").contains(uri.getHost())
                || !"/v1/web-search".equals(uri.getPath())
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("博查搜索地址必须使用官方 HTTPS Web Search API");
        }
        this.apiUrl = apiUrl;
    }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 120) {
            throw new IllegalArgumentException("博查搜索超时必须为 1～120 秒");
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int maxResults) {
        if (maxResults < 1 || maxResults > 50) {
            throw new IllegalArgumentException("博查搜索结果上限必须为 1～50 条");
        }
        this.maxResults = maxResults;
    }
}
