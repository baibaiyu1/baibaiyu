package com.gmd.faq.server.main.engine.knowledge;

import com.gmd.faq.server.main.common.wrapper.KnowledgeWrapper;
import com.gmd.faq.server.main.common.model.KnowledgeRetrievalSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.engine.tool.ToolResultPresentation;
import io.agentscope.core.message.*;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.*;
import io.agentscope.core.tool.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeRetrievalToolTest {
    @Test void sdkToolPreservesMetadataAndUsesServerBudgetInsteadOfModelArguments() {
        AtomicInteger limit = new AtomicInteger();
        var tool = new KnowledgeRetrievalTool(List.of(wrapper((query, config) -> {
            limit.set(config.getLimit());
            return Mono.just(List.of(document("a", "设备说明.pdf", "第一段"),
                    document("a", "设备说明.pdf", "第二段"), document("b", "设备说明.pdf", "另一知识库")));
        })), RetrieveConfig.builder().limit(17).build());
        Toolkit toolkit = new Toolkit(); toolkit.registerTool(tool);
        var result = toolkit.callTool(ToolCallParam.builder().toolUseBlock(ToolUseBlock.builder()
                .id("query").name(KnowledgeRetrievalTool.NAME).content("{\"query\":\"设备如何使用\",\"limit\":1}")
                .input(Map.of("query", "设备如何使用", "limit", 1)).build())
                .input(Map.of("query", "设备如何使用", "limit", 1)).build()).block(Duration.ofSeconds(10));
        assertNotNull(result);
        assertEquals(ToolResultState.SUCCESS, result.getState(), () -> ToolResultPresentation.from(result).text());
        assertEquals(17, limit.get());
        var presentation = ToolResultPresentation.from(result);
        assertEquals(2, presentation.citations().size(), "相同文件在同库去重，跨库保留独立来源");
        assertTrue(presentation.citations().getFirst().content().contains("第二段"));
        assertTrue(presentation.hasAttachments());
        var json = new ObjectMapper().valueToTree(presentation.citations());
        assertNotEquals(json.get(0).path("referenceId").asText(), json.get(1).path("referenceId").asText(), "来源号必须跨库隔离");
        assertTrue(presentation.text().contains("[[cite:" + presentation.citations().getFirst().referenceId() + "]]"));
        assertEquals(Set.of("query"), ((Map<?, ?>)toolkit.getTool(KnowledgeRetrievalTool.NAME).getParameters().get("properties")).keySet());
    }

    @Test void citationMarkersStayStableAcrossCallsAndGraphReferencesMapToVerifiedSources() throws Exception {
        var graph = new Document(DocumentMetadata.builder().content(TextBlock.builder().text("关系来源[7]").build())
                .docId("graph").chunkId("g").payload(Map.of("knowledgeId", "a", "contentType", "graph",
                        "references", List.of(Map.of("file_path", "设备说明.pdf", "reference_id", "7")))).build());
        var tool = new KnowledgeRetrievalTool(List.of(wrapper((q, c) -> Mono.just(List.of(document("a", "设备说明.pdf", "原文"), graph)))),
                RetrieveConfig.builder().build());
        var first = ToolResultPresentation.from(tool.retrieve("一", RetrieveConfig.builder().build()).block());
        var second = ToolResultPresentation.from(tool.retrieve("二", RetrieveConfig.builder().build()).block());
        assertEquals(first.citations(), second.citations());
        assertEquals(1, first.citations().size());
        var json = new ObjectMapper().readTree(first.text());
        var marker = json.path("documents").get(0).path("citation").asText();
        assertEquals(marker, json.path("documents").get(1).path("sourceReferences").get(0).path("citation").asText());
        assertEquals("7", json.path("documents").get(1).path("sourceReferences").get(0).path("referenceId").asText());
    }

    @Test void retrievalFailuresKeepErrorStateAndDoNotExposeRemoteErrorText() {
        var tool = new KnowledgeRetrievalTool(List.of(wrapper((query, config) ->
                Mono.error(new IllegalStateException("remote-secret")))), RetrieveConfig.builder().build());
        var result = tool.retrieve("设备", RetrieveConfig.builder().build()).block();
        assertEquals(ToolResultState.ERROR, result.getState());
        assertFalse(ToolResultPresentation.from(result).text().contains("remote-secret"));
    }

    @Test void capsDeduplicatesEvidenceAndLimitsImagesAcrossKnowledgeBases() throws Exception {
        var mapper = new ObjectMapper();
        var first = new Document(DocumentMetadata.builder().content(TextBlock.builder().text("甲".repeat(900)).build())
                .docId("first").chunkId("one").payload(Map.of("knowledgeId", "42", "file_path", "手册.pdf",
                        "images", List.of(Map.of("id", "1"), Map.of("id", "2"), Map.of("id", "3")))).build());
        var settings = KnowledgeRetrievalSettings.parse(mapper.readTree("{\"maxContextChars\":2000,\"maxChunkChars\":700,\"maxImages\":2}"));
        var kb = wrapper((query, config) -> Mono.just(List.of(first, first, document("42", "手册.pdf", "乙".repeat(1900)),
                document("42", "手册.pdf", "丙".repeat(1900)), document("42", "手册.pdf", "超出预算"))));
        var result = new KnowledgeRetrievalTool(List.of(kb), RetrieveConfig.builder().build(), settings)
                .retrieve("问题", RetrieveConfig.builder().build()).block();
        var json = mapper.readTree(ToolResultPresentation.from(result).text());
        assertEquals(3, json.path("documents").size());
        int length = 0;
        for (var doc : json.path("documents")) length += doc.path("content").asText().length();
        assertTrue(length <= 2000);
        assertEquals(2, json.path("images").size());
        assertTrue(json.path("instruction").asText().contains("证据缺失"));
        var noImages = KnowledgeRetrievalSettings.parse(mapper.readTree("{\"includeImages\":false}"));
        var noImageResult = new KnowledgeRetrievalTool(List.of(kb), RetrieveConfig.builder().build(), noImages)
                .retrieve("问题", RetrieveConfig.builder().build()).block();
        assertEquals(0, mapper.readTree(ToolResultPresentation.from(noImageResult).text()).path("images").size());
    }

    @Test void timeoutCancelsSlowRetrievalAndDoesNotMasqueradeAsEvidence() throws Exception {
        var cancelled = new AtomicInteger();
        var settings = KnowledgeRetrievalSettings.parse(new ObjectMapper().readTree("{\"timeoutSeconds\":1}"));
        var tool = new KnowledgeRetrievalTool(List.of(wrapper((q, c) -> Mono.<List<Document>>never()
                .doOnCancel(cancelled::incrementAndGet))), RetrieveConfig.builder().build(), settings);
        var result = tool.retrieve("问题", RetrieveConfig.builder().build()).block(Duration.ofSeconds(3));
        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals(1, cancelled.get());
        assertTrue(ToolResultPresentation.from(result).citations().isEmpty());
    }

    private interface Retrieval { Mono<List<Document>> apply(String query, RetrieveConfig config); }
    private static KnowledgeWrapper wrapper(Retrieval retrieval) {
        Knowledge knowledge = new Knowledge() {
            public Mono<Void> addDocuments(List<Document> documents) { return Mono.empty(); }
            public Mono<List<Document>> retrieve(String query, RetrieveConfig config) { return retrieval.apply(query, config); }
        };
        return KnowledgeWrapper.builder().knowledge(knowledge).build();
    }
    private static Document document(String kb, String path, String text) {
        return new Document(DocumentMetadata.builder().content(TextBlock.builder().text(text).build())
                .docId(path).chunkId(text)
                .payload(Map.of("knowledgeId", kb, "knowledgeName", "库" + kb, "file_path", path,
                        "reference_id", "1", "references", List.of(Map.of("file_path", path))))
                .build());
    }
}
