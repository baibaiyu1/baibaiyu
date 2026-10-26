package com.gmd.faq.server.main.engine.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import com.gmd.faq.server.main.common.enums.KbType;
import com.gmd.faq.server.main.common.model.KnowledgeRetrievalSettings;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeRetrievalSettingsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test void twoAgentsCanOverrideTheSameKnowledgeWithoutMutatingItsDefaults() throws Exception {
        var source = new KnowledgeBaseConfig();
        source.setId(42L); source.setKbType(KbType.LIGHTRAG);
        source.setConnectionConfig(MAPPER.readTree("{\"baseUrl\":\"http://lightrag:9621\",\"apiKey\":\"secret\"}"));
        source.setRetrievalConfig(MAPPER.readTree("{\"mode\":\"mix\",\"topK\":20,\"graphTopK\":60,\"enableRerank\":true}"));
        source.setHttpConfig(MAPPER.readTree("{\"timeoutSeconds\":180,\"maxRetries\":2}"));
        var fast = KnowledgeRetrievalSettings.parse(MAPPER.readTree("""
                {"topK":6,"includeImages":false,"maxRetries":0,"lightRag":{"mode":"naive","graphTopK":20,"enableRerank":false}}
                """)).apply(source);
        var graph = KnowledgeRetrievalSettings.parse(MAPPER.readTree("{\"lightRag\":{\"mode\":\"local\"}}")).apply(source);
        assertEquals("naive", fast.getRetrievalConfig().path("mode").asText());
        assertEquals(6, fast.getRetrievalConfig().path("topK").asInt());
        assertFalse(fast.getRetrievalConfig().path("includeImages").asBoolean());
        assertFalse(fast.getRetrievalConfig().path("enableRerank").asBoolean());
        assertEquals(0, fast.getHttpConfig().path("maxRetries").asInt());
        assertEquals("local", graph.getRetrievalConfig().path("mode").asText());
        assertEquals(20, graph.getRetrievalConfig().path("topK").asInt());
        assertTrue(graph.getRetrievalConfig().path("enableRerank").asBoolean());
        assertEquals("mix", source.getRetrievalConfig().path("mode").asText());
        assertEquals(60, source.getRetrievalConfig().path("graphTopK").asInt());
        assertEquals(2, source.getHttpConfig().path("maxRetries").asInt());
        assertEquals(source.getConnectionConfig(), fast.getConnectionConfig());
    }

    @Test void rejectsInvalidServerSideBudgetsAndPreservesLegacyFields() throws Exception {
        for (String json : new String[]{"[]", "{\"maxCallsPerRound\":0}", "{\"timeoutSeconds\":181}",
                "{\"maxImages\":21}", "{\"maxImages\":0}", "{\"includeImages\":\"true\"}", "{\"topK\":2.5}",
                "{\"maxContextChars\":2000,\"maxChunkChars\":3000}", "{\"lightRag\":{\"mode\":\"bypass\"}}",
                "{\"lightRag\":{\"enableRerank\":\"off\"}}", "{\"lightRag\":{\"graphTopK\":0}}"}) {
            assertThrows(IllegalArgumentException.class, () -> KnowledgeRetrievalSettings.parse(MAPPER.readTree(json)), json);
        }
        var legacy = KnowledgeRetrievalSettings.parse(MAPPER.readTree("{\"topK\":17,\"scoreThreshold\":0.2,\"ragMode\":\"GENERIC\"}"));
        assertEquals(17, legacy.topK()); assertEquals(0.2, legacy.scoreThreshold()); assertEquals("GENERIC", legacy.ragMode());
        assertNull(KnowledgeRetrievalSettings.parse(null).topK());
        assertEquals(12, KnowledgeRetrievalSettings.parse(MAPPER.readTree("{\"maxImages\":12}")).maxImages());
    }
}
