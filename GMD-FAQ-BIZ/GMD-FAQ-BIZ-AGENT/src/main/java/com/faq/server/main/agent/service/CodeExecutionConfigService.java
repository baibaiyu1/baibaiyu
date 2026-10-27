package com.gmd.faq.server.main.agent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gmd.faq.server.main.common.entity.CodeExecutionConfig;

import java.util.List;

/**
 * 描述：CodeExecutionConfigService
 *
 **/
public interface CodeExecutionConfigService extends IService<CodeExecutionConfig> {
    /**
     * 查询被哪些Agent使用
     *
     * @param ids 配置ID列表
     * @return Agent名称列表
     */
    List<Object> usedWithAgent(List<Long> ids);

    boolean doUpdate(CodeExecutionConfig config);

    boolean deleteConfigs(List<Long> ids);
}
