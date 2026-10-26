package com.gmd.faq.server.main.console.hook;

import com.gmd.faq.server.main.common.dto.HookConfigDTO;
import com.gmd.faq.server.main.common.entity.HookConfig;
import com.gmd.faq.server.main.common.mp.support.MP;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.r.ResultCode;
import com.gmd.faq.server.main.common.util.BeanUtils;
import com.gmd.faq.server.main.common.vo.HookConfigVO;
import com.gmd.faq.server.main.hook.service.HookConfigService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * Hook配置Controller
 *
 */
@RestController
@RequestMapping("/hook-config")
@RequiredArgsConstructor
public class HookConfigController {

    private final HookConfigService hookConfigService;

    /**
     * 分页查询
     */
    @GetMapping("/page")
    public R<IPage<HookConfigVO>> page(PageParams pageParams, HookConfigDTO query) {
        IPage<HookConfig> page = hookConfigService.page(MP.getPage(pageParams), MP.getQueryWrapper(query));
        return R.data(BeanUtils.copyPage(page, HookConfigVO.class));
    }

    /**
     * 详情
     */
    @GetMapping("/{id}")
    public R<HookConfigVO> detail(@PathVariable("id") Long id) {
        HookConfig entity = hookConfigService.getById(id);

        HookConfigVO vo = BeanUtils.copy(entity, HookConfigVO.class);
        if (vo == null) {
            return R.fail(ResultCode.NOT_FOUND, "Hook 配置不存在");
        }
        vo.setUsed(hookConfigService.usedWithAgent(List.of(id)));

        return R.data(vo);
    }

    /**
     * 新增
     */
    @PostMapping
    @PermNeed({"hook:config:add"})
    public R<Boolean> save(@RequestBody HookConfig entity) {
        return R.data(hookConfigService.save(entity));
    }

    /**
     * 修改
     */
    @PutMapping
    @PermNeed({"hook:config:edit"})
    public R<Boolean> update(@RequestBody HookConfig entity) {
        return R.data(hookConfigService.doUpdate(entity));
    }

    /**
     * 删除
     */
    @DeleteMapping
    @PermNeed({"hook:config:remove"})
    public R<Boolean> delete(@RequestBody List<Long> ids) {
        return R.data(hookConfigService.deleteByIds(ids));
    }

    /**
     * 被哪些Agent使用
     */
    @PostMapping("used-with-agent")
    public R<List<Object>> usedWithAgent(@RequestBody List<Long> ids) {
        return R.data(hookConfigService.usedWithAgent(ids));
    }
}