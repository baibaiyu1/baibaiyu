package com.gmd.faq.server.main.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.util.AgentTagSupport;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentTagSupportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void normalizeTrimsDeduplicatesAndPreservesOrder() {
        assertEquals(List.of("alpha", "Beta"),
                AgentTagSupport.normalize(Arrays.asList(" alpha ", "Beta", "alpha", " ", "", null)));
    }

    @Test
    void normalizeRejectsOversizedTagsAfterTrimming() {
        assertEquals(List.of("a".repeat(100)), AgentTagSupport.normalize(List.of(" " + "a".repeat(100) + " ")));
        assertThrows(IllegalArgumentException.class,
                () -> AgentTagSupport.normalize(List.of("a".repeat(101))));
    }

    @Test
    void tagsOfFallsBackToLegacyTagOnlyWhenJsonIsAbsent() throws Exception {
        AgentDefinition agent = new AgentDefinition();
        agent.setTag("legacy");
        assertEquals(List.of("legacy"), AgentTagSupport.tagsOf(agent));

        agent.setTagsJson(mapper.readTree("[\"first\",\"second\"]"));
        assertEquals(List.of("first", "second"), AgentTagSupport.tagsOf(agent));

        agent.setTagsJson(mapper.readTree("[]"));
        assertEquals(List.of(), AgentTagSupport.tagsOf(agent));
    }

    @Test
    void setTagsPersistsFirstTagAndExplicitEmptySelection() {
        AgentDefinition agent = new AgentDefinition();
        AgentTagSupport.setTags(agent, List.of("first", "second"));
        assertEquals("first", agent.getTag());
        assertEquals(List.of("first", "second"), AgentTagSupport.tagsOf(agent));

        AgentTagSupport.setTags(agent, List.of());
        assertEquals("", agent.getTag());
        assertEquals(List.of(), AgentTagSupport.tagsOf(agent));
        assertEquals(0, agent.getTagsJson().size());
    }
}
