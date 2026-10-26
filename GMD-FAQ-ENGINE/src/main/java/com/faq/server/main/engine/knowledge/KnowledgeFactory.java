package com.gmd.faq.server.main.engine.knowledge;

import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import com.gmd.faq.server.main.common.enums.KbType;
import com.gmd.faq.server.main.common.model.KnowledgeRetrievalSettings;
import com.gmd.faq.server.main.common.wrapper.KnowledgeWrapper;
import com.gmd.faq.server.main.knowledge.service.KnowledgeBaseConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 描述：知识库工程
 *
 **/
@Component
@RequiredArgsConstructor
public class KnowledgeFactory {
    private static final Map<KbType, IKnowledge> KNOWLEDGE_MAP = new ConcurrentHashMap<>();

    private final KnowledgeBaseConfigService knowledgeBaseConfigService;

    public List<KnowledgeWrapper> getKnowledge(AgentDefinition definition) {
        List<KnowledgeBaseConfig> knowledgeBaseConfigs = knowledgeBaseConfigService.getByAgentId(definition.getId());
        KnowledgeRetrievalSettings settings = KnowledgeRetrievalSettings.parse(definition.getRagConfig());
        return getKnowledgeWrapper(knowledgeBaseConfigs == null ? null
                : knowledgeBaseConfigs.stream().map(settings::apply).toList());
    }

    /**
     * 根据知识库配置构建知识库实体（Knowledge）。
     *
     * @param knowledgeBaseConfigs 知识库配置
     * @return 知识库包装对象，配置为空/未启用/类型未注册时返回 null
     */
    public List<KnowledgeWrapper> getKnowledgeWrapper(List<KnowledgeBaseConfig> knowledgeBaseConfigs) {
        if (knowledgeBaseConfigs == null) {
            return List.of();
        }

        List<KnowledgeWrapper> objects = new ArrayList<>();
        for (KnowledgeBaseConfig knowledgeBaseConfig : knowledgeBaseConfigs) {
            if (!knowledgeBaseConfig.getEnabled()) {
                continue;
            }

            IKnowledge iKnowledge = KNOWLEDGE_MAP.get(knowledgeBaseConfig.getKbType());
            if (iKnowledge == null) {
                continue;
            }

            objects.add(KnowledgeWrapper
                    .builder()
                    .ragMode(knowledgeBaseConfig.getRagMode())
                    .knowledge(iKnowledge.build(knowledgeBaseConfig))
                    .retrievalConfig(knowledgeBaseConfig.getRetrievalConfig())
                    .build());
        }
        return objects;
    }

    public static IKnowledge getKnowledge(KbType kbType) {
        return KNOWLEDGE_MAP.get(kbType);
    }

    public static void register(IKnowledge knowledge) {
        KNOWLEDGE_MAP.put(knowledge.type(), knowledge);
    }

    public static void unregister(KbType type) {
        KNOWLEDGE_MAP.remove(type);
    }

    public static void clear() {
        KNOWLEDGE_MAP.clear();
    }
}
