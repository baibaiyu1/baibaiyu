package com.gmd.faq.server.main.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import com.gmd.faq.server.main.common.entity.ModelConfig;
import com.gmd.faq.server.main.common.consts.LocalEmbeddingDimensions;
import com.gmd.faq.server.main.common.enums.KbType;
import com.gmd.faq.server.main.common.enums.ModelApiType;
import com.gmd.faq.server.main.common.enums.ModelType;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.wrapper.ModelConfigWrapper;
import com.gmd.faq.server.main.common.wrapper.ModelWrapper;
import com.gmd.faq.server.main.model.service.ModelConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 知识库只保存模型引用；凭证在执行或同步时从当前租户的模型管理解析。 */
@Service
@RequiredArgsConstructor
public class KnowledgeModelService {
    public static final List<String> MODEL_KEYS = List.of("llmModelId", "extractModelId", "keywordModelId",
            "queryModelId", "vlmModelId", "embeddingModelId", "rerankModelId");
    private final ModelConfigService models;
    private final ObjectMapper json;

    public record Option(String id, String name, String modelId, String providerName, List<String> usages,
                         Integer dimension) {}

    public List<Option> options() {
        List<Option> result = new ArrayList<>();
        for (ModelConfig config : models.list()) {
            if (!Boolean.TRUE.equals(config.getEnabled())) continue;
            ModelWrapper model;
            try { model = checked(config.getId()); }
            catch (IllegalArgumentException ex) { continue; }
            List<String> usages = new ArrayList<>();
            for (String usage : List.of("CHAT", "IMAGE", "EMBEDDING", "RERANK")) {
                try { validate(model, usage); usages.add(usage); }
                catch (IllegalArgumentException ignored) { /* 不展示不兼容模型。 */ }
            }
            if (!usages.isEmpty()) result.add(new Option(config.getId().toString(), config.getName(), config.getModelId(),
                    model.getProvider().getName(), usages, dimension(config)));
        }
        return result;
    }

    public void validateReferences(KnowledgeBaseConfig config) {
        if (config.getKbType() != KbType.LOCAL && config.getKbType() != KbType.LIGHTRAG) return;
        JsonNode connection = config.getConnectionConfig();
        if (connection == null || !connection.isObject()) throw new IllegalArgumentException("知识库连接配置不能为空");
        for (String key : MODEL_KEYS) {
            Long id = reference(connection, key);
            if (id == null) continue;
            ModelWrapper model = checked(id);
            validate(model, usage(key));
            if (key.equals("embeddingModelId") && dimension(model.getConfig()) == null)
                throw new IllegalArgumentException("请先在模型管理中设置向量模型的输出维度");
        }
        if (config.getKbType() == KbType.LIGHTRAG && MODEL_KEYS.stream().anyMatch(key -> reference(connection, key) != null)) {
            ObjectNode managed = connection.deepCopy();
            managed.put("platformModelsManaged", true);
            config.setConnectionConfig(managed);
        }
        if (config.getKbType() == KbType.LOCAL && reference(connection, "embeddingModelId") != null) {
            Integer dim = dimension(checked(reference(connection, "embeddingModelId")).getConfig());
            if (!LocalEmbeddingDimensions.SUPPORTED.contains(dim))
                throw new IllegalArgumentException("本地知识库不支持此向量维度，可选维度：" + LocalEmbeddingDimensions.SUPPORTED);
            ObjectNode clean = connection.deepCopy();
            clean.remove(List.of("apiKey", "envVarName", "baseUrl", "embeddingModel", "providerType"));
            clean.put("dimension", dimension(checked(reference(connection, "embeddingModelId")).getConfig()));
            clean.put("embeddingIdentity", embeddingIdentity(checked(reference(connection, "embeddingModelId"))));
            config.setConnectionConfig(clean);
        }
        if (config.getKbType() == KbType.LOCAL) {
            boundedInteger(config.getConnectionConfig(), "batchSize", 1, 100);
            boundedInteger(config.getConnectionConfig(), "bufferSizeMb", 1, 512);
        }
    }

    private static void boundedInteger(JsonNode config, String key, int min, int max) {
        if (!config.has(key)) return;
        JsonNode value = config.get(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max)
            throw new IllegalArgumentException(key + " 须为 " + min + " 到 " + max + " 之间的整数");
    }

