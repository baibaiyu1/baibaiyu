package com.gmd.faq.server.main.file.sync;

import com.gmd.faq.server.main.file.client.ConsoleHttpClient;
import com.gmd.faq.server.main.file.config.FileSyncProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 描述：技能文件同步服务
 * 核心业务：全量同步、增量同步、删除同步
 *
 **/
@Service
public class SkillFileSyncService {

    private static final Logger log = LoggerFactory.getLogger(SkillFileSyncService.class);

    /** 路径常量：租户目录名 */
    private static final String TENANTS_DIR = "tenants";
    /** 路径常量：技能目录名 */
    private static final String SKILLS_DIR = "skills";

    private final ConsoleHttpClient httpClient;
    private final FileSyncProperties properties;
    private final JdbcTemplate jdbcTemplate;

    public SkillFileSyncService(ConsoleHttpClient httpClient,
                                FileSyncProperties properties,
                                JdbcTemplate jdbcTemplate) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ========== 全量同步（启动时调用） ==========

    /**
     * 启动时全量同步：遍历所有租户的所有技能包，仅下载本地不存在的
     */
    public void syncAll() {
        log.info("开始全量同步技能文件...");
        long start = System.currentTimeMillis();
        int syncCount = 0;
        int failCount = 0;

        // 查询所有启用的租户
        List<Map<String, Object>> tenants = jdbcTemplate.queryForList(
                "SELECT id, code FROM gmd_faq_tenant WHERE enabled = 1");

        for (Map<String, Object> tenant : tenants) {
            Long tenantId = ((Number) tenant.get("id")).longValue();
            String tenantCode = (String) tenant.get("code");

            // 查询该租户下所有启用的技能包
            List<Map<String, Object>> skills = jdbcTemplate.queryForList(
                    "SELECT id, name FROM gmd_faq_skill_package WHERE tenant_id = ? AND enabled = 1",
                    tenantId);

            for (Map<String, Object> skill : skills) {
                String skillName = (String) skill.get("name");
                Path localSkillDir = getSkillDirPath(tenantCode, skillName);

                // 本地已存在则先删除，确保每次全量同步拿到最新版本
                if (Files.exists(localSkillDir)) {
                    try {
                        deleteRecursively(localSkillDir);
                    } catch (IOException e) {
                        failCount++;
                        log.warn("删除旧技能包失败: {}/{} - {}", tenantCode, skillName, e.getMessage());
                        continue;
                    }
                }

                // 下载并安装
                String relativePath = tenantCode + "/" + SKILLS_DIR + "/" + skillName;
                try {
                    Path zipFile = httpClient.downloadSkillZip(relativePath);
                    try {
                        extractZip(zipFile, localSkillDir);
                        syncCount++;
                        log.info("同步技能包成功: {}/{}", tenantCode, skillName);
                    } finally {
                        Files.deleteIfExists(zipFile);
                    }
                } catch (Exception e) {
                    failCount++;
                    log.warn("同步技能包失败: {}/{} - {}", tenantCode, skillName, e.getMessage());
                }
            }
        }

        log.info("全量同步完成: 同步={}, 失败={}, 耗时={}ms",
                syncCount, failCount, System.currentTimeMillis() - start);
    }

    // ========== 增量同步（Redis 通知触发） ==========

    /**
     * 根据 skillId 增量同步单个技能包
     *
     * @param skillId 技能包 ID
     */
    public void syncById(long skillId) {
        // 查询技能包信息
        Map<String, Object> skill;
        try {
            skill = jdbcTemplate.queryForMap(
                    "SELECT id, name, tenant_id FROM gmd_faq_skill_package WHERE id = ?", skillId);
        } catch (Exception e) {
            // 技能包不存在（可能已被删除），跳过
            log.warn("增量同步时技能包不存在: skillId={}", skillId);
            return;
        }

        Long tenantId = ((Number) skill.get("tenant_id")).longValue();
        String skillName = (String) skill.get("name");

        // 查询租户信息
        Map<String, Object> tenant;
        try {
            tenant = jdbcTemplate.queryForMap("SELECT code FROM gmd_faq_tenant WHERE id = ?", tenantId);
        } catch (Exception e) {
            log.warn("增量同步时租户不存在: tenantId={}", tenantId);
            return;
        }

        String tenantCode = (String) tenant.get("code");
        String relativePath = tenantCode + "/" + SKILLS_DIR + "/" + skillName;
        Path localSkillDir = getSkillDirPath(tenantCode, skillName);

        try {
            Path zipFile = httpClient.downloadSkillZip(relativePath);
            try {
                // 解压到临时目录，然后增量合并到目标目录
                Path tempDir = Files.createTempDirectory("skill-sync-");
                try {
                    extractZip(zipFile, tempDir);
                    mergeIncrementally(tempDir, localSkillDir);
                    log.info("增量同步技能包成功: {}/{}", tenantCode, skillName);
                } finally {
                    deleteRecursively(tempDir);
                }
            } finally {
                Files.deleteIfExists(zipFile);
            }
        } catch (Exception e) {
            log.error("增量同步技能包失败: {}/{} - {}", tenantCode, skillName, e.getMessage(), e);
        }
    }

