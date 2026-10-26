package com.gmd.faq.server.main.console.agent;

import com.gmd.faq.server.main.common.entity.CodeExecutionConfig;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.agent.service.CodeExecutionConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 描述：CodeExecutionConfigController
 *
 **/
@RestController
@RequestMapping("/agent/code-execution")
@RequiredArgsConstructor
public class CodeExecutionConfigController {
    private final CodeExecutionConfigService codeExecutionConfigService;

    /**
     * 分页查询
     */
    @GetMapping("/list")
    public R<List<CodeExecutionConfig>> list() {
        return R.data(codeExecutionConfigService.list());
    }

    /**
     * 详情
     */
    @GetMapping("/{id}")
    public R<CodeExecutionConfig> detail(@PathVariable("id") Long id) {
        return R.data(codeExecutionConfigService.getById(id));
    }

    /**
     * 新增
     */
    @PostMapping
    @PermNeed({"agent:code:edit"})
    public R<Boolean> save(@RequestBody CodeExecutionConfig entity) {
        return R.data(codeExecutionConfigService.save(entity));
    }

    /**
     * 修改
     */
    @PutMapping
    @PermNeed({"agent:code:edit"})
    public R<Boolean> update(@RequestBody CodeExecutionConfig entity) {
        return R.data(codeExecutionConfigService.updateById(entity));
    }

    /**
     * 删除
     */
    @DeleteMapping
    @PermNeed({"agent:code:edit"})
    public R<Boolean> delete(@RequestBody List<Long> ids) {
        return R.data(codeExecutionConfigService.removeByIds(ids));
    }

    /**
     * 被哪些Agent使用
     */
    @PostMapping("used-with-agent")
    public R<List<Object>> usedWithAgent(@RequestBody List<Long> ids) {
        return R.data(codeExecutionConfigService.usedWithAgent(ids));
    }
}