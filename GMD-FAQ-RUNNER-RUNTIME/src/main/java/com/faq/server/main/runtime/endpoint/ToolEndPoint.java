package com.gmd.faq.server.main.runtime.endpoint;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gmd.faq.server.main.common.config.auth.SkAccess;
import com.gmd.faq.server.main.common.entity.ToolConfig;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import com.gmd.faq.server.main.runtime.tool.ToolInvokeService;
import com.gmd.faq.server.main.tool.service.ToolService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;

/**
 * 描述：执行工具调用
 *
 **/
@RestController
@RequestMapping("/runtime/tool")
@RequiredArgsConstructor
public class ToolEndPoint {
    private final ToolService toolService;
    private final ToolInvokeService toolInvokeService;
    private final ResourceVisibilityService resourceVisibilityService;

    @SkAccess
    @PostMapping("/{toolName}/do")
    public R<?> doTool(@PathVariable("toolName") String toolName, @RequestBody LinkedHashMap<String, Object> args) {
        ToolConfig toolConfig = toolService.getOne(new LambdaQueryWrapper<ToolConfig>().eq(ToolConfig::getToolId, toolName));

        if (toolConfig == null) {
            return R.data("工具调用失败");
        }
        if (!Boolean.TRUE.equals(toolConfig.getEnabled())) {
            return R.fail("工具已禁用");
        }
        resourceVisibilityService.assertUsable("TOOL", toolConfig.getId());

        try {
            return R.data(toolInvokeService.invoke(toolConfig, args));
        } catch (Exception e) {
            // 动态工具执行异常（网络不通、脚本报错等）以可读信息返回，避免调试时只看到「系统异常」
            return R.fail("工具执行失败：" + e.getMessage());
        }
    }
}
