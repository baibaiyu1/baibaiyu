package com.gmd.faq.server.main.console.skill;

import com.gmd.faq.server.main.common.config.auth.PassAuth;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 描述：技能文件内部下载接口（服务于 runner-file 服务）
 * 查库校验 token 后按路径打 zip 返回
 *
 **/
@RestController
@RequestMapping("/internal/skill")
public class SkillInternalController {

    private static final Logger log = LoggerFactory.getLogger(SkillInternalController.class);

    /** 多租户根目录 */
    private static final String ROOT_DIR = ".baibaiyu";
    private static final String TENANTS_DIR = "tenants";

    private final JdbcTemplate jdbcTemplate;

    public SkillInternalController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按路径下载技能包 zip
     *
     * @param skillPath  相对路径，格式：{tenantCode}/skills/{skillName}
     * @param token      内部服务鉴权 token
     * @param response   HTTP 响应
     */
    @PassAuth
    @GetMapping("/download-zip")
    public void downloadZip(@RequestParam("skillPath") String skillPath,
                            @RequestParam("token") String token,
                            HttpServletResponse response) throws IOException {
        // token 校验
        String internalToken = jdbcTemplate.queryForObject(
                "SELECT skill_token FROM " + TableConst.SKILL_TOKEN + " LIMIT 1", String.class);
        if (internalToken == null || internalToken.isEmpty() || !internalToken.equals(token)) {
            throw new BusinessException("无效的 token");
        }

        // 路径非空校验
        if (skillPath == null || skillPath.isBlank() || ".".equals(skillPath.trim())) {
            throw new BusinessException("路径不能为空");
        }

        // 构造并校验路径
        Path tenantsRoot = Paths.get(ROOT_DIR, TENANTS_DIR).toAbsolutePath().normalize();
        Path skillDir = tenantsRoot.resolve(skillPath).normalize();

        // 路径穿越检查（排除空路径解析为根目录的情况）
        if (!skillDir.startsWith(tenantsRoot) || skillDir.equals(tenantsRoot)) {
            throw new BusinessException("非法路径");
        }

        if (!Files.exists(skillDir) || !Files.isDirectory(skillDir)) {
            throw new BusinessException("技能包目录不存在: " + skillPath);
        }

        // 设置响应头
        String skillName = skillDir.getFileName().toString();
        String zipFileName = skillName + ".zip";
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader("Content-Disposition", "attachment; filename=\"" +
                new String(zipFileName.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1) + "\"");

        // 打 zip 输出
        try (OutputStream os = response.getOutputStream();
             ZipOutputStream zos = new ZipOutputStream(os)) {
            Files.walk(skillDir).forEach(filePath -> {
                if (Files.isDirectory(filePath)) {
                    return;
                }
                String entryName = skillDir.relativize(filePath).toString().replace('\\', '/');
                try {
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(filePath, zos);
                    zos.closeEntry();
                } catch (IOException e) {
                    throw new RuntimeException("压缩文件失败: " + filePath, e);
                }
            });
            zos.flush();
        }

        log.info("内部接口下载技能包 zip: path={}", skillPath);
    }
}
