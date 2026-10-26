package com.gmd.faq.server.main.engine.tool;

import com.gmd.faq.server.main.common.entity.Attach;
import com.gmd.faq.server.main.engine.rag.DocumentParser;
import com.gmd.faq.server.main.resource.service.AttachService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 描述：文件文本提取服务，将上传的文档解析为纯文本并保存为 .baibaiyu 文件
 *
 **/
@Service
@RequiredArgsConstructor
public class FileTextExtractService {
    private static final Logger log = LoggerFactory.getLogger(FileTextExtractService.class);

    private final AttachService attachService;
    private final DocumentParser documentParser;

    /**
     * 提取文件纯文本并保存为 .baibaiyu 文件
     *
     * @param fileId 附件ID
     * @return true=解析成功, false=文件类型不支持或解析失败
     */
    public boolean extractAndSave(Long fileId) {
        Attach attach = attachService.getById(fileId);
        if (attach == null) {
            log.warn("附件不存在, fileId={}", fileId);
            return false;
        }

        String originalName = attach.getOriginalName();
        if (documentParser.isNotSupported(originalName)) {
            log.info("文件类型不支持文本提取, fileId={}, fileName={}", fileId, originalName);
            return false;
        }

        // try-with-resources 确保 InputStream 正确关闭
        try (InputStream inputStream = attachService.downloadAsStream(attach)) {
            String text = documentParser.parse(inputStream, originalName);
            Path textFile = DocumentTextFiles.getTextFilePath(fileId);
            Files.createDirectories(textFile.getParent());
            Files.writeString(textFile, text, StandardCharsets.UTF_8);
            log.info("文件文本提取成功, fileId={}, fileName={}, size={}, path={}", fileId, originalName, text.length(), textFile.toAbsolutePath());
            return true;
        } catch (Exception e) {
            log.error("文件文本提取失败, fileId={}, fileName={}", fileId, originalName, e);
            return false;
        }
    }
}
