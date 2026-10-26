package com.gmd.faq.server.main.common.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gmd.faq.server.main.common.entity.ModelConfig;
import com.gmd.faq.server.main.common.entity.ModelProvider;
import com.gmd.faq.server.main.common.enums.ModelApiType;
import com.gmd.faq.server.main.common.enums.ModelProviderType;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.common.wrapper.ModelConfigWrapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 推理设置的兼容读取、能力校验和协议映射共用入口。新配置不允许原始请求参数绕过。 */
public final class ReasoningConfigSupport {
    private static final Set<String> CONTROLLED_KEYS = Set.of("reasoning", "reasoning.effort", "reasoning.summary",
            "reasoning.mode", "reasoning_effort", "thinking", "thinking.type", "thinking.budget_tokens",
            "enable_thinking", "thinking_budget", "thinkingBudget", "thinkingConfig", "think");
    private static final Set<String> PROFILES = Set.of("AUTO", "OPENAI", "DEEPSEEK", "QWEN", "ANTHROPIC", "GEMINI", "OLLAMA", "NONE");
    private ReasoningConfigSupport() {}

    public static ReasoningCapabilities capabilities(ModelProviderType provider, String baseUrl, String model,
            ModelApiType apiType, String selectedProfile) {
        String profile = selectedProfile == null ? "AUTO" : selectedProfile;
        if (!PROFILES.contains(profile)) throw new IllegalArgumentException("推理兼容类型无效");
        String name = model == null ? "" : model.toLowerCase(Locale.ROOT);
        String url = baseUrl == null ? "" : baseUrl.toLowerCase(Locale.ROOT);
        if ("AUTO".equals(profile)) {
            profile = switch (provider == null ? ModelProviderType.OPEN_AI : provider) {
                case DASH_SCOPE -> "QWEN";
                case ANTHROPIC -> "ANTHROPIC";
                case GEMINI -> "GEMINI";
                case OLLAMA -> "OLLAMA";
                default -> name.contains("deepseek") || url.contains("api.deepseek.com") ? "DEEPSEEK"
                        : name.startsWith("qwen") ? "QWEN"
                        : name.matches("(o[1-9].*|gpt-5.*|gpt-6.*)") ? "OPENAI" : "NONE";
            };
        }
        if (provider != null && provider != ModelProviderType.OPEN_AI && provider != ModelProviderType.ORCA_ROUTER) {
            String required = switch (provider) {
                case DASH_SCOPE -> "QWEN";
                case ANTHROPIC -> "ANTHROPIC";
                case GEMINI -> "GEMINI";
                case OLLAMA -> "OLLAMA";
                default -> "NONE";
            };
            if (!required.equals(profile)) throw new IllegalArgumentException("推理兼容类型与供应商不匹配");
        }
        if (apiType == ModelApiType.RESPONSES && !Set.of("OPENAI", "DEEPSEEK", "NONE").contains(profile))
            throw new IllegalArgumentException("此推理兼容类型不支持 Responses 协议");
        return switch (profile) {
            case "DEEPSEEK" -> {
                boolean fixed = name.contains("reasoner") || name.matches(".*deepseek[-/]r1.*");
                yield new ReasoningCapabilities(profile, true, !fixed, fixed ? List.of() : List.of("low", "high", "max"),
                        null, null, false, fixed ? "固定推理模型不能关闭推理；如需关闭请选择支持切换的模型。" : "最高档为 max；xhigh 在 DeepSeek 中仅等同于 high。");
            }
            case "OPENAI" -> {
                boolean old = name.startsWith("o1") || name.startsWith("o3") || name.startsWith("o4")
                        || name.matches("gpt-5(-[0-9]{4}.*)?") || name.startsWith("gpt-5-mini") || name.startsWith("gpt-5-nano");
                List<String> efforts = name.startsWith("gpt-5") && old ? List.of("minimal", "low", "medium", "high")
                        : name.matches("gpt-5\\.[2-9].*|gpt-6.*") ? List.of("low", "medium", "high", "xhigh")
                        : List.of("low", "medium", "high");
                yield new ReasoningCapabilities(profile, true, !old, efforts, null, null, true,
                        old ? "此模型不能使用 none 关闭推理。" : "按模型支持范围选择强度；展示内容为供应商返回的推理摘要。");
            }
            case "QWEN" -> new ReasoningCapabilities(profile, true, !name.startsWith("qvq") && !name.contains("thinking"),
                    List.of(), 1, 131072, false, "使用 enable_thinking 控制推理，预算不得超过最大输出 Token。");
            case "ANTHROPIC" -> new ReasoningCapabilities(profile, true, true, List.of(), 1024, 128000, false,
                    "预算使用 budget_tokens，必须小于最大输出 Token；开启时默认 1024。");
            case "GEMINI" -> new ReasoningCapabilities(profile, true, !name.contains("2.5-pro"), List.of(), 128, 32768, false,
                    "使用 thinkingBudget；预算不得超过最大输出 Token。");
            case "OLLAMA" -> new ReasoningCapabilities(profile, true, true, List.of(), null, null, false,
                    "使用 think 开关；具体模型必须支持思考模式。");
            default -> new ReasoningCapabilities("NONE", false, false, List.of(), null, null, false,
                    "尚未识别推理能力。代理服务可选择实际兼容类型；普通模型使用模型默认即可。");
        };
    }

