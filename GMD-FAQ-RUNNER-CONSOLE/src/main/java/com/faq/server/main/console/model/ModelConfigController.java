package com.gmd.faq.server.main.console.model;

import com.gmd.faq.server.main.common.dto.ModelConfigDTO;
import com.gmd.faq.server.main.common.entity.ModelConfig;
import com.gmd.faq.server.main.common.mp.support.MP;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.r.ResultCode;
import com.gmd.faq.server.main.common.util.BeanUtils;
import com.gmd.faq.server.main.common.vo.ModelConfigVO;
import com.gmd.faq.server.main.model.service.ModelConfigService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.gmd.faq.server.main.model.service.ModelProviderService;
import com.gmd.faq.server.main.common.model.ReasoningConfigSupport;
import com.gmd.faq.server.main.common.model.ReasoningCapabilities;
import com.gmd.faq.server.main.common.enums.ModelApiType;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 模型配置Controller
 *
 */
@RestController
@RequestMapping("/model/config")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelConfigService modelConfigService;
    private final ModelProviderService modelProviderService;

    /**
     * 分页查询
     */
    @GetMapping("/page")
    public R<IPage<ModelConfigVO>> page(PageParams pageParams, ModelConfigDTO query) {
        IPage<ModelConfig> page = modelConfigService.page(MP.getPage(pageParams), MP.getQueryWrapper(query));
        IPage<ModelConfigVO> result = BeanUtils.copyPage(page, ModelConfigVO.class);
        if (!page.getRecords().isEmpty()) {
            var providers = modelProviderService.listByIds(page.getRecords().stream().map(ModelConfig::getProviderId).distinct().toList())
                    .stream().collect(Collectors.toMap(com.gmd.faq.server.main.common.entity.ModelProvider::getId, p -> p));
            Map<Long, ModelConfig> configs = page.getRecords().stream().collect(Collectors.toMap(ModelConfig::getId, c -> c));
            result.getRecords().forEach(vo -> describeReasoning(vo, configs.get(vo.getId()), providers.get(vo.getProviderId())));
        }
        return R.data(result);
    }

    /**
     * 详情
     */
    @GetMapping("/{id}")
    public R<ModelConfigVO> detail(@PathVariable("id") Long id) {
        ModelConfig entity = modelConfigService.getById(id);
        if (entity == null) {
            return R.fail(ResultCode.NOT_FOUND, "模型配置不存在");
        }
        ModelConfigVO vo = BeanUtils.copy(entity, ModelConfigVO.class);
        describeReasoning(vo, entity, modelProviderService.getById(entity.getProviderId()));
        vo.setUsed(modelConfigService.usedWithAgent(List.of(id)));

        return R.data(vo);
    }

    @GetMapping("/reasoning-capabilities")
    public R<ReasoningCapabilities> reasoningCapabilities(@RequestParam("providerId") Long providerId, @RequestParam("modelId") String modelId,
            @RequestParam(value = "apiType", defaultValue = "CHAT_COMPLETIONS") ModelApiType apiType,
            @RequestParam(value = "profile", defaultValue = "AUTO") String profile) {
        var provider = modelProviderService.getById(providerId);
        if (provider == null) return R.fail(ResultCode.NOT_FOUND, "模型供应商不存在");
        return R.data(ReasoningConfigSupport.capabilities(provider.getType(), provider.getBaseUrl(), modelId, apiType, profile));
    }

    private void describeReasoning(ModelConfigVO vo, ModelConfig config, com.gmd.faq.server.main.common.entity.ModelProvider provider) {
        if (provider == null) return;
        vo.setReasoningConfig(ReasoningConfigSupport.modelSettings(config, provider));
        vo.setReasoningCapabilities(ReasoningConfigSupport.capabilities(config, provider));
    }

    /**
     * 新增
     */
    @PostMapping
    @PermNeed({"model:config:add"})
    public R<Long> save(@RequestBody ModelConfig entity) {
        modelConfigService.save(entity);
        return R.data(entity.getId());
    }

    /**
     * 修改
     */
    @PutMapping
    @PermNeed({"model:config:edit"})
    public R<Long> update(@RequestBody ModelConfig entity) {
        modelConfigService.doUpdate(entity);
        return R.data(entity.getId());
    }

    /**
     * 删除
     */
    @DeleteMapping
    @PermNeed({"model:config:remove"})
    public R<Boolean> delete(@RequestBody List<Long> ids) {
        return R.data(modelConfigService.deleteByIds(ids));
    }

    /**
     * 被哪些Agent使用
     */
    @PostMapping("used-with-agent")
    public R<List<Object>> usedWithAgent(@RequestBody List<Long> ids) {
        return R.data(modelConfigService.usedWithAgent(ids));
    }
}
