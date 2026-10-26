package com.gmd.faq.server.main.console.knowledge;

import com.gmd.faq.server.main.common.dto.KnowledgeBaseConfigDTO;
import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import com.gmd.faq.server.main.common.enums.KbType;
import com.gmd.faq.server.main.common.mp.support.MP;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.r.ResultCode;
import com.gmd.faq.server.main.common.util.BeanUtils;
import com.gmd.faq.server.main.common.vo.KnowledgeBaseConfigVO;
import com.gmd.faq.server.main.knowledge.service.KnowledgeBaseConfigService;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 知识库配置Controller
 *
 */
@RestController
@RequestMapping("/knowledge/config")
@RequiredArgsConstructor
public class KnowledgeBaseConfigController {

    private final KnowledgeBaseConfigService knowledgeBaseConfigService;
    private final ResourceVisibilityService resourceVisibilityService;

    /**
     * 知识库来源字典（供前端下拉/标签直接渲染，新增来源时只需在此扩展）
     * <p>
     * 返回 value / label / description / uploadSupported（是否支持在平台内上传文档）。
     */
    @GetMapping("/sources")
    public R<List<Map<String, Object>>> sources() {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (KbType type : KbType.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("value", type.name());
            item.put("label", type.getDescription());
            boolean local = type == KbType.LOCAL;
            item.put("description", local ? "平台本地知识库，支持上传文档并本地向量化"
                    : type == KbType.LIGHTRAG ? "支持文档上传、解析进度管理与知识图谱浏览" : "外部知识库服务，按连接配置检索");
            item.put("uploadSupported", local || type == KbType.LIGHTRAG);
            sources.add(item);
        }
        return R.data(sources);
    }

    /**
     * 分页查询
     */
    @GetMapping("/page")
    @PermNeed({"knowledge:config:list"})
    public R<IPage<KnowledgeBaseConfigVO>> page(PageParams pageParams, KnowledgeBaseConfigDTO query) {
        QueryWrapper<KnowledgeBaseConfig> wrapper = MP.getQueryWrapper(query);
        resourceVisibilityService.applyScope(wrapper, "KNOWLEDGE", query.getScope(), query.getSpace());
        IPage<KnowledgeBaseConfig> page = knowledgeBaseConfigService.page(MP.getPage(pageParams), wrapper);
        return R.data(BeanUtils.copyPage(page, KnowledgeBaseConfigVO.class));
    }

    /**
     * 详情
     */
    @GetMapping("/{id}")
    @PermNeed({"knowledge:config:list"})
    public R<KnowledgeBaseConfigVO> detail(@PathVariable("id") Long id) {
        resourceVisibilityService.assertViewable("KNOWLEDGE", id);
        KnowledgeBaseConfig entity = knowledgeBaseConfigService.getById(id);

        KnowledgeBaseConfigVO vo = BeanUtils.copy(entity, KnowledgeBaseConfigVO.class);
        if (vo == null) {
            return R.fail(ResultCode.NOT_FOUND, "知识库配置不存在");
        }
        vo.setUsed(knowledgeBaseConfigService.usedWithAgent(List.of(id)));

        return R.data(vo);
    }

    /**
     * 新增
     */
    @PostMapping
    @PermNeed({"knowledge:config:add"})
    public R<Boolean> save(@RequestBody KnowledgeBaseConfig entity) {
        return R.data(knowledgeBaseConfigService.save(entity));
    }

    /**
     * 修改
     */
    @PutMapping
    @PermNeed({"knowledge:config:edit"})
    public R<Boolean> update(@RequestBody KnowledgeBaseConfig entity) {
        return R.data(knowledgeBaseConfigService.doUpdate(entity));
    }

    /**
     * 删除
     */
    @DeleteMapping
    @PermNeed({"knowledge:config:remove"})
    public R<Boolean> delete(@RequestBody List<Long> ids) {
        return R.data(knowledgeBaseConfigService.deleteByIds(ids));
    }

    /**
     * 被哪些Agent使用
     */
    @PostMapping("used-with-agent")
    @PermNeed({"knowledge:config:list"})
    public R<List<Object>> usedWithAgent(@RequestBody List<Long> ids) {
        ids.forEach(id -> resourceVisibilityService.assertViewable("KNOWLEDGE", id));
        return R.data(knowledgeBaseConfigService.usedWithAgent(ids));
    }
}
