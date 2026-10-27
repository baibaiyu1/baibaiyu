package com.gmd.faq.server.main.engine.sensitive;

import com.gmd.faq.server.main.common.enums.SensitiveWordAction;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Flux;

/** 在 SDK 生成文字事件、保存回复之前过滤；包在模型容错层外，避免扩大重试边界。 */
public final class SensitiveWordModel implements Model {
    private final Model delegate;
    private final SensitiveWordPolicy policy;
    private final Runnable warning;

    public SensitiveWordModel(Model delegate, SensitiveWordPolicy policy, Runnable warning) {
        this.delegate = delegate; this.policy = policy; this.warning = warning;
    }

    @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        if (!policy.active()) return delegate.stream(messages, tools, options);
        return Flux.defer(() -> {
            var responses = delegate.stream(messages, tools, options);
            if (policy.action() == SensitiveWordAction.WARN) {
                StringBuilder output = new StringBuilder();
                StringBuilder thinking = new StringBuilder();
                return responses.doOnNext(chunk -> append(chunk, output, thinking)).doOnComplete(() -> {
                    if (policy.matches(output.toString()) || policy.matches(thinking.toString())) warning.run();
                });
            }
            // 阻断和替换须先检查完整的模型响应，不能先向客户端泄露前缀或部分词条。
            return responses.collectList().flatMapMany(chunks -> Flux.fromIterable(filter(policy, chunks)));
        });
    }

    private static void append(ChatResponse chunk, StringBuilder text, StringBuilder thinking) {
        for (ContentBlock block : chunk.getContent()) {
            if (block instanceof TextBlock value) text.append(value.getText());
            if (block instanceof ThinkingBlock value && value.getThinking() != null) thinking.append(value.getThinking());
        }
    }

    static boolean matches(SensitiveWordPolicy policy, List<ContentBlock> blocks) {
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        append(ChatResponse.builder().content(blocks).build(), text, thinking);
        return policy.matches(text.toString()) || policy.matches(thinking.toString());
    }

    static List<ChatResponse> filter(SensitiveWordPolicy policy, List<ChatResponse> chunks) {
        StringBuilder text = new StringBuilder(); StringBuilder thinking = new StringBuilder();
        chunks.forEach(chunk -> append(chunk, text, thinking));
        if (!policy.matches(text.toString()) && !policy.matches(thinking.toString())) return chunks;
        if (policy.action() == SensitiveWordAction.WARN) return chunks;
        if (policy.action() == SensitiveWordAction.BLOCK) {
            ChatUsage usage = null; String id = null;
            for (ChatResponse chunk : chunks) {
                if (chunk.getUsage() != null) usage = chunk.getUsage();
                if (chunk.getId() != null) id = chunk.getId();
            }
            return List.of(ChatResponse.builder().id(id).usage(usage).finishReason("stop")
                    .content(List.of(TextBlock.builder().text(SensitiveWordPolicy.BLOCKED_REPLY).build())).build());
        }
        List<ChatResponse> filtered = new ArrayList<>();
        int textOffset = 0; int thinkingOffset = 0;
        for (ChatResponse chunk : chunks) {
            List<ContentBlock> blocks = new ArrayList<>();
            for (ContentBlock block : chunk.getContent()) {
                if (block instanceof TextBlock value) {
                    int end = textOffset + value.getText().length();
                    blocks.add(TextBlock.builder().text(policy.replaceSlice(text.toString(), textOffset, end)).build());
                    textOffset = end;
                } else if (block instanceof ThinkingBlock value && value.getThinking() != null) {
                    int end = thinkingOffset + value.getThinking().length();
                    blocks.add(ThinkingBlock.builder().thinking(policy.replaceSlice(thinking.toString(), thinkingOffset, end))
                            .metadata(value.getMetadata()).build());
                    thinkingOffset = end;
                } else blocks.add(block);
            }
            filtered.add(new ChatResponse(chunk.getId(), blocks, chunk.getUsage(), chunk.getMetadata(), chunk.getFinishReason()));
        }
        return filtered;
    }

    @Override public String getModelName() { return delegate.getModelName(); }
    @Override public boolean supportsNativeStructuredOutput() { return delegate.supportsNativeStructuredOutput(); }
    @Override public boolean supportsNativeStructuredOutputWithTools() { return delegate.supportsNativeStructuredOutputWithTools(); }
    @Override public int getContextWindowSize() { return delegate.getContextWindowSize(); }
}