    /** 本地向量化每次重新解析，停用模型或跨租户引用不能借旧配置继续执行。 */
    public JsonNode embeddingConnection(KnowledgeBaseConfig config) {
        JsonNode connection = config.getConnectionConfig();
        Long id = reference(connection, "embeddingModelId");
        if (id == null) return connection; // 兼容已有手工配置。
        ModelWrapper model = checked(id);
        validate(model, "EMBEDDING");
        Integer dim = dimension(model.getConfig());
        if (dim == null || dim != connection.path("dimension").asInt())
            throw new IllegalArgumentException("向量模型维度已变更，请重新构建知识库索引");
        if (!embeddingIdentity(model).equals(connection.path("embeddingIdentity").asText()))
            throw new IllegalArgumentException("向量模型身份已变更，请清空原文档后重新保存并构建索引");
        ObjectNode resolved = connection.deepCopy();
        boolean ollama = model.getProvider().getType().name().equals("OLLAMA");
        resolved.put("providerType", ollama ? "ollama" : "openai");
        resolved.put("baseUrl", host(model) + (ollama ? "/api/embed" : "/embeddings"));
        resolved.put("embeddingModel", model.getConfig().getModelId());
        resolved.put("apiKey", apiKey(model));
        resolved.put("sendDimensions", model.getConfig().getExtendConfig() != null
                && model.getConfig().getExtendConfig().path("embeddingSendDimensions").asBoolean(false));
        return resolved;
    }

    public KnowledgeBaseConfig embeddingProbeConfig(Long id) {
        ModelWrapper model = checked(id);
        validate(model, "EMBEDDING");
        KnowledgeBaseConfig config = new KnowledgeBaseConfig();
        config.setConnectionConfig(json.createObjectNode().put("embeddingModelId", id.toString())
                .put("dimension", dimension(model.getConfig())).put("embeddingIdentity", embeddingIdentity(model)));
        return config;
    }

    public ObjectNode rerankProbeSpec(Long id) { return spec(id, "RERANK"); }

    public ObjectNode lightRagPayload(KnowledgeBaseConfig config) {
        JsonNode connection = config.getConnectionConfig();
        if (MODEL_KEYS.stream().noneMatch(key -> reference(connection, key) != null)
                && (connection == null || !connection.path("platformModelsManaged").asBoolean())) return null;
        ObjectNode payload = json.createObjectNode();
        payload.put("owner", TenantUtils.getCurrentTenantId() + ":" + config.getId());
        ObjectNode roles = payload.putObject("roles");
        Long base = reference(connection, "llmModelId");
        for (String role : List.of("extract", "keyword", "query", "vlm")) {
            Long selected = reference(connection, role + "ModelId");
            if (selected == null && role.equals("vlm")) continue;
            if (selected == null) selected = base;
            if (selected != null) roles.set(role, spec(selected, role.equals("vlm") ? "IMAGE" : "CHAT"));
        }
        // 默认模型与各角色一并保存；实体、关系摘要沿用抽取角色。
        if (base != null) payload.set("llm", spec(base, "CHAT"));
        Long embedding = reference(connection, "embeddingModelId");
        if (embedding != null) payload.set("embedding", spec(embedding, "EMBEDDING"));
        Long rerank = reference(connection, "rerankModelId");
        if (rerank != null) payload.set("rerank", spec(rerank, "RERANK"));
        return payload;
    }

    private ObjectNode spec(Long id, String usage) {
        ModelWrapper model = checked(id);
        validate(model, usage);
        ObjectNode spec = json.createObjectNode();
        String provider = model.getProvider().getType().name();
        String binding = switch (provider) {
            case "OLLAMA" -> "ollama";
            case "GEMINI" -> "gemini";
            default -> "openai";
        };
        if (usage.equals("RERANK")) binding = provider.equals("DASH_SCOPE") ? "aliyun" : "cohere";
        spec.put("binding", binding);
        spec.put("model", model.getConfig().getModelId());
        String base = host(model);
        if (usage.equals("RERANK")) base = provider.equals("DASH_SCOPE")
                ? base.replace("/compatible-mode/v1", "/api/v1/services/rerank/text-rerank/text-rerank") : base + "/rerank";
        spec.put("host", base);
        spec.put("api_key", apiKey(model));
        if (usage.equals("EMBEDDING")) {
            Integer dim = dimension(model.getConfig());
            if (dim == null) throw new IllegalArgumentException("请先设置向量模型的输出维度");
            spec.put("dimension", dim);
            spec.put("send_dimensions", model.getConfig().getExtendConfig().path("embeddingSendDimensions").asBoolean(false));
        }
        if (Set.of("CHAT", "IMAGE").contains(usage)) {
            ObjectNode options = spec.putObject("provider_options");
            if (model.getConfig().getTemperature() != null) options.put("temperature", model.getConfig().getTemperature());
            if (model.getConfig().getTopP() != null) options.put("top_p", model.getConfig().getTopP());
            if (model.getConfig().getMaxTokens() != null) options.put(binding.equals("ollama") ? "num_predict" : binding.equals("gemini") ? "max_output_tokens" : "max_tokens", model.getConfig().getMaxTokens());
            ModelConfigWrapper resolved = new ModelConfigWrapper();
            model.getConfig().fillModelConfigWrapper(resolved);
            resolved.setProvider(model.getProvider().getType());
            resolved.setBaseUrl(host(model));
            com.gmd.faq.server.main.common.model.ReasoningConfigSupport.apply(resolved);
            if (resolved.getBodyParams() != null && !resolved.getBodyParams().isEmpty()) {
                if (binding.equals("openai")) options.set("extra_body", json.valueToTree(resolved.getBodyParams()));
                else options.setAll((ObjectNode) json.valueToTree(resolved.getBodyParams()));
            }
            if (binding.equals("openai")) {
                if (resolved.getHeaders() != null && !resolved.getHeaders().isEmpty()) options.set("extra_headers", json.valueToTree(resolved.getHeaders()));
                if (resolved.getQueryParams() != null && !resolved.getQueryParams().isEmpty()) options.set("extra_query", json.valueToTree(resolved.getQueryParams()));
            }
            if (binding.equals("gemini") && resolved.getReasoningBudget() != null)
                options.putObject("thinking_config").put("thinking_budget", resolved.getReasoningBudget());
        }
        return spec;
    }

