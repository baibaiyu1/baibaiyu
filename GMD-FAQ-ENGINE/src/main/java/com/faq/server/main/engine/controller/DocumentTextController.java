package com.gmd.faq.server.main.engine.controller;

import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.engine.tool.DocumentTextFiles;
import com.gmd.faq.server.main.engine.tool.FileTextExtractService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 描述：文档文本控制器，提供文档解析触发和 .baibaiyu 文本读取接口
 *
 **/
@RestController
@RequestMapping("/runtime")
@RequiredArgsConstructor
public class DocumentTextController {
    private final FileTextExtractService fileTextExtractService;

    /** fileId 必须为纯数字，防止路径遍历攻击 */
    private static final Pattern FILE_ID_PATTERN = Pattern.compile("^\\d+$");

    /**
     * 触发文档文本提取
     *
     * @param fileId 附件ID
     * @return true=解析成功, false=不支持
     */
    @PostMapping(value = "/parse-text/{fileId}", name = "触发文档文本提取")
    public R<Boolean> parseText(@PathVariable("fileId") String fileId) {
        if (!FILE_ID_PATTERN.matcher(fileId).matches()) {
            return R.fail("无效的文件ID");
        }
        boolean result = fileTextExtractService.extractAndSave(Long.valueOf(fileId));
        return R.data(result);
    }

    /**
     * 读取 .baibaiyu 文件的纯文本内容（用于前端预览）
     *
     * @param fileId 附件ID
     */
    @GetMapping(value = "/file-text/{fileId}", name = "读取 .baibaiyu 文件纯文本")
    public ResponseEntity<String> getFileText(@PathVariable("fileId") String fileId) {
        if (!FILE_ID_PATTERN.matcher(fileId).matches()) {
            return ResponseEntity.badRequest().body("无效的文件ID");
        }
        try {
            Path textFile = DocumentTextFiles.getTextFilePath(Long.valueOf(fileId));
            if (!Files.exists(textFile)) {
                return ResponseEntity.notFound().build();
            }
            String content = Files.readString(textFile, StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                    .body(content);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("读取文件失败: " + e.getMessage());
        }
    }
}