    // ========== 删除同步（Redis 通知触发） ==========

    /**
     * 删除本地技能包目录
     *
     * @param tenantCode 租户编码
     * @param skillName  技能包名称
     */
    public void removeSkill(String tenantCode, String skillName) {
        Path localSkillDir = getSkillDirPath(tenantCode, skillName);
        if (Files.exists(localSkillDir)) {
            try {
                deleteRecursively(localSkillDir);
                log.info("删除技能包成功: {}/{}", tenantCode, skillName);
            } catch (IOException e) {
                log.error("删除技能包失败: {}/{} - {}", tenantCode, skillName, e.getMessage(), e);
            }
        }
    }

    // ========== 内部方法 ==========

    /**
     * 构造本地技能包目录路径
     */
    private Path getSkillDirPath(String tenantCode, String skillName) {
        return Path.of(properties.getLocalBaseDir(), TENANTS_DIR, tenantCode, SKILLS_DIR, skillName);
    }

    /**
     * 增量合并：比较源目录和目标目录，仅复制变更的文件
     * - 源有目标无 → 复制（新增）
     * - 源有目标有 → 比较内容，不同则覆盖（更新）
     * - 目标有源无 → 删除（清理）
     */
    private void mergeIncrementally(Path sourceDir, Path targetDir) throws IOException {
        // 确保目标目录存在
        if (!Files.exists(targetDir)) {
            Files.createDirectories(targetDir);
        }

        // 收集源文件相对路径
        Set<String> sourceFiles = new HashSet<>();
        if (Files.exists(sourceDir)) {
            collectRelativePaths(sourceDir, sourceDir, sourceFiles);
        }

        // 收集目标文件相对路径
        Set<String> targetFiles = new HashSet<>();
        if (Files.exists(targetDir)) {
            collectRelativePaths(targetDir, targetDir, targetFiles);
        }

        // 1. 处理源中的文件（新增/更新）
        for (String relPath : sourceFiles) {
            Path srcFile = sourceDir.resolve(relPath);
            Path tgtFile = targetDir.resolve(relPath);

            if (!targetFiles.contains(relPath)) {
                // 新增：源有目标无
                Files.createDirectories(tgtFile.getParent());
                Files.copy(srcFile, tgtFile, StandardCopyOption.REPLACE_EXISTING);
            } else {
                // 目标也有，比较内容
                if (isFileDifferent(srcFile, tgtFile)) {
                    Files.copy(srcFile, tgtFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        // 2. 处理目标中多出的文件（删除：目标有源无）
        for (String relPath : targetFiles) {
            if (!sourceFiles.contains(relPath)) {
                Path tgtFile = targetDir.resolve(relPath);
                Files.deleteIfExists(tgtFile);
            }
        }

        // 3. 清理目标中的空目录
        cleanEmptyDirectories(targetDir);
    }

    /**
     * 流式比较两个文件内容是否不同（先比较大小，再逐块比较）
     */
    private boolean isFileDifferent(Path file1, Path file2) throws IOException {
        long size1 = Files.size(file1);
        long size2 = Files.size(file2);
        if (size1 != size2) {
            return true;
        }

        // 大小相同，流式逐块比较
        byte[] buf1 = new byte[8192];
        byte[] buf2 = new byte[8192];
        try (InputStream is1 = Files.newInputStream(file1);
             InputStream is2 = Files.newInputStream(file2)) {
            while (true) {
                int read1 = is1.read(buf1);
                int read2 = is2.read(buf2);
                if (read1 != read2) {
                    return true;
                }
                if (read1 == -1) {
                    return false;
                }
                for (int i = 0; i < read1; i++) {
                    if (buf1[i] != buf2[i]) {
                        return true;
                    }
                }
            }
        }
    }

    /**
     * 递归收集目录下的所有文件相对路径
     */
    private void collectRelativePaths(Path root, Path current, Set<String> paths) throws IOException {
        try (var stream = Files.list(current)) {
            for (Path entry : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(entry)) {
                    collectRelativePaths(root, entry, paths);
                } else {
                    String relPath = root.relativize(entry).toString().replace('\\', '/');
                    paths.add(relPath);
                }
            }
        }
    }

    /**
     * 清理空目录（自底向上）
     */
    private void cleanEmptyDirectories(Path dir) throws IOException {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return;
        }
        // 收集所有子目录（自底向上排序）
        List<Path> dirs = new ArrayList<>();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) {
                if (!d.equals(dir)) {
                    dirs.add(d);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        // 自底向上删除空目录
        for (Path d : dirs) {
            try (var stream = Files.list(d)) {
                if (!stream.findFirst().isPresent()) {
                    Files.delete(d);
                }
            }
        }
    }

    /**
     * 解压 zip 到目标目录
     */
    private void extractZip(Path zipFile, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // 安全校验：防止路径穿越
                String entryName = entry.getName();
                Path resolved = targetDir.resolve(entryName).normalize();
                if (!resolved.startsWith(targetDir)) {
                    log.warn("跳过不安全的 zip 条目: {}", entryName);
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zis, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * 递归删除目录及其所有内容
     */
    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
