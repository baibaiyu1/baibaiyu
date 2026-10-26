package com.gmd.faq.server.main.console.tool;

import com.gmd.faq.server.main.common.dto.OpenApiImportDTO;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.vo.OpenApiPreviewVO;
import com.gmd.faq.server.main.tool.openapi.service.OpenApiImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * OpenAPI 导入 Controller
 * <p>
 * 支持 OpenAPI 3.x（JSON / YAML）与 Swagger 2.0 文档中的接口批量导入为自定义工具。
 * 导入以工具编号为幂等键，重复导入同一文档只会更新实现与参数。
 *
 */
@RestController
@RequestMapping("/tool/openapi")
@RequiredArgsConstructor
public class OpenApiImportController {

    private final OpenApiImportService openApiImportService;

    /**
     * 解析文档并预览可导入的接口（不落库）
     */
    @PostMapping("/preview")
    @PermNeed({"tool:openapi:import"})
    public R<OpenApiPreviewVO> preview(@RequestBody OpenApiImportDTO dto) {
        try {
            return R.data(openApiImportService.preview(dto));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    /**
     * 执行导入
     */
    @PostMapping("/import")
    @PermNeed({"tool:openapi:import"})
    public R<Map<String, Object>> importTools(@RequestBody OpenApiImportDTO dto) {
        try {
            return R.data(openApiImportService.importTools(dto), "导入完成");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }
}