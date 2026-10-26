package com.gmd.faq.server.main.console.mcp;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gmd.faq.server.main.common.dto.McpServerDTO;
import com.gmd.faq.server.main.common.dto.McpToolDebugDTO;
import com.gmd.faq.server.main.common.dto.McpToolEnabledDTO;
import com.gmd.faq.server.main.common.dto.McpToolNeedConfirmDTO;
import com.gmd.faq.server.main.common.entity.McpServer;
import com.gmd.faq.server.main.common.entity.McpTool;
import com.gmd.faq.server.main.common.mp.support.MP;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.util.BeanUtils;
import com.gmd.faq.server.main.common.vo.McpServerVO;
import com.gmd.faq.server.main.common.vo.McpToolDebugResultVO;
import com.gmd.faq.server.main.common.vo.McpToolVO;
import com.gmd.faq.server.main.mcp.service.McpServerService;
import com.gmd.faq.server.main.mcp.service.McpToolDebugService;
import com.gmd.faq.server.main.mcp.service.McpToolService;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * MCP 服务配置 Controller
 *
 */
@RestController
@RequestMapping("/mcp/server")
@RequiredArgsConstructor
public class McpServerController {

    private final McpServerService mcpServerService;
    private final McpToolService mcpToolService;
    private final McpToolDebugService mcpToolDebugService;
    private final ResourceVisibilityService resourceVisibilityService;

    /**
     * 分页查询
     */
    @GetMapping("/page")
    @PermNeed({"mcp:server:list"})
    public R<IPage<McpServerVO>> page(PageParams pageParams, McpServerDTO query) {
        QueryWrapper<McpServer> wrapper = MP.getQueryWrapper(query);
        resourceVisibilityService.applyScope(wrapper, "MCP", query.getScope(), query.getSpace());
        IPage<McpServer> page = mcpServerService.page(MP.getPage(pageParams), wrapper);
        IPage<McpServerVO> pageVo = BeanUtils.copyPage(page, McpServerVO.class);
        fillAvailableToolCount(pageVo.getRecords());
        return R.data(pageVo);
    }

    /**
     * 详情
     */
    @GetMapping("/{id}")
    @PermNeed({"mcp:server:list"})
    public R<McpServerVO> detail(@PathVariable("id") Long id) {
        resourceVisibilityService.assertViewable("MCP", id);
        return R.data(toVo(mcpServerService.getById(id)));
    }

    /**
     * 新增
     */
    @PostMapping
    @PermNeed({"mcp:server:add"})
    public R<McpServerVO> save(@RequestBody McpServer entity) {
        mcpServerService.save(entity);
        return R.data(toVo(mcpServerService.getById(entity.getId())));
    }

    /**
     * 修改
     */
    @PutMapping
    @PermNeed({"mcp:server:edit"})
    public R<McpServerVO> update(@RequestBody McpServer entity) {
        return R.data(toVo(mcpServerService.doUpdate(entity)));
    }

    /**
     * 激活
     */
    @PostMapping("/{id}/activate")
    @PermNeed({"mcp:server:activate"})
    public R<McpServerVO> activate(@PathVariable("id") Long id) {
        return R.data(toVo(mcpServerService.activate(id)));
    }

    /**
     * 同步工具目录
     */
    @PostMapping("/{id}/sync-tools")
    @PermNeed({"mcp:server:sync"})
    public R<McpServerVO> syncTools(@PathVariable("id") Long id) {
        return R.data(toVo(mcpServerService.syncTools(id)));
    }

    /**
     * 工具列表
     * <p>
     * 只读目录，沿用 {@code mcp:server:list}（viewer 也要能看）；
     * 工具治理的写操作由 {@code mcp:tool:edit} + Service 层归属校验把关。
     */
    @GetMapping("/{id}/tools")
    @PermNeed({"mcp:server:list"})
    public R<List<McpToolVO>> listTools(@PathVariable("id") Long id) {
        resourceVisibilityService.assertViewable("MCP", id);
        return R.data(mcpServerService.listTools(id));
    }

    /**
     * 批量切换工具全局可用状态
     */
    @PutMapping("/{id}/tools/global-enabled")
    @PermNeed({"mcp:tool:edit"})
    public R<McpServerVO> updateToolGlobalEnabled(@PathVariable("id") Long id,
                                                  @RequestBody McpToolEnabledDTO dto) {
        return R.data(toVo(mcpServerService.updateToolGlobalEnabled(id, dto)));
    }

    /**
     * 批量切换工具是否需要人工确认
     */
    @PutMapping("/{id}/tools/global-need-confirm")
    @PermNeed({"mcp:tool:edit"})
    public R<McpServerVO> updateToolNeedConfirm(@PathVariable("id") Long id,
                                                @RequestBody McpToolNeedConfirmDTO dto) {
        return R.data(toVo(mcpServerService.updateToolNeedConfirm(id, dto)));
    }

    /**
     * 调试调用工具
     */
    @PostMapping("/tools/debug")
    @PermNeed({"mcp:server:debug"})
    public R<McpToolDebugResultVO> debugTool(@RequestBody McpToolDebugDTO dto) {
        McpTool tool = mcpToolService.getById(dto.getToolId());
        if (tool != null) {
            resourceVisibilityService.assertUsable("MCP", tool.getMcpServerId());
        }
        return R.data(mcpToolDebugService.debugTool(dto.getToolId(), dto.getInput()));
    }

    /**
     * 删除
     */
    @DeleteMapping
    @PermNeed({"mcp:server:remove"})
    public R<Boolean> delete(@RequestBody List<Long> ids) {
        return R.data(mcpServerService.deleteByIds(ids));
    }

    /**
     * 被哪些 Agent 使用
     */
    @PostMapping("used-with-agent")
    @PermNeed({"mcp:server:list"})
    public R<List<Object>> usedWithAgent(@RequestBody List<Long> ids) {
        ids.forEach(id -> resourceVisibilityService.assertViewable("MCP", id));
        return R.data(mcpServerService.usedWithAgent(ids));
    }

    private McpServerVO toVo(McpServer entity) {
        if (entity == null) {
            return null;
        }
        McpServerVO vo = BeanUtils.copy(entity, McpServerVO.class);
        vo.setUsed(mcpServerService.usedWithAgent(List.of(entity.getId())));
        fillAvailableToolCount(List.of(vo));
        return vo;
    }

    private void fillAvailableToolCount(List<McpServerVO> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Map<Long, Integer> countMap = mcpToolService.countAvailableTools(items.stream()
                .map(item -> Long.valueOf(String.valueOf(item.getId())))
                .collect(Collectors.toList()));
        items.forEach(item -> item.setAvailableToolCount(countMap.getOrDefault(
                Long.valueOf(String.valueOf(item.getId())),
                0)));
    }
}
