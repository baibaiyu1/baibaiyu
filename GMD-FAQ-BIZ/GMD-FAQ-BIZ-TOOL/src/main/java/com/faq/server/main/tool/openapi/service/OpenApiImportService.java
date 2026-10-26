package com.gmd.faq.server.main.tool.openapi.service;

import com.gmd.faq.server.main.common.dto.OpenApiImportDTO;
import com.gmd.faq.server.main.common.vo.OpenApiPreviewVO;

import java.util.Map;

/**
 * OpenAPI 导入 Service
 * <p>
 * 将 OpenAPI / Swagger 文档中的接口批量转换为平台的自定义工具（Groovy 动态工具）。
 * 导入以「工具编号」为幂等键：同编号已存在则更新实现与参数，不产生重复工具。
 *
 */
public interface OpenApiImportService {

    /**
     * 解析文档并预览可导入的接口（不落库）
     *
     * @param dto 请求（至少包含 spec）
     * @return 预览结果
     */
    OpenApiPreviewVO preview(OpenApiImportDTO dto);

    /**
     * 执行导入
     *
     * @param dto 请求（spec + 选中的 operationId + 可选基地址/分类/鉴权头）
     * @return 导入结果：inserted / updated / skipped / toolIds
     */
    Map<String, Object> importTools(OpenApiImportDTO dto);
}
