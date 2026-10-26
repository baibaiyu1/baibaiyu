package com.gmd.faq.server.main.file.client;

import com.gmd.faq.server.main.file.config.FileSyncProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 描述：Console HTTP 客户端
 * 负责从 console 节点下载技能包 zip 文件
 *
 **/
@Component
public class ConsoleHttpClient {

    private static final Logger log = LoggerFactory.getLogger(ConsoleHttpClient.class);

    private final RestTemplate restTemplate;
    private final FileSyncProperties properties;
    private final JdbcTemplate jdbcTemplate;

    public ConsoleHttpClient(RestTemplateBuilder builder, FileSyncProperties properties, JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(properties.getDownloadTimeoutSeconds()))
                .build();
    }

    /**
     * 从 console 下载技能包 zip 到本地临时文件
     *
     * @param relativePath 相对于 .baibaiyu/tenants/ 的路径，格式：{tenantCode}/skills/{skillName}
     * @return 下载的临时 zip 文件路径
     * @throws IOException 下载或写入失败
     */
    public Path downloadSkillZip(String relativePath) throws IOException {
        // 动态查询 token
        String token = jdbcTemplate.queryForObject(
                "SELECT skill_token FROM gmd_faq_skill_token LIMIT 1", String.class);

        URI uri = UriComponentsBuilder.fromUriString(properties.getConsoleUrl())
                .path("/internal/skill/download-zip")
                .queryParam("skillPath", relativePath)
                .queryParam("token", token)
                .build()
                .toUri();

        Path tempFile = Files.createTempFile("skill-sync-", ".zip");
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    uri, HttpMethod.GET, null, byte[].class);

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                throw new IOException("下载失败，HTTP 状态码: " + response.getStatusCode());
            }

            Files.write(tempFile, response.getBody());
            log.info("下载技能包 zip 成功: path={}, size={} bytes", relativePath, response.getBody().length);
            return tempFile;
        } catch (Exception e) {
            Files.deleteIfExists(tempFile);
            throw new IOException("下载技能包 zip 失败: path=" + relativePath, e);
        }
    }
}
