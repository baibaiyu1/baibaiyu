package com.gmd.faq.server.main.console.model;

import com.gmd.faq.server.main.common.dto.ModelProviderDTO;
import com.gmd.faq.server.main.common.entity.ModelProvider;
import com.gmd.faq.server.main.common.mp.support.MP;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.util.BeanUtils;
import com.gmd.faq.server.main.common.vo.ModelProviderVO;
import com.gmd.faq.server.main.model.service.ModelProviderService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 模型提供商Controller
 *
 */
@RestController
@RequestMapping("/model/provider")
@RequiredArgsConstructor
public class ModelProviderController {

    private final ModelProviderService modelProviderService;

    /**
     * 分页查询
     */
    @GetMapping("/page")
    public R<IPage<ModelProviderVO>> page(PageParams pageParams, ModelProviderDTO query) {
        IPage<ModelProvider> page = modelProviderService.page(MP.getPage(pageParams), MP.getQueryWrapper(query));
        return R.data(BeanUtils.copyPage(page, ModelProviderVO.class));
    }

    /**
     * 详情
     */
    @GetMapping("/{id}")
    public R<ModelProviderVO> detail(@PathVariable("id") Long id) {
        ModelProvider entity = modelProviderService.getById(id);
        return R.data(BeanUtils.copy(entity, ModelProviderVO.class));
    }

    /**
     * 新增
     */
    @PostMapping
    @PermNeed({"model:provider:add"})
    public R<Boolean> save(@RequestBody ModelProvider entity) {
        return R.data(modelProviderService.save(entity));
    }

    /**
     * 修改
     */
    @PutMapping
    @PermNeed({"model:provider:edit"})
    public R<Boolean> update(@RequestBody ModelProvider entity) {
        return R.data(modelProviderService.doUpdate(entity));
    }

    /**
     * 删除
     */
    @DeleteMapping
    @PermNeed({"model:provider:remove"})
    public R<Boolean> delete(@RequestBody List<Long> ids) {
        return R.data(modelProviderService.deleteByIds(ids));
    }

    /**
     * 被哪些Agent使用
     */
    @PostMapping("used-with-model")
    public R<List<Object>> usedWithModel(@RequestBody List<Long> ids) {
        return R.data(modelProviderService.usedWithModel(ids));
    }
}