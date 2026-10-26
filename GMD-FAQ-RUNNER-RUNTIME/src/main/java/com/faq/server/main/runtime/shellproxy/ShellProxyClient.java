package com.gmd.faq.server.main.runtime.shellproxy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 描述：Shell代理HTTP客户端，将命令执行请求转发到shell-proxy服务
 *
 **/
public final class ShellProxyClient {

    private static final Logger log = LoggerFactory.getLogger(ShellProxyClient.class);

    /** HTTP连接超时 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** JSON序列化 */
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 共享HttpClient实例（线程安全） */
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private ShellProxyClient() {
    }

    /**
     * 执行Shell命令，将请求转发到shell-proxy服务
     *
     * @param baseUrl          shell-proxy服务地址，如 http://localhost:3062
     * @param command          要执行的命令
     * @param timeoutSeconds   超时时间（秒）
     * @param charset          字符集名称
     * @param workingDirectory 工作目录
     * @return 命令执行结果
     */
    public static ShellProxyResult execute(String baseUrl, String command, int timeoutSeconds,
                                           String charset, String workingDirectory) {
        String url = baseUrl.replaceAll("/$", "") + "/api/proxy/execute/shell";

        try {
            // 构建请求体
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("command", command);
            requestBody.put("timeout", timeoutSeconds);
            if (charset != null && !charset.isBlank()) {
                requestBody.put("charset", charset);
            }
            if (workingDirectory != null && !workingDirectory.isBlank()) {
                requestBody.put("workingDirectory", workingDirectory);
            }

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            // 构建HTTP请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds + 5))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            log.debug("sending shell command to proxy, url={}, timeout={}s", url, timeoutSeconds);

            // 发送请求
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            // 解析响应
            if (response.statusCode() == 200) {
                ShellProxyResponse proxyResp = objectMapper.readValue(response.body(),
                        ShellProxyResponse.class);
                return new ShellProxyResult(
                        proxyResp.returnCode,
                        proxyResp.stdout != null ? proxyResp.stdout : "",
                        proxyResp.stderr != null ? proxyResp.stderr : "");
            }

            // 非200状态码
            log.warn("shell proxy returned status={}, body={}", response.statusCode(),
                    response.body());
            return new ShellProxyResult(-1, "",
                    "ProxyError: shell-proxy returned status " + response.statusCode());

        } catch (HttpConnectTimeoutException | ConnectException e) {
            log.error("cannot connect to shell-proxy at {}", url, e);
            return new ShellProxyResult(-1, "",
                    "ProxyError: cannot connect to shell-proxy at " + url);
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("shell-proxy request timeout, url={}", url, e);
            return new ShellProxyResult(-1, "",
                    "ProxyError: request to shell-proxy timed out");
        } catch (IOException e) {
            log.error("IO error communicating with shell-proxy", e);
            return new ShellProxyResult(-1, "",
                    "ProxyError: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("shell-proxy request interrupted", e);
            return new ShellProxyResult(-1, "",
                    "ProxyError: request was interrupted");
        }
    }

    /**
     * 命令执行结果
     */
    public record ShellProxyResult(int returnCode, String stdout, String stderr) {
    }

    /**
     * shell-proxy JSON响应结构（仅映射需要的字段）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ShellProxyResponse {
        @JsonProperty("returnCode")
        private int returnCode;

        @JsonProperty("stdout")
        private String stdout;

        @JsonProperty("stderr")
        private String stderr;
    }
}
