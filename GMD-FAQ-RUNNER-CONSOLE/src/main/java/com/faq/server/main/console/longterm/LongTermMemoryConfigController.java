package com.gmd.faq.server.main.console.longterm;

import com.gmd.faq.server.main.common.entity.LongTermMemoryConfig;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.longterm.service.LongTermMemoryConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 描述：LongTermMemoryConfigController
 *
 **/
@RestController
@RequestMapping("/long-term-memory")
@RequiredArgsConstructor
public class LongTermMemoryConfigController {
    private final LongTermMemoryConfigService longTermMemoryConfigService;

    /**
     * 分页查询
     */
    @GetMapping("/list")
    public R<List<LongTermMemoryConfig>> list() {
        return R.data(longTermMemoryConfigService.list());
    }

    /**
     * 详情
     */
    @GetMapping("/{id}")
    public R<LongTermMemoryConfig> detail(@PathVariable("id") Long id) {
        return R.data(longTermMemoryConfigService.getById(id));
    }

    /**
     * 新增
     */
    @PostMapping
    @PermNeed({"agent:memory:edit"})
    public R<Boolean> save(@RequestBody LongTermMemoryConfig entity) {
        return R.data(longTermMemoryConfigService.save(entity));
    }

    /**
     * 修改
     */
    @PutMapping
    @PermNeed({"agent:memory:edit"})
    public R<Boolean> update(@RequestBody LongTermMemoryConfig entity) {
        return R.data(longTermMemoryConfigService.updateById(entity));
    }

    /**
     * 删除
     */
    @DeleteMapping
    @PermNeed({"agent:memory:edit"})
    public R<Boolean> delete(@RequestBody List<Long> ids) {
        return R.data(longTermMemoryConfigService.removeByIds(ids));
    }

    /**
     * 被哪些Agent使用
     */
    @PermNeed({"agent:memory:list"})
    @PostMapping("used-with-agent")
    public R<List<Object>> usedWithAgent(@RequestBody List<Long> ids) {
        return R.data(longTermMemoryConfigService.usedWithAgent(ids));
    }
}