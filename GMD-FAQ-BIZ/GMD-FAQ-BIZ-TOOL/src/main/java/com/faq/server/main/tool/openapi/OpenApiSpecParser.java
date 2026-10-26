package com.gmd.faq.server.main.tool.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * OpenAPI 文档解析器
 * <p>
 * 支持 OpenAPI 3.x（JSON / YAML）与 Swagger 2.0 的常用子集：
 * servers / basePath、paths、operationId、summary、description、tags、
 * parameters（path / query / header）、requestBody（application/json）、
 * 本地 $ref 与 allOf 合并。
 * <p>
 * 不支持的场景（远程 $ref、非 JSON 请求体、cookie 参数等）会被忽略，不影响其余接口的解析。
 *
 */
public final class OpenApiSpecParser {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /**
     * OpenAPI 文档支持的 HTTP 方法
     */
    private static final List<String> METHODS = List.of("get", "post", "put", "delete", "patch", "head", "options");

    private OpenApiSpecParser() {
    }

    /**
     * 解析结果
     */
    @Getter
    @Setter
    public static class Document {
        private String title;
        private String version;
        private String specVersion;
        private List<String> servers = new ArrayList<>();
        private String baseUrl;
        private List<OpenApiOperation> operations = new ArrayList<>();
    }

    /**
     * 解析 OpenAPI 文档文本（自动识别 JSON / YAML）
     *
     * @param spec 文档内容
     * @return 解析结果
     */
    public static Document parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("请提供 OpenAPI 文档内容");
        }
        JsonNode root;
        try {
            root = spec.trim().startsWith("{")
                    ? JSON_MAPPER.readTree(spec)
                    : YAML_MAPPER.readTree(spec);
        } catch (Exception e) {
            throw new IllegalArgumentException("OpenAPI 文档解析失败，请确认是合法的 JSON 或 YAML：" + e.getMessage());
        }
        if (root == null || root.isMissingNode()) {
            throw new IllegalArgumentException("OpenAPI 文档内容为空");
        }

        Document document = new Document();
        document.setTitle(root.path("info").path("title").asText("未命名接口文档"));
        document.setVersion(root.path("info").path("version").asText(""));

        String openapi = root.path("openapi").asText("");
        String swagger = root.path("swagger").asText("");
        if (!openapi.isBlank()) {
            document.setSpecVersion("OpenAPI " + openapi);
            collectServersV3(root, document);
        } else if (!swagger.isBlank()) {
            document.setSpecVersion("Swagger " + swagger);
            collectServersV2(root, document);
        } else {
            throw new IllegalArgumentException("无法识别的接口文档：缺少 openapi 或 swagger 版本字段");
        }

        JsonNode paths = root.path("paths");
        if (!paths.isObject() || paths.isEmpty()) {
            throw new IllegalArgumentException("接口文档中没有 paths 节点");
        }

        paths.fields().forEachRemaining(pathEntry -> {
            String path = pathEntry.getKey();
            JsonNode pathItem = resolveRef(root, pathEntry.getValue());
            for (String method : METHODS) {
                JsonNode operation = pathItem.path(method);
                if (operation.isMissingNode() || operation.isEmpty()) {
                    continue;
                }
                document.getOperations().add(parseOperation(root, path, method, operation));
            }
        });

        if (document.getOperations().isEmpty()) {
            throw new IllegalArgumentException("接口文档中没有可导入的接口操作");
        }
        return document;
    }

    /**
     * 解析单个接口操作
     */
    private static OpenApiOperation parseOperation(JsonNode root, String path, String method, JsonNode node) {
        OpenApiOperation operation = new OpenApiOperation();
        operation.setPath(path);
        operation.setMethod(method.toUpperCase());
        operation.setOperationId(node.path("operationId").asText(""));
        operation.setSummary(node.path("summary").asText(""));
        operation.setDescription(node.path("description").asText(""));
        JsonNode tags = node.path("tags");
        operation.setTag(tags.isArray() && !tags.isEmpty() ? tags.get(0).asText("") : "");

        // 参数：path / query / header
        JsonNode parameters = node.path("parameters");
        if (parameters.isArray()) {
            for (JsonNode item : parameters) {
                JsonNode parameter = resolveRef(root, item);
                String in = parameter.path("in").asText("");
                if (!"path".equals(in) && !"query".equals(in) && !"header".equals(in)) {
                    // cookie 与 body（Swagger 2.0 的 body 在下方单独处理）
                    if ("body".equals(in)) {
                        readBodySchema(root, parameter.path("schema"), operation);
                    }
                    continue;
                }
                JsonNode schema = parameter.has("schema") ? resolveRef(root, parameter.path("schema")) : parameter;
                OpenApiOperation.Param param = new OpenApiOperation.Param();
                param.setName(parameter.path("name").asText(""));
                param.setIn(in);
                param.setRequired(parameter.path("required").asBoolean(false) || "path".equals(in));
                param.setDescription(parameter.path("description").asText(""));
                param.setType(mapType(schema));
                param.setDefaultValue(schema.path("default").isMissingNode() ? null : schema.path("default").asText(null));
                if (!param.getName().isBlank()) {
                    operation.getParameters().add(param);
                }
            }
        }

        // OpenAPI 3.x 请求体
        JsonNode requestBody = resolveRef(root, node.path("requestBody"));
        if (requestBody != null && !requestBody.isMissingNode() && !requestBody.isEmpty()) {
            JsonNode content = requestBody.path("content");
            JsonNode jsonSchema = content.path("application/json").path("schema");
            operation.setHasBody(true);
            if (!jsonSchema.isMissingNode() && !jsonSchema.isEmpty()) {
                operation.setJsonBody(true);
                readBodySchema(root, jsonSchema, operation);
            }
        }

        return operation;
    }

    /**
     * 读取请求体 schema 的字段（含 allOf 合并）
     */
    private static void readBodySchema(JsonNode root, JsonNode schemaNode, OpenApiOperation operation) {
        JsonNode schema = resolveRef(root, schemaNode);
        if (schema == null || schema.isMissingNode() || schema.isEmpty()) {
            return;
        }
        operation.setHasBody(true);
        Set<String> requiredNames = new LinkedHashSet<>();
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            required.forEach(item -> requiredNames.add(item.asText("")));
        }
        for (JsonNode property : collectProperties(root, schema)) {
            String name = property.path("name").asText("");
            JsonNode propertySchema = property.path("schema");
            if (name.isBlank()) {
                continue;
            }
            OpenApiOperation.Param param = new OpenApiOperation.Param();
            param.setName(name);
            param.setIn("body");
            param.setRequired(requiredNames.contains(name));
            param.setDescription(propertySchema.path("description").asText(""));
            param.setType(mapType(propertySchema));
            operation.getParameters().add(param);
        }
    }

    /**
     * 收集 schema 的 properties（支持 allOf 递归合并）
     */
    private static List<JsonNode> collectProperties(JsonNode root, JsonNode schema) {
        List<JsonNode> result = new ArrayList<>();
        JsonNode allOf = schema.path("allOf");
        if (allOf.isArray()) {
            for (JsonNode item : allOf) {
                result.addAll(collectProperties(root, resolveRef(root, item)));
            }
        }
        JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            properties.fields().forEachRemaining(entry -> {
                com.fasterxml.jackson.databind.node.ObjectNode node =
                        JSON_MAPPER.createObjectNode();
                node.put("name", entry.getKey());
                node.set("schema", resolveRef(root, entry.getValue()));
                result.add(node);
            });
        }
        return result;
    }

    /**
     * 解析本地 $ref
     */
    private static JsonNode resolveRef(JsonNode root, JsonNode node) {
        JsonNode current = node;
        int depth = 0;
        while (current != null && current.has("$ref") && depth++ < 5) {
            String ref = current.path("$ref").asText("");
            if (!ref.startsWith("#/")) {
                // 远程引用不支持，原样返回
                return current;
            }
            JsonNode target = root;
            for (String segment : ref.substring(2).split("/")) {
                target = target.path(segment.replace("~1", "/").replace("~0", "~"));
            }
            current = target;
        }
        return current;
    }

    /**
     * OpenAPI 类型映射为工具参数类型
     */
    private static String mapType(JsonNode schema) {
        String type = schema == null ? "" : schema.path("type").asText("");
        return switch (type) {
            case "integer", "number" -> "number";
            case "boolean" -> "boolean";
            case "array" -> "array";
            case "object" -> "object";
            default -> "string";
        };
    }

    /**
     * 收集 OpenAPI 3.x 的 servers
     */
    private static void collectServersV3(JsonNode root, Document document) {
        JsonNode servers = root.path("servers");
        if (servers.isArray()) {
            for (JsonNode server : servers) {
                String url = server.path("url").asText("");
                if (url.isBlank()) {
                    continue;
                }
                // 服务变量按默认值替换
                JsonNode variables = server.path("variables");
                if (variables.isObject()) {
                    var fields = variables.fields();
                    while (fields.hasNext()) {
                        var entry = fields.next();
                        String defaultValue = entry.getValue().path("default").asText("");
                        url = url.replace("{" + entry.getKey() + "}", defaultValue);
                    }
                }
                document.getServers().add(url);
            }
        }
        if (!document.getServers().isEmpty()) {
            document.setBaseUrl(document.getServers().get(0));
        } else {
            document.setBaseUrl("");
        }
    }

    /**
     * 收集 Swagger 2.0 的服务地址（schemes + host + basePath）
     */
    private static void collectServersV2(JsonNode root, Document document) {
        String host = root.path("host").asText("");
        String basePath = root.path("basePath").asText("");
        JsonNode schemes = root.path("schemes");
        List<String> schemeList = new ArrayList<>();
        if (schemes.isArray()) {
            schemes.forEach(item -> schemeList.add(item.asText("")));
        }
        if (schemeList.isEmpty()) {
            schemeList.add("https");
        }
        if (!host.isBlank()) {
            for (String scheme : schemeList) {
                document.getServers().add(scheme + "://" + host + basePath);
            }
            document.setBaseUrl(document.getServers().get(0));
        } else {
            document.setBaseUrl(basePath);
        }
    }
}