    public static ReasoningCapabilities capabilities(ModelConfig config, ModelProvider provider) {
        return capabilities(provider.getType(), provider.getBaseUrl(), config.getModelId(), config.getApiType(),
                profile(config.getExtendConfig()));
    }

    public static String profile(JsonNode extend) {
        return extend == null ? "AUTO" : extend.path("reasoningProfile").asText("AUTO");
    }

    public static ReasoningSettings parse(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isObject()) throw new IllegalArgumentException("推理设置必须是对象");
        node.fieldNames().forEachRemaining(key -> {
            if (!Set.of("mode", "effort", "budgetTokens", "showThinking").contains(key))
                throw new IllegalArgumentException("不支持的推理设置：" + key);
        });
        String mode = node.path("mode").asText("DEFAULT");
        if (node.has("mode") && !node.get("mode").isTextual()) throw new IllegalArgumentException("推理模式必须是字符串");
        if (!Set.of("DEFAULT", "ENABLED", "DISABLED").contains(mode)) throw new IllegalArgumentException("推理模式无效");
        if (node.hasNonNull("effort") && !node.get("effort").isTextual()) throw new IllegalArgumentException("推理强度必须是字符串");
        String effort = node.path("effort").isNull() || !node.has("effort") ? null : node.path("effort").asText();
        if (effort != null && effort.isBlank()) effort = null;
        Integer budget = null;
        if (node.hasNonNull("budgetTokens")) {
            if (!node.get("budgetTokens").isIntegralNumber() || !node.get("budgetTokens").canConvertToInt())
                throw new IllegalArgumentException("推理预算必须是整数");
            budget = node.get("budgetTokens").asInt();
        }
        if (node.has("showThinking") && !node.get("showThinking").isBoolean())
            throw new IllegalArgumentException("展示思考内容必须是布尔值");
        if (!"ENABLED".equals(mode) && (effort != null || budget != null))
            throw new IllegalArgumentException("仅开启推理时可以设置强度或预算");
        return new ReasoningSettings(mode, effort, budget, node.path("showThinking").asBoolean(true));
    }

    public static ReasoningSettings modelSettings(ModelConfig config, ModelProviderType provider) {
        JsonNode extend = config.getExtendConfig();
        ReasoningSettings structured = parse(extend == null ? null : extend.get("reasoning"));
        ReasoningSettings settings = structured != null ? structured : legacy(extend == null ? null : extend.get("bodyParams"),
                provider == ModelProviderType.DASH_SCOPE ? config.getThinking() : null);
        if (structured == null && config.getModelId() != null && config.getModelId().toLowerCase(Locale.ROOT).contains("deepseek"))
            settings = deepSeekAliases(settings);
        if (structured == null && Boolean.TRUE.equals(config.getThinking()) && settings.budgetTokens() == null
                && !"DISABLED".equals(settings.mode())
                && (provider == ModelProviderType.DASH_SCOPE || provider == ModelProviderType.GEMINI || provider == ModelProviderType.OLLAMA)) {
            settings = new ReasoningSettings("ENABLED", settings.effort(), provider == ModelProviderType.OLLAMA ? null : 5000, true);
        }
        return settings;
    }

    public static ReasoningSettings effective(ModelConfig config, ModelProvider provider, JsonNode overrides) {
        if (overrides != null && overrides.hasNonNull("reasoning")) return parse(overrides.get("reasoning"));
        JsonNode body = overrides == null ? null : overrides.path("extendConfig").get("bodyParams");
        if (hasLegacy(body)) {
            var settings = legacy(body, null);
            return "DEEPSEEK".equals(capabilities(config, provider).profile()) ? deepSeekAliases(settings) : settings;
        }
        return modelSettings(config, provider);
    }

    public static ReasoningSettings modelSettings(ModelConfig config, ModelProvider provider) {
        var settings = modelSettings(config, provider.getType());
        return (config.getExtendConfig() == null || !config.getExtendConfig().hasNonNull("reasoning"))
                && "DEEPSEEK".equals(capabilities(config, provider).profile()) ? deepSeekAliases(settings) : settings;
    }

    /** 旧智能体原始推理参数视为独立配置；只转换返回副本，不在读取时修改存储。 */
    public static JsonNode normalizeOverride(ModelConfig config, ModelProvider provider, JsonNode overrides) {
        if (overrides == null || !overrides.isObject() || overrides.hasNonNull("reasoning")) return overrides;
        JsonNode extend = overrides.get("extendConfig");
        if (extend == null || !hasLegacy(extend.get("bodyParams"))) return overrides;
        ObjectNode copy = overrides.deepCopy();
        copy.set("reasoning", toNode(effective(config, provider, overrides)));
        copy.set("extendConfig", withoutLegacy(extend));
        return copy;
    }

    private static ReasoningSettings deepSeekAliases(ReasoningSettings settings) {
        String effort = settings.effort();
        if ("minimal".equals(effort)) effort = "low";
        if ("medium".equals(effort) || "xhigh".equals(effort)) effort = "high";
        return new ReasoningSettings(settings.mode(), effort, settings.budgetTokens(), settings.showThinking());
    }

    public static ReasoningSettings legacy(JsonNode body, Boolean thinking) {
        String effort = text(body, "reasoning.effort", "reasoning_effort");
        if (effort == null && body != null && body.path("reasoning").isObject()) effort = text(body.get("reasoning"), "effort");
        String mode = effort == null ? "DEFAULT" : "none".equals(effort) ? "DISABLED" : "ENABLED";
        JsonNode enabled = body == null ? null : body.get("enable_thinking");
        JsonNode nativeThinking = body == null ? null : body.get("thinking");
        if (nativeThinking != null && nativeThinking.isTextual()) {
            try { nativeThinking = JsonUtils.parse(nativeThinking.asText(), JsonNode.class); } catch (RuntimeException ignored) { }
        }
        String type = nativeThinking == null ? null : text(nativeThinking, "type");
        if (type == null) type = text(body, "thinking.type");
        if (type != null) mode = "disabled".equals(type) ? "DISABLED" : "ENABLED";
        else if (enabled != null && enabled.isBoolean()) mode = enabled.asBoolean() ? "ENABLED" : "DISABLED";
        else if (thinking != null && effort == null) mode = thinking ? "ENABLED" : "DISABLED";
        Integer budget = null;
        if (body != null && body.path("thinking_budget").isIntegralNumber()) budget = body.path("thinking_budget").asInt();
        if (nativeThinking != null && nativeThinking.path("budget_tokens").isIntegralNumber()) budget = nativeThinking.path("budget_tokens").asInt();
        if (body != null && body.path("thinking.budget_tokens").isIntegralNumber()) budget = body.path("thinking.budget_tokens").asInt();
        if (body != null && body.path("thinkingBudget").isIntegralNumber()) budget = body.path("thinkingBudget").asInt();
        if (body != null && body.path("thinkingConfig").path("thinkingBudget").isIntegralNumber())
            budget = body.path("thinkingConfig").path("thinkingBudget").asInt();
        if (budget != null && "DEFAULT".equals(mode)) mode = budget == 0 ? "DISABLED" : "ENABLED";
        if (Integer.valueOf(-1).equals(budget)) budget = null;
        if (body != null && body.path("think").isBoolean()) mode = body.path("think").asBoolean() ? "ENABLED" : "DISABLED";
        return new ReasoningSettings(mode, "ENABLED".equals(mode) ? effort : null, "ENABLED".equals(mode) ? budget : null, true);
    }

    private static String text(JsonNode node, String... keys) {
        if (node != null) for (String key : keys) if (node.hasNonNull(key)) return node.get(key).asText();
        return null;
    }

    public static boolean hasLegacy(JsonNode body) {
        if (body == null || !body.isObject()) return false;
        return CONTROLLED_KEYS.stream().anyMatch(body::has);
    }

    public static void rejectConflicts(JsonNode extend, JsonNode structured) {
        if (structured == null || structured.isNull() || extend == null) return;
        if (hasLegacy(extend.get("bodyParams")) || hasLegacy(extend.get("queryParams")))
            throw new IllegalArgumentException("推理参数已由推理设置管理，请删除扩展配置中的重复推理参数");
    }

    public static void validate(ReasoningSettings settings, ReasoningCapabilities caps, Integer maxTokens) {
        if (settings == null) return;
        if ("ENABLED".equals(settings.mode()) && !caps.canEnable()) throw new IllegalArgumentException("当前模型未声明可控制的推理能力");
        if ("DISABLED".equals(settings.mode()) && !caps.canDisable()) throw new IllegalArgumentException("当前模型不支持关闭推理");
        if (settings.effort() != null && !caps.efforts().contains(settings.effort()))
            throw new IllegalArgumentException("当前模型不支持推理强度：" + settings.effort());
        Integer budget = settings.budgetTokens();
        if (budget != null && (caps.minBudget() == null || budget < caps.minBudget() || budget > caps.maxBudget()))
            throw new IllegalArgumentException("当前模型的推理预算超出支持范围");
        int effectiveBudget = budget != null ? budget : "ANTHROPIC".equals(caps.profile()) && "ENABLED".equals(settings.mode()) ? 1024 : 0;
        if (maxTokens != null && effectiveBudget > 0 && (effectiveBudget > maxTokens
                || "ANTHROPIC".equals(caps.profile()) && effectiveBudget == maxTokens))
            throw new IllegalArgumentException("推理预算必须小于或符合模型最大输出 Token 限制");
    }

    public static void apply(ModelConfigWrapper config) {
        if (config.getReasoning() == null) return; // 旧配置按实际参数继续执行，禁用的旧 thinking=false 不能变成关闭指令。
        ReasoningSettings settings = parse(config.getReasoning());
        var caps = capabilities(config.getProvider(), config.getBaseUrl(), config.getModelCode(), config.getApiType(), config.getReasoningProfile());
        validate(settings, caps, config.getMaxTokens());
        config.setResolvedReasoning(settings);
        config.setReasoningProfile(caps.profile());
        Map<String, Object> body = new LinkedHashMap<>(config.getBodyParams() == null ? Map.of() : config.getBodyParams());
        CONTROLLED_KEYS.forEach(body::remove);
        config.setThinking(null);
        config.setReasoningBudget(null);
        if (!"DEFAULT".equals(settings.mode())) {
            boolean enabled = "ENABLED".equals(settings.mode());
            switch (caps.profile()) {
                case "OPENAI", "DEEPSEEK" -> {
                    String effort = !enabled ? "none" : settings.effort() == null ? "high" : settings.effort();
                    if (effort != null && !caps.efforts().isEmpty())
                        body.put(config.getApiType() == ModelApiType.RESPONSES ? "reasoning.effort" : "reasoning_effort", effort);
                    if ("DEEPSEEK".equals(caps.profile()) && config.getApiType() != ModelApiType.RESPONSES
                            && caps.canDisable()) body.put("thinking", Map.of("type", enabled ? "enabled" : "disabled"));
                }
                case "QWEN" -> {
                    config.setThinking(enabled);
                    body.put("enable_thinking", enabled);
                    if (enabled && settings.budgetTokens() != null) body.put("thinking_budget", settings.budgetTokens());
                }
                case "ANTHROPIC" -> {
                    if (enabled) body.put("thinking", Map.of("type", "enabled", "budget_tokens", settings.budgetTokens() == null ? 1024 : settings.budgetTokens()));
                    else body.put("thinking", Map.of("type", "disabled"));
                }
                case "GEMINI" -> config.setReasoningBudget(enabled ? settings.budgetTokens() == null ? -1 : settings.budgetTokens() : 0);
                case "OLLAMA" -> body.put("think", enabled);
                default -> { }
            }
        }
        if (config.getApiType() == ModelApiType.RESPONSES && caps.summary() && settings.showThinking() && !"DISABLED".equals(settings.mode()))
            body.put("reasoning.summary", "auto");
        config.setBodyParams(body);
    }

    public static JsonNode toNode(ReasoningSettings settings) {
        return JsonUtils.toJsonNode(settings);
    }

    /** 仅清理被接管的推理字段，其他扩展字段完整保留。 */
    public static ObjectNode withoutLegacy(JsonNode extend) {
        ObjectNode result = extend != null && extend.isObject() ? (ObjectNode) extend.deepCopy()
                : (ObjectNode) JsonUtils.parse("{}", JsonNode.class);
        if (result.get("bodyParams") instanceof ObjectNode body) CONTROLLED_KEYS.forEach(body::remove);
        if (result.get("queryParams") instanceof ObjectNode query) CONTROLLED_KEYS.forEach(query::remove);
        return result;
    }
}
