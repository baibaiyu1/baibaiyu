package com.gmd.faq.server.main.engine.tool;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 描述：文档文本工具，管理文档解析临时文件目录
 *
 **/
@Component
public class DocumentTextFiles {
    private static final Logger log = LoggerFactory.getLogger(DocumentTextFiles.class);

    /** 文档解析临时文件存储目录 */
    public static final String TEMP_FILES_DIR = ".baibaiyu/temp/files";

    /**
     * 获取临时文件目录的绝对路径
     */
    public static Path getTempFilesDir() {
        return Paths.get(TEMP_FILES_DIR).toAbsolutePath().normalize();
    }

    /**
     * 获取指定 fileId 对应的 .baibaiyu 文件路径
     *
     * @param fileId 附件ID
     */
    public static Path getTextFilePath(Long fileId) {
        return getTempFilesDir().resolve(fileId + ".baibaiyu");
    }

    /**
     * 删除指定 fileId 对应的 .baibaiyu 文件（若存在）
     *
     * @param fileId 附件ID
     */
    public static void deleteTextFile(Long fileId) {
        try {
            Path path = getTextFilePath(fileId);
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除 .baibaiyu 文件失败, fileId={}", fileId, e);
        }
    }

    @PostConstruct
    public void init() {
        try {
            Path dir = getTempFilesDir();
            Files.createDirectories(dir);
            log.info("文档文本临时目录已创建: {}", dir);
        } catch (IOException e) {
            log.error("创建 文档文本临时目录失败", e);
        }
    }
}
