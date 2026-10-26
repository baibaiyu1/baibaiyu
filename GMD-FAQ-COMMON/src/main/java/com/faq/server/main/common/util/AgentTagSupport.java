package com.gmd.faq.server.main.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.gmd.faq.server.main.common.entity.AgentDefinition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Keeps the multi-tag field and the legacy primary tag consistent. */
public final class AgentTagSupport {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentTagSupport() {
    }

    public static List<String> tagsOf(AgentDefinition agent) {
        JsonNode stored = agent.getTagsJson();
        if (stored != null && stored.isArray()) {
            List<String> tags = new ArrayList<>();
            stored.forEach(item -> {
                if (item.isTextual() && !item.asText().isBlank()) {
                    tags.add(item.asText());
                }
            });
            return List.copyOf(tags);
        }
        return agent.getTag() == null || agent.getTag().isBlank()
                ? List.of() : List.of(agent.getTag());
    }

    public static List<String> normalize(List<String> tags) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : tags) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String tag = value.trim();
            if (tag.length() > 100) {
                throw new IllegalArgumentException("标签长度不能超过100个字符");
            }
            unique.add(tag);
        }
        return List.copyOf(unique);
    }

    public static void setTags(AgentDefinition agent, List<String> tags) {
        ArrayNode json = MAPPER.createArrayNode();
        tags.forEach(json::add);
        agent.setTagsJson(json);
        agent.setTag(tags.isEmpty() ? "" : tags.getFirst());
    }
}
