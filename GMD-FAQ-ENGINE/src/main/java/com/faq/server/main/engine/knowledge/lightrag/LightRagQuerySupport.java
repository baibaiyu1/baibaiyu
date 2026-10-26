package com.gmd.faq.server.main.engine.knowledge.lightrag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 型号查询按实际图谱来源和图片对应关系约束候选，不能把同文档命中当作同产品。 */
final class LightRagQuerySupport {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z][a-zA-Z0-9]*(?:[-_./][a-zA-Z0-9]+)*");

    private LightRagQuerySupport() {}

    static Set<String> graphSources(String query, List<Map<String, Object>> entities,
            List<Map<String, Object>> relationships) {
        List<String> identifiers = identifiers(query);
        Set<String> sources = new HashSet<>();
        for (Map<String, Object> entity : entities) {
            if (matches(entity.get("entity_name"), identifiers)) addPaths(sources, entity.get("file_path"));
        }
        for (Map<String, Object> relation : relationships) {
            if (matches(relation.get("src_id"), identifiers) || matches(relation.get("tgt_id"), identifiers)) {
                addPaths(sources, relation.get("file_path"));
            }
        }
        return sources;
    }

    static boolean relevantChunk(String query, String content, String filePath, Set<String> graphSources) {
        return graphSources.isEmpty() || graphSources.contains(filePath) || matches(content, identifiers(query));
    }

    private static void addPaths(Set<String> sources, Object value) {
        if (value instanceof List<?> paths) paths.forEach(path -> addPaths(sources, path));
        if (value instanceof String text) for (String path : text.split("<SEP>")) {
            String source = path.trim();
            if (!source.isBlank() && !"unknown_source".equalsIgnoreCase(source) && !source.startsWith("...")) sources.add(source);
        }
    }

    static int candidateLimit(String query, int limit) {
        // 远端按文档顺序读取图片；先取得有界候选，再应用用户设置的展示数量。
        return identifiers(query).isEmpty() ? limit : 20;
    }

    static List<Map<String, Object>> select(String query, List<Map<String, Object>> images,
            List<Map<String, Object>> entities, List<Map<String, Object>> relationships, int limit) {
        List<String> identifiers = identifiers(query);
        if (identifiers.isEmpty()) return images.stream().limit(limit).toList();
        Set<String> related = new HashSet<>();
        for (Map<String, Object> entity : entities) {
            if ("drawing".equalsIgnoreCase(String.valueOf(entity.get("entity_type")))
                    && matches(entity.get("description"), identifiers)) {
                related.add(String.valueOf(entity.get("entity_name")));
            }
        }
        for (Map<String, Object> relation : relationships) {
            String source = String.valueOf(relation.get("src_id"));
            String target = String.valueOf(relation.get("tgt_id"));
            if (matches(source, identifiers) && target.startsWith("im-")) related.add(target);
            if (matches(target, identifiers) && source.startsWith("im-")) related.add(source);
        }
        return images.stream().filter(image -> matches(image.get("caption"), identifiers)
                || related.contains(String.valueOf(image.get("id")))).limit(limit).toList();
    }

    private static List<String> identifiers(String query) {
        List<String> identifiers = new ArrayList<>();
        var matcher = IDENTIFIER.matcher(query == null ? "" : query);
        while (matcher.find()) {
            String token = matcher.group().toLowerCase(Locale.ROOT);
            if (token.length() >= 3 && token.chars().anyMatch(Character::isDigit)) identifiers.add(token);
        }
        return identifiers;
    }

    private static boolean matches(Object value, List<String> identifiers) {
        if (!(value instanceof String text)) return false;
        for (String identifier : identifiers) {
            if (Pattern.compile("(?i)(?<![a-z0-9])" + Pattern.quote(identifier) + "(?![0-9])").matcher(text).find()) return true;
        }
        return false;
    }
}
