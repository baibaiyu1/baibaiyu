package com.gmd.faq.server.main.console.sensitive;

import com.gmd.faq.server.main.common.dto.SensitiveWordConfigDTO;
import com.gmd.faq.server.main.common.entity.SensitiveWordConfig;
import com.gmd.faq.server.main.common.mp.support.MP;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.r.ResultCode;
import com.gmd.faq.server.main.common.util.BeanUtils;
import com.gmd.faq.server.main.common.vo.SensitiveWordConfigVO;
import com.gmd.faq.server.main.sensitive.service.SensitiveWordConfigService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 敏感词配置Controller
 *
 */
@RestController
@RequestMapping("/sensitive/config")
@RequiredArgsConstructor
public class SensitiveWordConfigController {

    private final SensitiveWordConfigService sensitiveWordConfigService;

    /**
     * 分页查询
     */
    @GetMapping("/page")
    public R<IPage<SensitiveWordConfigVO>> page(PageParams pageParams, SensitiveWordConfigDTO query) {
        IPage<SensitiveWordConfig> page = sensitiveWordConfigService.page(MP.getPage(pageParams), MP.getQueryWrapper(query));
        return R.data(BeanUtils.copyPage(page, SensitiveWordConfigVO.class));
    }

    /**
     * 详情
     */
    @GetMapping("/{id}")
    public R<SensitiveWordConfigVO> detail(@PathVariable("id") Long id) {
        SensitiveWordConfig entity = sensitiveWordConfigService.getById(id);

        SensitiveWordConfigVO vo = BeanUtils.copy(entity, SensitiveWordConfigVO.class);
        if (vo == null) {
            return R.fail(ResultCode.NOT_FOUND, "敏感词配置不存在");
        }
        vo.setUsed(sensitiveWordConfigService.usedWithAgent(List.of(id)));

        return R.data(vo);
    }

    /**
     * 新增
     */
    @PostMapping
    @PermNeed({"sensitive:word:add"})
    public R<Boolean> save(@RequestBody SensitiveWordConfig entity) {
        return R.data(sensitiveWordConfigService.save(entity));
    }

    /**
     * 修改
     */
    @PutMapping
    @PermNeed({"sensitive:word:edit"})
    public R<Boolean> update(@RequestBody SensitiveWordConfig entity) {
        return R.data(sensitiveWordConfigService.doUpdate(entity));
    }

    /**
     * 删除
     */
    @DeleteMapping
    @PermNeed({"sensitive:word:remove"})
    public R<Boolean> delete(@RequestBody List<Long> ids) {
        return R.data(sensitiveWordConfigService.deleteByIds(ids));
    }

    /**
     * 被哪些Agent使用
     */
    @PostMapping("used-with-agent")
    public R<List<Object>> usedWithAgent(@RequestBody List<Long> ids) {
        return R.data(sensitiveWordConfigService.usedWithAgent(ids));
    }

    /**
     * 获取所有分类
     */
    @GetMapping("/get/categories")
    public R<List<String>> listCategories() {
        return R.data(sensitiveWordConfigService.listCategories());
    }
}