package com.gmd.faq.server.main.runtime.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import com.gmd.faq.server.main.common.enums.KbType;
import com.gmd.faq.server.main.engine.knowledge.IKnowledge;
import com.gmd.faq.server.main.engine.knowledge.KnowledgeFactory;
import com.gmd.faq.server.main.knowledge.service.KnowledgeBaseConfigService;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagLightRagSearchTest {
    private final KnowledgeBaseConfigService configs = mock(KnowledgeBaseConfigService.class);
    private final ResourceVisibilityService visibility = mock(ResourceVisibilityService.class);
    private final IKnowledge adapter = mock(IKnowledge.class);
    private final Knowledge knowledge = mock(Knowledge.class);
    private final RagEndPoint endpoint = new RagEndPoint(null, null, null, null, configs, null, visibility);
    private KnowledgeBaseConfig config;
    private final Map<String, Object> params = Map.of("knowledgeBaseConfigId", "42", "query", "知识库检索");

    @BeforeEach
    void setup() {
        config = new KnowledgeBaseConfig();
        config.setId(42L);
        config.setKbType(KbType.LIGHTRAG);
        config.setEnabled(true);
        config.setRetrievalConfig(new ObjectMapper().createObjectNode().put("topK", 12));
        when(configs.getById(42L)).thenReturn(config);
        when(adapter.type()).thenReturn(KbType.LIGHTRAG);
        when(adapter.build(any())).thenReturn(knowledge);
        KnowledgeFactory.register(adapter);
    }

    @AfterEach
    void cleanup() {
        KnowledgeFactory.unregister(KbType.LIGHTRAG);
    }

    @Test
    void unifiedSearchRetainsSourcesAndUnknownScoresAndUsesChunkLimit() {
        Document doc = new Document(DocumentMetadata.builder().docId("source").chunkId("c1")
                .content(TextBlock.builder().text("来源正文").build())
                .payload(Map.of("fileName", "manual.md", "contentType", "chunk")).build());
        doc.setScore(null);
        when(knowledge.retrieve(anyString(), any())).thenReturn(Mono.just(List.of(doc)));
        var result = endpoint.search(params, KbType.LIGHTRAG);
        assertEquals(200, result.getCode());
        assertEquals("manual.md", result.getData().getFirst().get("fileName"));
        assertNull(result.getData().getFirst().get("score"));
        assertEquals("来源正文", result.getData().getFirst().get("content"));
        verify(visibility).assertUsable("KNOWLEDGE", 42L);
        var retrieveConfig = ArgumentCaptor.forClass(RetrieveConfig.class);
        verify(knowledge).retrieve(eq("知识库检索"), retrieveConfig.capture());
        assertEquals(12, retrieveConfig.getValue().getLimit());
        assertEquals(0, retrieveConfig.getValue().getScoreThreshold());
    }

    @Test
    void refusesUnauthorizedDisabledAndMismatchedKnowledgeBeforeCallingRemote() {
        doThrow(new IllegalStateException("资源不存在或无权使用")).when(visibility).assertUsable("KNOWLEDGE", 42L);
        assertThrows(IllegalStateException.class, () -> endpoint.search(params, KbType.LIGHTRAG));
        verify(adapter, never()).build(any());
        reset(visibility);
        config.setEnabled(false);
        assertEquals(400, endpoint.search(params, KbType.LIGHTRAG).getCode());
        assertEquals(400, endpoint.search(params, KbType.DIFY).getCode());
        verify(adapter, never()).build(any());
    }

    @Test
    void remoteFailureIsAnErrorAndNotAnEmptySuccessfulResult() {
        when(knowledge.retrieve(anyString(), any())).thenReturn(Mono.error(new IllegalStateException("LightRAG 检索超时")));
        var result = endpoint.search(params, KbType.LIGHTRAG);
        assertEquals(400, result.getCode());
        assertTrue(result.getMsg().contains("超时"));
    }
}
