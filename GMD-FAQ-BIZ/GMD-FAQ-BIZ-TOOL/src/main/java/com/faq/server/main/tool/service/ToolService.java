package com.gmd.faq.server.main.tool.service;

import com.gmd.faq.server.main.common.entity.ToolConfig;
import com.gmd.faq.server.main.common.wrapper.ToolInfoWrapper;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 工具Service
 *
 */
public interface ToolService extends IService<ToolConfig> {
    Boolean deleteTools(List<Long> ids);

    /**
     * 同步内置工具到数据库（按租户作用域分配）
     *
     * @param toolInfos 内置工具信息列表（含 Scope 声明）
     */
    void SyncConfigToDatabase(List<ToolInfoWrapper> toolInfos);

    List<Object> usedWithAgent(List<Long> ids);

    /**
     * 获取所有分类
     *
     * @return 分类列表
     */
    List<String> listCategories();

    Boolean doUpdate(ToolConfig toolConfig);
}
