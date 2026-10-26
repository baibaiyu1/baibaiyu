package com.gmd.faq.server.main.engine.formatter;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Flux;

/** 把工具图片作为视觉输入交给模型，兼容只允许纯文本 tool 消息的供应商。 */
public final class ToolResultImageModel implements Model {
    private final Model delegate;

    public ToolResultImageModel(Model delegate) {
        this.delegate = delegate;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return delegate.stream(withImageInputs(messages), tools, options);
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }
    @Override public boolean supportsNativeStructuredOutput() { return delegate.supportsNativeStructuredOutput(); }
    @Override public boolean supportsNativeStructuredOutputWithTools() { return delegate.supportsNativeStructuredOutputWithTools(); }
    @Override public int getContextWindowSize() { return delegate.getContextWindowSize(); }

    static List<Msg> withImageInputs(List<Msg> messages) {
        List<Msg> output = new ArrayList<>();
        List<ContentBlock> images = new ArrayList<>();
        for (Msg message : ToolCallMessageSanitizer.sanitize(messages)) {
            if (message.getRole() != MsgRole.TOOL) {
                appendImages(output, images);
                if (message.getRole() == MsgRole.ASSISTANT
                        && Boolean.TRUE.equals(message.getMetadata().get(MessageMetadataKeys.TOOL_RETURN_DIRECT))) {
                    var text = message.getContent().stream().filter(block -> !(block instanceof ImageBlock)).toList();
                    var directImages = message.getContentBlocks(ImageBlock.class);
                    if (!directImages.isEmpty()) {
                        if (!text.isEmpty()) output.add(Msg.builder().id(message.getId()).role(message.getRole())
                                .name(message.getName()).timestamp(message.getTimestamp()).content(text).metadata(message.getMetadata()).build());
                        images.add(TextBlock.builder().text("以下图片是上一轮工具直接返回的实际内容，请结合图片回答用户。").build());
                        images.addAll(directImages);
                        continue;
                    }
                }
                output.add(message);
                continue;
            }
            List<ContentBlock> blocks = new ArrayList<>();
            for (ContentBlock block : message.getContent()) {
                if (!(block instanceof ToolResultBlock result)) {
                    blocks.add(block);
                    continue;
                }
                List<ContentBlock> textOutput = new ArrayList<>();
                List<ImageBlock> toolImages = new ArrayList<>();
                for (ContentBlock item : result.getOutput()) {
                    if (item instanceof ImageBlock image) toolImages.add(image);
                    else textOutput.add(item);
                }
                if (!toolImages.isEmpty()) {
                    images.add(TextBlock.builder().text("以下图片是工具 " + result.getName()
                            + "（调用 " + result.getId() + "）的实际返回内容，请结合图片回答用户。").build());
                    images.addAll(toolImages);
                    textOutput.add(TextBlock.builder().text("工具返回了 " + toolImages.size()
                            + " 张图片，视觉内容随本组工具结果附后。").build());
                }
                blocks.add(ToolResultBlock.of(result.getId(), result.getName(), textOutput, result.getMetadata()).withState(result.getState()));
            }
            output.add(Msg.builder().role(message.getRole()).name(message.getName())
                    .content(blocks).metadata(message.getMetadata()).build());
        }
        appendImages(output, images);
        return output;
    }

    private static void appendImages(List<Msg> messages, List<ContentBlock> images) {
        if (images.isEmpty()) return;
        // 必须在本组所有 tool 结果之后追加，避免打断并行工具的调用/结果配对。
        messages.add(Msg.builder().role(MsgRole.USER).content(new ArrayList<>(images)).build());
        images.clear();
    }
}
