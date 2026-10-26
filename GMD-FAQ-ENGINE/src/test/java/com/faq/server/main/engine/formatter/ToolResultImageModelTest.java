package com.gmd.faq.server.main.engine.formatter;

import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ToolResultImageModelTest {
    @Test void directAnswerImagesBecomeVisualInputBeforeTheNextQuestionWithoutChangingHistory() {
        var image = ImageBlock.builder().source(new Base64Source("image/png", "aW1hZ2U=")).build();
        Msg answer = Msg.builder().id("direct-answer").name("assistant").role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text("原始答复").build(), image)
                .metadata(Map.of(MessageMetadataKeys.TOOL_RETURN_DIRECT, true)).build();
        Msg question = Msg.builder().role(MsgRole.USER).textContent("解释刚才的图片").build();
        var input = ToolResultImageModel.withImageInputs(List.of(answer, question));
        assertEquals(List.of(MsgRole.ASSISTANT, MsgRole.USER, MsgRole.USER), input.stream().map(Msg::getRole).toList());
        assertEquals("原始答复", input.getFirst().getTextContent());
        assertEquals(answer.getId(), input.getFirst().getId());
        assertEquals(answer.getTimestamp(), input.getFirst().getTimestamp());
        assertEquals(answer.getMetadata(), input.getFirst().getMetadata());
        assertTrue(input.getFirst().getContentBlocks(ImageBlock.class).isEmpty());
        assertEquals(List.of(image), input.get(1).getContentBlocks(ImageBlock.class));
        assertSame(question, input.getLast());
        assertEquals(List.of(image), answer.getContentBlocks(ImageBlock.class));
        Msg imageOnly = Msg.builder().role(MsgRole.ASSISTANT).content(image).metadata(answer.getMetadata()).build();
        var imageOnlyInput = ToolResultImageModel.withImageInputs(List.of(imageOnly, question));
        assertEquals(2, imageOnlyInput.size());
        assertEquals(List.of(image), imageOnlyInput.getFirst().getContentBlocks(ImageBlock.class));
        assertSame(question, imageOnlyInput.getLast());
    }

    @Test void testImagesReachProviderAsVisualInputAfterAllParallelToolResults() throws Exception {
        var image = ImageBlock.builder().source(new Base64Source("image/png", "aW1hZ2U=")).build();
        Msg calls = Msg.builder().role(MsgRole.ASSISTANT).content(
                ToolUseBlock.builder().id("one").name("chart").input(Map.of()).build(),
                ToolUseBlock.builder().id("two").name("chart").input(Map.of()).build()).build();
        Msg results = Msg.builder().role(MsgRole.TOOL).content(
                ToolResultBlock.of("one", "chart", List.of(TextBlock.builder().text("图表一").build(), image)),
                ToolResultBlock.of("two", "chart", image)).build();
        List<Msg> input = ToolResultImageModel.withImageInputs(List.of(calls, results));
        assertEquals(List.of(MsgRole.ASSISTANT, MsgRole.TOOL, MsgRole.TOOL, MsgRole.USER),
                input.stream().map(Msg::getRole).toList());
        assertEquals(2, input.getLast().getContentBlocks(ImageBlock.class).size());
        assertFalse(input.get(1).getFirstContentBlock(ToolResultBlock.class).getOutput().stream().anyMatch(ImageBlock.class::isInstance));
        assertEquals(2, results.getContentBlocks(ToolResultBlock.class).size());
        assertTrue(results.getFirstContentBlock(ToolResultBlock.class).getOutput().contains(image));
        var wire = new OpenAIChatFormatter().format(input);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(wire);
        assertTrue(json.contains("image_url"));
        assertTrue(json.contains("data:image/png;base64,aW1hZ2U="));
    }

    @Test void testImagesRemainBeforeFollowingUserQuestionAndOrphansAreNotReintroduced() {
        var image = ImageBlock.builder().source(new Base64Source("image/png", "aW1hZ2U=")).build();
        Msg call = Msg.builder().role(MsgRole.ASSISTANT).content(ToolUseBlock.builder().id("one").name("chart").input(Map.of()).build()).build();
        Msg result = Msg.builder().role(MsgRole.TOOL).content(ToolResultBlock.of("one", "chart", image)).build();
        Msg question = Msg.builder().role(MsgRole.USER).textContent("解释图片中的趋势").build();
        var input = ToolResultImageModel.withImageInputs(List.of(call, result, question));
        assertSame(question, input.getLast());
        assertEquals(1, input.get(input.size() - 2).getContentBlocks(ImageBlock.class).size());
        assertEquals(List.of(question), ToolResultImageModel.withImageInputs(List.of(result, question)));
    }
}
