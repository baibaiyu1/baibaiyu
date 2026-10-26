package com.gmd.faq.server.main.tool.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gmd.faq.server.main.common.enums.CodeLanguage;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI 操作 → 自定义工具（Groovy 动态工具）生成器
 * <p>
 * 生成的工具形态与手工创建的「自定义工具」完全一致：
 * {@code toolType=CUSTOM}、{@code language=JAVA}（由 Groovy 实例加载器处理）、
 * {@code inputSchema} 为参数数组、{@code code} 为 Groovy 源码。
 * <p>
 * 生成的 Groovy 类实现 {@code IDynamicAgentTool}，运行时按参数模板拼装 URL、
 * 附加固定/动态请求头与 JSON 请求体，通过 hutool-http 发起调用并返回响应文本。
 *
 */
public final class OpenApiGroovyGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_TOOL_ID_LENGTH = 64;
    private static final int MAX_RESPONSE_CHARS = 20000;

    private OpenApiGroovyGenerator() {
    }

    /**
     * 生成结果
     */
    @Getter
    @Setter
    public static class GeneratedTool {
        private String toolId;
        private String name;
        private String description;
        private String inputSchema;
        private String code;
        private CodeLanguage language = CodeLanguage.JAVA;
    }

    /**
     * 生成工具
     *
     * @param operation      解析出的接口操作
     * @param baseUrl        基地址（末尾不带 /）
     * @param authHeaderName 统一附加的鉴权请求头名称，可为空
     * @param authHeaderValue 统一附加的鉴权请求头取值，可为空
     * @return 生成结果
     */
    public static GeneratedTool generate(OpenApiOperation operation,
                                         String baseUrl,
                                         String authHeaderName,
                                         String authHeaderValue) {
        GeneratedTool tool = new GeneratedTool();
        tool.setToolId(buildToolId(operation));
        tool.setName(buildName(operation));
        tool.setDescription(buildDescription(operation));
        tool.setInputSchema(buildInputSchema(operation));
        tool.setCode(buildGroovyCode(operation, baseUrl, authHeaderName, authHeaderValue));
        return tool;
    }

    /**
     * 生成工具编号：优先取 operationId，缺失时用「方法_路径」兜底
     */
    public static String buildToolId(OpenApiOperation operation) {
        String source = operation.getOperationId();
        if (source == null || source.isBlank()) {
            source = operation.getMethod().toLowerCase() + "_" + operation.getPath();
        }
        String toolId = source.replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("^_+", "").replaceAll("_+$", "");
        if (toolId.isBlank()) {
            toolId = "openapi_tool";
        }
        if (Character.isDigit(toolId.charAt(0))) {
            toolId = "t_" + toolId;
        }
        return toolId.length() > MAX_TOOL_ID_LENGTH ? toolId.substring(0, MAX_TOOL_ID_LENGTH) : toolId;
    }

    /**
     * 工具编号去重后缀（同一文档内出现重复编号时使用）
     */
    public static String withSuffix(String toolId, int index) {
        String suffix = "_" + index;
        int maxBase = MAX_TOOL_ID_LENGTH - suffix.length();
        String base = toolId.length() > maxBase ? toolId.substring(0, maxBase) : toolId;
        return base + suffix;
    }

    private static String buildName(OpenApiOperation operation) {
        String name = operation.getSummary();
        if (name == null || name.isBlank()) {
            name = operation.getOperationId();
        }
        if (name == null || name.isBlank()) {
            name = operation.getMethod() + " " + operation.getPath();
        }
        name = name.replaceAll("\\s+", " ").trim();
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    private static String buildDescription(OpenApiOperation operation) {
        StringBuilder builder = new StringBuilder();
        builder.append(operation.getMethod()).append(" ").append(operation.getPath());
        if (operation.getSummary() != null && !operation.getSummary().isBlank()) {
            builder.append(" —— ").append(operation.getSummary().trim());
        }
        if (operation.getDescription() != null && !operation.getDescription().isBlank()) {
            String description = operation.getDescription().replaceAll("\\s+", " ").trim();
            builder.append("。").append(description);
        }
        String result = builder.toString();
        return result.length() > 500 ? result.substring(0, 500) : result;
    }

    /**
     * 生成工具参数 Schema（与手工创建自定义工具的结构一致）
     */
    private static String buildInputSchema(OpenApiOperation operation) {
        ArrayNode array = MAPPER.createArrayNode();
        for (OpenApiOperation.Param param : operation.getParameters()) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("name", param.getName());
            node.put("type", param.getType() == null ? "string" : param.getType());
            node.put("description", param.getDescription() == null ? "" : param.getDescription());
            node.put("defaultValue", param.getDefaultValue());
            node.put("required", param.isRequired());
            array.add(node);
        }
        return array.toString();
    }

    /**
     * 生成 Groovy 源码
     */
    private static String buildGroovyCode(OpenApiOperation operation,
                                          String baseUrl,
                                          String authHeaderName,
                                          String authHeaderValue) {
        String url = joinUrl(baseUrl, operation.getPath());
        List<String> pathParams = new ArrayList<>();
        List<String> queryParams = new ArrayList<>();
        List<String> headerParams = new ArrayList<>();
        List<String> bodyParams = new ArrayList<>();
        for (OpenApiOperation.Param param : operation.getParameters()) {
            switch (param.getIn()) {
                case "path" -> pathParams.add(param.getName());
                case "query" -> queryParams.add(param.getName());
                case "header" -> headerParams.add(param.getName());
                case "body" -> bodyParams.add(param.getName());
                default -> { }
            }
        }

        Map<String, String> fixedHeaders = new LinkedHashMap<>();
        if (authHeaderName != null && !authHeaderName.isBlank() && authHeaderValue != null && !authHeaderValue.isBlank()) {
            fixedHeaders.put(authHeaderName.trim(), authHeaderValue.trim());
        }

        String className = "OpenApiTool_" + buildToolId(operation);
        StringBuilder code = new StringBuilder();
        code.append("import com.gmd.faq.server.main.engine.tool.dynamices.IDynamicAgentTool\n");
        code.append("import com.gmd.faq.server.main.engine.agui.AgentContext\n\n");
        code.append("/**\n");
        code.append(" * 由 OpenAPI 导入自动生成：").append(operation.getMethod()).append(" ").append(operation.getPath()).append("\n");
        code.append(" * 生成时间：").append(LocalDateTime.now().format(TIME_FORMATTER)).append("\n");
        code.append(" * 提示：手工修改会在重新导入时被覆盖\n");
        code.append(" */\n");
        code.append("class ").append(className).append(" implements IDynamicAgentTool {\n\n");
        code.append("    private static final String METHOD = \"").append(operation.getMethod()).append("\"\n");
        code.append("    private static final String URL_TEMPLATE = \"").append(escape(url)).append("\"\n");
        code.append("    private static final List<String> PATH_PARAMS = ").append(toGroovyList(pathParams)).append("\n");
        code.append("    private static final List<String> QUERY_PARAMS = ").append(toGroovyList(queryParams)).append("\n");
        code.append("    private static final List<String> HEADER_PARAMS = ").append(toGroovyList(headerParams)).append("\n");
        code.append("    private static final List<String> BODY_PARAMS = ").append(toGroovyList(bodyParams)).append("\n");
        code.append("    private static final Map<String, String> FIXED_HEADERS = ").append(toGroovyMap(fixedHeaders)).append("\n");
        code.append("    private static final int TIMEOUT_MS = 30000\n");
        code.append("    private static final int MAX_BODY_CHARS = ").append(MAX_RESPONSE_CHARS).append("\n\n");
        code.append("    @Override\n");
        code.append("    Object execute(AgentContext agentContext, Map<String, Object> params) {\n");
        code.append("        // 统一兜底：网络/解析等异常转成可读文本，避免调用方拿到 500\n");
        code.append("        try {\n");
        code.append("        String url = URL_TEMPLATE\n");
        code.append("        for (String name : PATH_PARAMS) {\n");
        code.append("            Object value = params.get(name)\n");
        code.append("            url = url.replace(\"{\" + name + \"}\", value == null ? \"\" : String.valueOf(value))\n");
        code.append("        }\n\n");
        code.append("        List<String> queries = []\n");
        code.append("        for (String name : QUERY_PARAMS) {\n");
        code.append("            Object value = params.get(name)\n");
        code.append("            if (value != null && String.valueOf(value).length() > 0) {\n");
        code.append("                queries.add(name + \"=\" + java.net.URLEncoder.encode(String.valueOf(value), \"UTF-8\"))\n");
        code.append("            }\n");
        code.append("        }\n");
        code.append("        if (!queries.isEmpty()) {\n");
        code.append("            url = url + (url.contains(\"?\") ? \"&\" : \"?\") + queries.join(\"&\")\n");
        code.append("        }\n\n");
        code.append("        def request = cn.hutool.http.HttpRequest.of(url)\n");
        code.append("        request.method(cn.hutool.http.Method.valueOf(METHOD))\n");
        code.append("        request.timeout(TIMEOUT_MS)\n");
        code.append("        for (Map.Entry<String, String> entry : FIXED_HEADERS.entrySet()) {\n");
        code.append("            request.header(entry.getKey(), entry.getValue())\n");
        code.append("        }\n");
        code.append("        for (String name : HEADER_PARAMS) {\n");
        code.append("            Object value = params.get(name)\n");
        code.append("            if (value != null) {\n");
        code.append("                request.header(name, String.valueOf(value))\n");
        code.append("            }\n");
        code.append("        }\n");
        code.append("        if (!BODY_PARAMS.isEmpty()) {\n");
        code.append("            Map<String, Object> body = [:]\n");
        code.append("            for (String name : BODY_PARAMS) {\n");
        code.append("                if (params.containsKey(name)) {\n");
        code.append("                    body.put(name, params.get(name))\n");
        code.append("                }\n");
        code.append("            }\n");
        code.append("            request.header(\"Content-Type\", \"application/json\")\n");
        code.append("            request.body(toJsonString(body))\n");
        code.append("        }\n\n");
        code.append("        def response = request.execute()\n");
        code.append("        String text = response.body()\n");
        code.append("        if (text == null) {\n");
        code.append("            text = \"\"\n");
        code.append("        }\n");
        code.append("        if (text.length() > MAX_BODY_CHARS) {\n");
        code.append("            text = text.substring(0, MAX_BODY_CHARS) + \"...(响应内容过长已截断)\"\n");
        code.append("        }\n");
        code.append("        if (response.getStatus() >= 400) {\n");
        code.append("            return \"HTTP \" + response.getStatus() + \" 调用失败：\" + text\n");
        code.append("        }\n");
        code.append("        return text\n");
        code.append("        } catch (Exception e) {\n");
        code.append("            return \"接口调用失败：\" + e.getMessage()\n");
        code.append("        }\n");
        code.append("    }\n");
        code.append("}\n");
        return code.toString();
    }

    /**
     * 拼接基地址与路径
     */
    public static String joinUrl(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String suffix = path == null ? "" : path.trim();
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        return base + suffix;
    }

    /**
     * 转义 Groovy 双引号字符串
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private static String toGroovyList(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append("\"").append(escape(values.get(i))).append("\"");
        }
        return builder.append("]").toString();
    }

    private static String toGroovyMap(Map<String, String> values) {
        // 注意：Groovy 中 [] 是空 List，空 Map 必须写成 [:]
        if (values.isEmpty()) {
            return "[:]";
        }
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            builder.append("\"").append(escape(entry.getKey())).append("\": \"")
                    .append(escape(entry.getValue())).append("\"");
        }
        return builder.append("]").toString();
    }
}
