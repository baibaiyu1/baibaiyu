package com.gmd.faq.server.main.engine.sensitive;

import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.SensitiveWordConfig;
import com.gmd.faq.server.main.common.enums.SensitiveWordAction;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** 每次运行使用服务端配置；词条和替换内容都按字面量处理。 */
public final class SensitiveWordPolicy {
    public static final String BLOCKED_REPLY = "内容涉及敏感词，已阻断本次回复。";
    private final Pattern pattern;
    private final SensitiveWordAction action;
    private final String replacement;

    private SensitiveWordPolicy(Pattern pattern, SensitiveWordAction action, String replacement) {
        this.pattern = pattern; this.action = action; this.replacement = replacement;
    }

    public static SensitiveWordPolicy from(AgentDefinition definition, SensitiveWordConfig config) {
        if (!Boolean.TRUE.equals(definition.getSensitiveFilterEnabled()) || config == null
                || !Boolean.TRUE.equals(config.getEnabled()) || config.getWords() == null || !config.getWords().isArray())
            return new SensitiveWordPolicy(null, SensitiveWordAction.BLOCK, "***");
        List<String> words = new ArrayList<>();
        config.getWords().forEach(word -> { if (word.isTextual() && !word.asText().isBlank()) words.add(word.asText()); });
        String regex = words.stream().distinct().sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote).collect(java.util.stream.Collectors.joining("|"));
        return new SensitiveWordPolicy(regex.isEmpty() ? null : Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
                config.getAction() == null ? SensitiveWordAction.BLOCK : config.getAction(),
                config.getReplacement() == null ? "***" : config.getReplacement());
    }

    public boolean active() { return pattern != null; }
    public SensitiveWordAction action() { return action; }
    public boolean matches(String text) { return active() && text != null && pattern.matcher(text).find(); }

    public String input(String text) {
        if (!matches(text)) return text;
        if (action == SensitiveWordAction.BLOCK) throw new IllegalStateException("输入内容涉及敏感词，已阻断本次运行");
        return action == SensitiveWordAction.REPLACE ? pattern.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(replacement)) : text;
    }

    /** 对流式片段对应的原文区间执行替换，跨分片的词条只替换一次。 */
    String replaceSlice(String fullText, int start, int end) {
        StringBuilder result = new StringBuilder();
        var matcher = pattern.matcher(fullText);
        int cursor = start;
        while (matcher.find()) {
            if (matcher.end() <= start) continue;
            if (matcher.start() >= end) break;
            if (matcher.start() > cursor) result.append(fullText, cursor, matcher.start());
            if (matcher.start() >= start) result.append(replacement);
            cursor = Math.max(cursor, Math.min(end, matcher.end()));
        }
        return result.append(fullText, cursor, end).toString();
    }
}
