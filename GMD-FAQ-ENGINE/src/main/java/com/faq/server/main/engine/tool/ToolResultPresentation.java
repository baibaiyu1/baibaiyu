package com.gmd.faq.server.main.engine.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.List;
import java.util.stream.Collectors;

/** 工具结果的展示快照；图片文件引用与原生多模态输出分别保存。 */
public record ToolResultPresentation(String text, List<Image> images, List<Artifact> artifacts, List<Citation> citations) {
    public static final String METADATA_KEY = "gmd.tool.result.images";
    public static final String ARTIFACTS_METADATA_KEY = "gmd.tool.result.artifacts";
    public static final String CITATIONS_METADATA_KEY = "gmd.knowledge.citations";
    public ToolResultPresentation(String text, List<Image> images, List<Artifact> artifacts) {
        this(text, images, artifacts, List.of());
    }
    /** 本次流订阅的产物接收器，类型为 BiConsumer<Agent, ToolResultBlock>；不写入 SDK 持久状态。 */
    public static final String ARTIFACTS_LISTENER_CONTEXT_KEY = "gmd.tool.result.artifacts.listener";
    public static final String EVENT_NAME = "TOOL_RESULT_CONTENT";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ToolResultPresentation from(ToolResultBlock result) {
        String text = result.getOutput() == null ? "" : result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining("\n"));
        Object images = result.getMetadata().get(METADATA_KEY);
        Object artifacts = result.getMetadata().get(ARTIFACTS_METADATA_KEY);
        Object citations = result.getMetadata().get(CITATIONS_METADATA_KEY);
        return new ToolResultPresentation(text, images == null ? List.of()
                : MAPPER.convertValue(images, new TypeReference<List<Image>>() {}), artifacts == null ? List.of()
                : MAPPER.convertValue(artifacts, new TypeReference<List<Artifact>>() {}), citations == null ? List.of()
                : MAPPER.convertValue(citations, new TypeReference<List<Citation>>() {}));
    }

    public boolean hasAttachments() { return !images.isEmpty() || !artifacts.isEmpty() || !citations.isEmpty(); }

    /** 引用作为检索证据保存；远端文件路径用于标识来源，不能直接作为下载地址。 */
    public record Citation(String knowledgeId, String knowledgeName, String referenceId,
                           String fileName, String filePath, String content) {}

    public record Image(String filePath, String fileName, String extension, String mimeType,
                        Long sizeBytes, String error, String sourcePath) {
        public Image(String filePath, String fileName, String extension, String mimeType, Long sizeBytes, String error) {
            this(filePath, fileName, extension, mimeType, sizeBytes, error, null);
        }
    }

    /** 与工具调用一起持久化，不依赖异步聊天消息的数据库主键。 */
    public record Artifact(String filePath, String fileName, String extension, Long sizeBytes,
                           String sourceTool, String createTime) {}
}
