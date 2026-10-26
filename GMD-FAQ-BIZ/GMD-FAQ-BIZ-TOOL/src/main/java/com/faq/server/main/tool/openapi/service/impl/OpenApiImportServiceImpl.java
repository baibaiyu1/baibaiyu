package com.gmd.faq.server.main.tool.openapi.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gmd.faq.server.main.common.dto.OpenApiImportDTO;
import com.gmd.faq.server.main.common.entity.ToolConfig;
import com.gmd.faq.server.main.common.enums.CodeLanguage;
import com.gmd.faq.server.main.common.enums.ScopeType;
import com.gmd.faq.server.main.common.enums.ToolType;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.common.util.ResourceOwnershipSupport;
import com.gmd.faq.server.main.common.vo.OpenApiOperationVO;
import com.gmd.faq.server.main.common.vo.OpenApiPreviewVO;
import com.gmd.faq.server.main.tool.openapi.OpenApiGroovyGenerator;
import com.gmd.faq.server.main.tool.openapi.OpenApiOperation;
import com.gmd.faq.server.main.tool.openapi.OpenApiSpecParser;
import com.gmd.faq.server.main.tool.openapi.service.OpenApiImportService;
import com.gmd.faq.server.main.tool.service.ToolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAPI 导入 Service 实现
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenApiImportServiceImpl implements OpenApiImportService {

    /**
     * 单次导入的接口数量上限，避免超大文档一次生成过多工具
     */
    private static final int MAX_IMPORT_SIZE = 100;

    /**
     * 未指定分类且文档无 tag 时的默认分类
     */
    private static final String DEFAULT_CATEGORY = "OpenAPI导入";

    private final ToolService toolService;

    @Override
    public OpenApiPreviewVO preview(OpenApiImportDTO dto) {
        OpenApiSpecParser.Document document = OpenApiSpecParser.parse(dto.getSpec());
        String baseUrl = resolveBaseUrl(dto, document);

        OpenApiPreviewVO preview = new OpenApiPreviewVO();
        preview.setTitle(document.getTitle());
        preview.setVersion(document.getVersion());
        preview.setSpecVersion(document.getSpecVersion());
        preview.setServers(document.getServers());
        preview.setBaseUrl(baseUrl);

        List<OpenApiOperationVO> operations = new ArrayList<>();
        Set<String> usedToolIds = new HashSet<>();
        for (OpenApiOperation operation : document.getOperations()) {
            String toolId = OpenApiGroovyGenerator.buildToolId(operation);
            if (!usedToolIds.add(toolId)) {
                toolId = OpenApiGroovyGenerator.withSuffix(toolId, usedToolIds.size());
                usedToolIds.add(toolId);
            }
            OpenApiOperationVO vo = new OpenApiOperationVO();
            vo.setOperationId(operation.getOperationId());
            vo.setToolId(toolId);
            vo.setMethod(operation.getMethod());
            vo.setPath(operation.getPath());
            vo.setSummary(operation.getSummary());
            vo.setTag(operation.getTag());
            vo.setParamCount(operation.getParameters().size());
            vo.setHasBody(operation.isHasBody());
            vo.setExists(toolService.getOne(Wrappers.<ToolConfig>lambdaQuery()
                    .eq(ToolConfig::getToolId, toolId)) != null);
            operations.add(vo);
        }
        preview.setOperations(operations);
        return preview;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> importTools(OpenApiImportDTO dto) {
        OpenApiSpecParser.Document document = OpenApiSpecParser.parse(dto.getSpec());
        String baseUrl = resolveBaseUrl(dto, document);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("文档未声明服务地址（servers / host），请在导入时指定基地址");
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("基地址需为完整的 http(s) 地址：" + baseUrl);
        }

        Set<String> selected = dto.getOperationIds() == null
                ? Set.of()
                : new HashSet<>(dto.getOperationIds());

        List<OpenApiOperation> targets = new ArrayList<>();
        Set<String> usedToolIds = new HashSet<>();
        Map<String, String> toolIdMap = new LinkedHashMap<>();
        for (OpenApiOperation operation : document.getOperations()) {
            String toolId = OpenApiGroovyGenerator.buildToolId(operation);
            if (!usedToolIds.add(toolId)) {
                toolId = OpenApiGroovyGenerator.withSuffix(toolId, usedToolIds.size());
                usedToolIds.add(toolId);
            }
            toolIdMap.put(operationKey(operation), toolId);
            if (selected.isEmpty() || selected.contains(operationKey(operation)) || selected.contains(toolId)) {
                targets.add(operation);
            }
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("没有选中任何接口");
        }
        if (targets.size() > MAX_IMPORT_SIZE) {
            throw new IllegalArgumentException("单次最多导入 " + MAX_IMPORT_SIZE + " 个接口，当前选中 " + targets.size() + " 个");
        }

        int inserted = 0;
        int updated = 0;
        List<String> toolIds = new ArrayList<>();
        for (OpenApiOperation operation : targets) {
            String toolId = toolIdMap.get(operationKey(operation));
            OpenApiGroovyGenerator.GeneratedTool generated = OpenApiGroovyGenerator.generate(
                    operation, baseUrl, dto.getAuthHeaderName(), dto.getAuthHeaderValue());
            generated.setToolId(toolId);

            String category = resolveCategory(dto, operation);
            ToolConfig exists = toolService.getOne(Wrappers.<ToolConfig>lambdaQuery()
                    .eq(ToolConfig::getToolId, toolId));
            if (exists == null) {
                ToolConfig toolConfig = new ToolConfig();
                toolConfig.setToolId(toolId);
                toolConfig.setName(generated.getName());
                toolConfig.setDescription(generated.getDescription());
                toolConfig.setCategory(category);
                toolConfig.setToolType(ToolType.CUSTOM);
                toolConfig.setNeedConfirm(false);
                toolConfig.setInputSchema(JsonUtils.parse(generated.getInputSchema()));
                toolConfig.setLanguage(CodeLanguage.JAVA);
                toolConfig.setCode(generated.getCode());
                toolConfig.setVersion("1.0.0");
                toolConfig.setScopeType(ScopeType.TENANT);
                toolService.save(toolConfig);
                inserted++;
            } else {
                // 幂等：同编号工具按最新文档更新实现与参数，保留原有启用状态
                // 但别人的资源不能借「导入」覆盖：归属人或管理员才允许覆盖
                ResourceOwnershipSupport.assertManageable(exists, "覆盖导入");
                exists.setName(generated.getName());
                exists.setDescription(generated.getDescription());
                exists.setCategory(category);
                exists.setToolType(ToolType.CUSTOM);
                exists.setInputSchema(JsonUtils.parse(generated.getInputSchema()));
                exists.setLanguage(CodeLanguage.JAVA);
                exists.setCode(generated.getCode());
                toolService.updateById(exists);
                updated++;
            }
            toolIds.add(toolId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inserted", inserted);
        result.put("updated", updated);
        result.put("skipped", document.getOperations().size() - targets.size());
        result.put("toolIds", toolIds);
        log.info("OpenAPI 导入完成：新增 {} 个，更新 {} 个，跳过 {} 个", inserted, updated, result.get("skipped"));
        return result;
    }

    /**
     * 解析实际使用的基地址：请求指定优先，其次文档 servers
     */
    private String resolveBaseUrl(OpenApiImportDTO dto, OpenApiSpecParser.Document document) {
        String baseUrl = dto.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = document.getBaseUrl();
        }
        if (baseUrl == null) {
            return "";
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl.trim();
    }

    /**
     * 解析工具分类：请求指定 > 文档 tag > 默认分类
     */
    private String resolveCategory(OpenApiImportDTO dto, OpenApiOperation operation) {
        if (dto.getCategory() != null && !dto.getCategory().isBlank()) {
            return dto.getCategory().trim();
        }
        if (operation.getTag() != null && !operation.getTag().isBlank()) {
            return operation.getTag().trim();
        }
        return DEFAULT_CATEGORY;
    }

    /**
     * 操作唯一标识（operationId 可能为空，用方法 + 路径兜底，与前端选项值一致）
     */
    private String operationKey(OpenApiOperation operation) {
        String operationId = operation.getOperationId();
        if (operationId != null && !operationId.isBlank()) {
            return operationId;
        }
        return operation.getMethod().toLowerCase() + " " + operation.getPath();
    }
}
