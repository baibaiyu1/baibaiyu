package com.gmd.faq.server.main.console.tool;

import com.gmd.faq.server.main.common.dto.ToolDTO;
import com.gmd.faq.server.main.common.entity.ToolConfig;
import com.gmd.faq.server.main.common.enums.ToolType;
import com.gmd.faq.server.main.common.mp.support.MP;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.r.ResultCode;
import com.gmd.faq.server.main.common.util.BeanUtils;
import com.gmd.faq.server.main.common.vo.ToolVO;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import com.gmd.faq.server.main.tool.service.ToolService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 工具Controller
 *
 */
@RestController
@RequestMapping("/tool")
@RequiredArgsConstructor
public class ToolController {

    private final ToolService toolService;
    private final ResourceVisibilityService resourceVisibilityService;

    /**
     * 分页查询
     */
    @GetMapping("/page")
    @PermNeed({"tool:config:list"})
    public R<IPage<ToolVO>> page(PageParams pageParams, ToolDTO query) {
        QueryWrapper<ToolConfig> wrapper = MP.getQueryWrapper(query);
        resourceVisibilityService.applyScope(wrapper, "TOOL", query.getScope(), query.getSpace());
        IPage<ToolConfig> page = toolService.page(MP.getPage(pageParams), wrapper);
        return R.data(BeanUtils.copyPage(page, ToolVO.class));
    }

    /**
     * 详情
     */
    @GetMapping("/{id}")
    @PermNeed({"tool:config:list"})
    public R<ToolVO> detail(@PathVariable("id") Long id) {
        resourceVisibilityService.assertViewable("TOOL", id);
        ToolConfig entity = toolService.getById(id);

        ToolVO vo = BeanUtils.copy(entity, ToolVO.class);
        if (vo == null) {
            return R.fail(ResultCode.NOT_FOUND, "工具不存在");
        }
        vo.setUsed(toolService.usedWithAgent(List.of(id)));

        return R.data(vo);
    }

    /**
     * 新增
     */
    @PostMapping
    @PermNeed({"tool:config:add"})
    public R<Boolean> save(@RequestBody ToolConfig entity) {
        entity.setToolType(ToolType.CUSTOM);
        return R.data(toolService.save(entity));
    }

    /**
     * 修改
     */
    @PutMapping
    @PermNeed({"tool:config:edit"})
    public R<Boolean> update(@RequestBody ToolConfig entity) {
        return R.data(toolService.doUpdate(entity));
    }

    /**
     * 删除
     */
    @DeleteMapping
    @PermNeed({"tool:config:remove"})
    public R<Boolean> delete(@RequestBody List<Long> ids) {
        return R.data(toolService.deleteTools(ids));
    }

    /**
     * 被哪些Agent使用
     */
    @PostMapping("used-with-agent")
    @PermNeed({"tool:config:list"})
    public R<List<Object>> usedWithAgent(@RequestBody List<Long> ids) {
        ids.forEach(id -> resourceVisibilityService.assertViewable("TOOL", id));
        return R.data(toolService.usedWithAgent(ids));
    }

    /**
     * 获取所有分类
     */
    @GetMapping("/get/categories")
    public R<List<String>> listCategories() {
        return R.data(toolService.listCategories());
    }
}
