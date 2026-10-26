package com.gmd.faq.server.main.console.prompt;

import com.gmd.faq.server.main.common.dto.SystemPromptTemplateDTO;
import com.gmd.faq.server.main.common.entity.SystemPromptTemplate;
import com.gmd.faq.server.main.common.mp.support.MP;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.r.ResultCode;
import com.gmd.faq.server.main.common.util.BeanUtils;
import com.gmd.faq.server.main.common.vo.SystemPromptTemplateVO;
import com.gmd.faq.server.main.prompt.service.SystemPromptTemplateService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 系统提示词模板Controller
 *
 */
@RestController
@RequestMapping("/prompt/template")
@RequiredArgsConstructor
public class SystemPromptTemplateController {

    private final SystemPromptTemplateService systemPromptTemplateService;

    /**
     * 分页查询
     */
    @GetMapping("/page")
    public R<IPage<SystemPromptTemplateVO>> page(PageParams pageParams, SystemPromptTemplateDTO query) {
        IPage<SystemPromptTemplate> page = systemPromptTemplateService.page(MP.getPage(pageParams), MP.getQueryWrapper(query));
        return R.data(BeanUtils.copyPage(page, SystemPromptTemplateVO.class));
    }

    /**
     * 详情
     */
    @GetMapping("/{id}")
    public R<SystemPromptTemplateVO> detail(@PathVariable("id") Long id) {
        SystemPromptTemplate entity = systemPromptTemplateService.getById(id);

        SystemPromptTemplateVO vo = BeanUtils.copy(entity, SystemPromptTemplateVO.class);
        if (vo == null) {
            return R.fail(ResultCode.NOT_FOUND, "提示词模板不存在");
        }
        vo.setUsed(systemPromptTemplateService.usedWithAgent(List.of(id)));

        return R.data(vo);
    }

    /**
     * 新增
     */
    @PostMapping
    @PermNeed({"prompt:template:add"})
    public R<Boolean> save(@RequestBody SystemPromptTemplate entity) {
        return R.data(systemPromptTemplateService.save(entity));
    }

    /**
     * 修改
     */
    @PutMapping
    @PermNeed({"prompt:template:edit"})
    public R<Boolean> update(@RequestBody SystemPromptTemplate entity) {
        return R.data(systemPromptTemplateService.doUpdate(entity));
    }

    /**
     * 删除
     */
    @DeleteMapping
    @PermNeed({"prompt:template:remove"})
    public R<Boolean> delete(@RequestBody List<Long> ids) {
        return R.data(systemPromptTemplateService.deleteByIds(ids));
    }

    /**
     * 被哪些Agent使用
     */
    @PostMapping("used-with-agent")
    public R<List<Object>> usedWithAgent(@RequestBody List<Long> ids) {
        return R.data(systemPromptTemplateService.usedWithAgent(ids));
    }

    /**
     * 获取所有分类
     */
    @GetMapping("/get/categories")
    public R<List<String>> listCategories() {
        return R.data(systemPromptTemplateService.listCategories());
    }
}