    private ModelWrapper checked(Long id) {
        ModelConfig config = models.getById(id);
        if (TenantUtils.getCurrentTenantId() == null || config == null || !Objects.equals(config.getTenantId(), TenantUtils.getCurrentTenantId()) || !Boolean.TRUE.equals(config.getEnabled()))
            throw new IllegalArgumentException("模型不存在、已停用或不属于当前租户");
        ModelWrapper model;
        try { model = models.getModelWrapperById(id); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("模型供应商不存在或已停用"); }
        if (!Objects.equals(model.getProvider().getTenantId(), TenantUtils.getCurrentTenantId()))
            throw new IllegalArgumentException("模型供应商不属于当前租户");
        return model;
    }

    private void validate(ModelWrapper model, String usage) {
        JsonNode types = model.getConfig().getModelType();
        boolean matches = false;
        if (types != null && types.isArray()) for (JsonNode type : types) if (usage.equals(type.asText())) matches = true;
        if (!matches)
            throw new IllegalArgumentException("所选模型不支持" + ModelType.valueOf(usage).getDescription() + "用途");
        String provider = model.getProvider().getType().name();
        Set<String> supported = switch (usage) {
            case "EMBEDDING" -> Set.of("OPEN_AI", "DASH_SCOPE", "OLLAMA", "ORCA_ROUTER");
            case "RERANK" -> Set.of("OPEN_AI", "DASH_SCOPE", "ORCA_ROUTER");
            default -> Set.of("OPEN_AI", "DASH_SCOPE", "OLLAMA", "ORCA_ROUTER", "GEMINI");
        };
        if (!supported.contains(provider) || model.getConfig().getApiType() == ModelApiType.RESPONSES)
            throw new IllegalArgumentException("该知识库用途暂不支持所选供应商或 Responses 协议，请选择兼容模型");
    }

    private static String usage(String key) {
        return switch (key) { case "embeddingModelId" -> "EMBEDDING"; case "rerankModelId" -> "RERANK";
            case "vlmModelId" -> "IMAGE"; default -> "CHAT"; };
    }

    public static Long reference(JsonNode config, String key) {
        if (config == null || config.path(key).isMissingNode() || config.path(key).isNull() || config.path(key).asText().isBlank()) return null;
        try {
            long id = Long.parseLong(config.path(key).asText());
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException ex) { throw new IllegalArgumentException("模型引用格式无效：" + key); }
    }

    private static Integer dimension(ModelConfig config) {
        JsonNode value = config.getExtendConfig() == null ? null : config.getExtendConfig().get("embeddingDimension");
        return value != null && value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0 && value.intValue() <= 65536
                ? value.intValue() : null;
    }

    private static String embeddingIdentity(ModelWrapper model) {
        return model.getProvider().getId() + ":" + model.getProvider().getType() + ":" + model.getConfig().getModelId()
                + ":" + dimension(model.getConfig()) + ":" + host(model);
    }

    private static String apiKey(ModelWrapper model) {
        if (model.getProvider().getType().name().equals("OLLAMA") && (model.getProvider().getApiKey() == null || model.getProvider().getApiKey().isBlank())
                && model.getProvider().getEnvVarName() == null) return "";
        ModelConfigWrapper credentials = new ModelConfigWrapper();
        model.getProvider().fillModelConfigWrapper(credentials);
        return credentials.getApiKey() == null ? "" : credentials.getApiKey();
    }

    private static String host(ModelWrapper model) {
        String provider = model.getProvider().getType().name();
        String base = model.getProvider().getBaseUrl();
        if (base == null || base.isBlank()) base = switch (provider) {
            case "DASH_SCOPE" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "OLLAMA" -> "http://localhost:11434";
            case "GEMINI" -> "https://generativelanguage.googleapis.com";
            default -> "https://api.openai.com/v1";
        };
        base = base.trim().replaceAll("/+$", "").replaceAll("/(chat/completions|embeddings|api/embed|api/chat|rerank)$", "");
        URI uri = URI.create(base);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("模型供应商服务地址无效");
        if (provider.equals("DASH_SCOPE") && (uri.getPath().isBlank() || uri.getPath().equals("/"))) base += "/compatible-mode/v1";
        else if ((provider.equals("OPEN_AI") || provider.equals("ORCA_ROUTER")) && uri.getPath().isBlank()) base += "/v1";
        return base;
    }
}
