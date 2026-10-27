package com.gmd.faq.server.main.engine.sensitive;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Flux;

/** SDK 在 PostCall 前发送直接返回正文；延迟这部分投影，使用过滤后的最终回复重建。 */
final class SensitiveWordDirectStream {
    private final Map<BlockKey, TextBlockStartEvent> starts = new HashMap<>();
    private final Set<BlockKey> directBlocks = new HashSet<>();
    private final Map<String, List<TextBlockDeltaEvent>> directReplies = new HashMap<>();

    Flux<AgentEvent> apply(AgentEvent event) {
        if (event instanceof TextBlockStartEvent start) {
            starts.put(new BlockKey(start.getSource(), start.getReplyId(), start.getBlockId()), start);
            return Flux.empty();
        }
        if (event instanceof TextBlockDeltaEvent delta) {
            BlockKey key = new BlockKey(delta.getSource(), delta.getReplyId(), delta.getBlockId());
            TextBlockStartEvent start = starts.remove(key);
            if (delta.getMetadata() != null && GenerateReason.TOOL_RETURN_DIRECT.name()
                    .equals(delta.getMetadata().get(AgentEvent.METADATA_GENERATE_REASON))) {
                directBlocks.add(key);
                directReplies.computeIfAbsent(delta.getSource(), ignored -> new ArrayList<>()).add(delta);
                return Flux.empty();
            }
            return start == null ? Flux.just(event) : Flux.just(start, event);
        }
        if (event instanceof TextBlockEndEvent end) {
            BlockKey key = new BlockKey(end.getSource(), end.getReplyId(), end.getBlockId());
            TextBlockStartEvent start = starts.remove(key);
            if (directBlocks.remove(key)) return Flux.empty();
            return start == null ? Flux.just(event) : Flux.just(start, event);
        }
        if (event instanceof AgentResultEvent result) {
            List<TextBlockDeltaEvent> original = directReplies.remove(event.getSource());
            if (original != null) {
                List<AgentEvent> filtered = new ArrayList<>();
                List<TextBlock> blocks = result.getResult().getContentBlocks(TextBlock.class);
                for (int i = 0; i < blocks.size(); i++) {
                    TextBlockDeltaEvent template = original.get(Math.min(i, original.size() - 1));
                    String replyId = template.getReplyId();
                    String blockId = template.getBlockId();
                    filtered.add(new TextBlockStartEvent(replyId, blockId).withSource(template.getSource()));
                    filtered.add(new TextBlockDeltaEvent(replyId, blockId, blocks.get(i).getText())
                            .withMetadata(template.getMetadata()).withSource(template.getSource()));
                    filtered.add(new TextBlockEndEvent(replyId, blockId).withSource(template.getSource()));
                }
                filtered.add(event);
                return Flux.fromIterable(filtered);
            }
        }
        return Flux.just(event);
    }

    private record BlockKey(String source, String replyId, String blockId) {}
}
