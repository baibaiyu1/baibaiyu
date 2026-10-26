package com.gmd.faq.server.main.prompt.service;

import com.gmd.faq.server.main.common.entity.SystemPromptTemplate;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 系统提示词模板Service
 *
 */
public interface SystemPromptTemplateService extends IService<SystemPromptTemplate> {
    List<Object> usedWithAgent(List<Long> ids);

    /**
     * 获取所有分类
     *
     * @return 分类列表
     */
    List<String> listCategories();

    /**
     * 删除提示词模板，并使关联智能体保留提示词快照、脱离模板及重新注册
     *
     * @param ids 模板ID列表
     * @return 是否成功
     */
    boolean deleteByIds(List<Long> ids);

    /**
     * 更新提示词模板；内容变化时同步跟随的智能体并触发重新注册
     *
     * @param entity 提示词模板
     * @return 是否成功
     */
    boolean doUpdate(SystemPromptTemplate entity);
}
