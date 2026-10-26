package com.gmd.faq.server.main.console.studio;

import com.gmd.faq.server.main.common.entity.StudioConfig;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.studio.service.StudioConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 描述：StudioConfigController
 *
 **/
@RestController
@RequestMapping("/studio")
@RequiredArgsConstructor
public class StudioConfigController {
    private final StudioConfigService studioConfigService;

    /**
     * 分页查询
     */
    @GetMapping("/list")
    public R<List<StudioConfig>> page() {
        return R.data(studioConfigService.list());
    }

    /**
     * 详情
     */
    @GetMapping("/{id}")
    public R<StudioConfig> detail(@PathVariable("id") Long id) {
        return R.data(studioConfigService.getById(id));
    }

    /**
     * 新增
     */
    @PostMapping
    @PermNeed({"agent:studio:edit"})
    public R<Boolean> save(@RequestBody StudioConfig entity) {
        return R.data(studioConfigService.save(entity));
    }

    /**
     * 修改
     */
    @PutMapping
    @PermNeed({"agent:studio:edit"})
    public R<Boolean> update(@RequestBody StudioConfig entity) {
        return R.data(studioConfigService.updateById(entity));
    }

    /**
     * 删除
     */
    @DeleteMapping
    @PermNeed({"agent:studio:edit"})
    public R<Boolean> delete(@RequestBody List<Long> ids) {
        return R.data(studioConfigService.removeByIds(ids));
    }

    /**
     * 被哪些Agent使用
     */
    @PostMapping("used-with-agent")
    public R<List<Object>> usedWithAgent(@RequestBody List<Long> ids) {
        return R.data(studioConfigService.usedWithAgent(ids));
    }
}