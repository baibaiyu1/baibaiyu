package com.gmd.faq.server.main.model.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.util.TenantUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 模型模块读取知识库引用，避免模型/知识库模块形成循环依赖。 */
@Component
@RequiredArgsConstructor
public class KnowledgeModelReferences {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private static final List<String> KEYS = List.of("llmModelId", "extractModelId", "keywordModelId", "queryModelId",
            "vlmModelId", "embeddingModelId", "rerankModelId");

    public List<String> names(List<Long> modelIds) {
        if (modelIds == null || modelIds.isEmpty() || TenantUtils.getCurrentTenantId() == null) return List.of();
        Set<String> ids = modelIds.stream().map(String::valueOf).collect(Collectors.toSet());
        // 模型管理权限不等于知识库可见权限，不在此处暴露其他成员的资源名称。
        return jdbc.query("SELECT connection_config FROM " + TableConst.KNOWLEDGE + " WHERE tenant_id = ?",
                (rs, row) -> {
                    try {
                        var connection = json.readTree(rs.getString("connection_config") == null ? "{}" : rs.getString("connection_config"));
                        return connection != null && KEYS.stream().anyMatch(key -> ids.contains(connection.path(key).asText()))
                                ? "知识库引用" : null;
                    } catch (java.io.IOException ex) { throw new IllegalStateException("知识库模型引用格式无效"); }
                }, TenantUtils.getCurrentTenantId()).stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    public void assertUnreferenced(List<Long> ids) {
        if (!names(ids).isEmpty()) throw new IllegalArgumentException("模型仍被知识库使用，请先解除知识库模型关联");
    }
}
