package com.gmd.faq.server.main.engine.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.file.ReadFileTool;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 描述：加载文件文本内容工具，用于 AI 自主读取已解析的文档附件纯文本
 *
 **/
public class LoadFileTextContentTool {

    private final ReadFileTool readFileTool;
    private final DocumentTextAccess documentTextAccess;

    public LoadFileTextContentTool(DocumentTextAccess documentTextAccess) {
        this.documentTextAccess = documentTextAccess;
        // 限制文件访问范围到 .baibaiyu 临时文件目录
        String baseDir = DocumentTextFiles.getTempFilesDir().toString();
        this.readFileTool = new ReadFileTool(baseDir);
    }

    /**
     * 读取已解析的文档附件纯文本内容
     *
     * @param textFileName .baibaiyu 文件名（即 {fileId}.baibaiyu）
     * @param ranges 可选的行范围，格式 "start,end"，不传则返回全文
     * @return 文件文本内容
     */
    @Tool(
            name = "load_file_text_content",
            description =
                    "Read the extracted plain text content of an uploaded document file"
                            + " (Word, Excel, PPT, PDF, CSV, or plain text). The file content is"
                            + " automatically extracted and saved as a .baibaiyu file when the user"
                            + " uploads a document. Use this tool to read the text content for analysis."
                            + " The gmd_faq_file_name parameter is provided in the user's message hint"
                            + " (e.g., '123.baibaiyu'). Optionally specify line ranges for large files.")
    public Mono<ToolResultBlock> loadFileTextContent(
            @ToolParam(
                            name = "gmd_faq_file_name",
                            description = "The .baibaiyu file name from the user's message hint, e.g. '123.baibaiyu'")
                    String textFileName,
            @ToolParam(
                            name = "ranges",
                            required = false,
                            description =
                                    "Optional line range to read, e.g. '1,100' for first 100 lines"
                                            + " or '-100,-1' for last 100 lines. Omit to read the entire file.")
                    String ranges) {
        // 防御 null 参数：AI 模型可能传入异常值，避免 NPE 穿透
        if (textFileName == null || textFileName.isBlank()) {
            return Mono.just(ToolResultBlock.error("Error: gmd_faq_file_name is required and cannot be empty"));
        }

        if (!textFileName.matches("[0-9]{1,19}\\.baibaiyu")) {
            return Mono.just(ToolResultBlock.error("无效的附件正文文件名"));
        }
        try {
            Long fileId = DocumentTextAccess.parseId(textFileName.substring(0, textFileName.lastIndexOf('.')));
            documentTextAccess.requireForSession(fileId, com.gmd.faq.server.main.engine.agui.AgentContext.get());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Mono.just(ToolResultBlock.error("附件不存在或未授权给当前会话"));
        }
        // 鉴权后再检查缓存，不暴露其他附件是否已解析。
        Path filePath = DocumentTextFiles.getTempFilesDir().resolve(textFileName).normalize();
        if (!filePath.startsWith(DocumentTextFiles.getTempFilesDir())
                || !Files.isRegularFile(filePath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return Mono.just(ToolResultBlock.error("Error: .baibaiyu file not found for '" + textFileName
                    + "'. The file may not have been parsed yet or was deleted."));
        }

        return readFileTool.viewTextFile(textFileName, ranges);
    }
}
