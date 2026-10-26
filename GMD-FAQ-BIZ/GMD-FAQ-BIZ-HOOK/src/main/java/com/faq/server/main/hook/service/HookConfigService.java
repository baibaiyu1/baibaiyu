package com.gmd.faq.server.main.hook.service;

import com.gmd.faq.server.main.common.entity.HookConfig;
import com.gmd.faq.server.main.common.wrapper.HookConfigWrapper;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * Hook配置Service
 *
 */
public interface HookConfigService extends IService<HookConfig> {
    /**
     * 同步内置Hook到数据库（按租户作用域分配）
     *
     * @param configWrappers 内置Hook信息列表（含 Scope 声明）
     */
    void SyncConfigToDatabase(List<HookConfigWrapper> configWrappers);
    List<Object> usedWithAgent(List<Long> ids);
    boolean deleteByIds(List<Long> ids);

    /**
     * 更新Hook配置并触发关联智能体重新注册
     *
     * @param entity Hook配置
     * @return 是否成功
     */
    boolean doUpdate(HookConfig entity);
}
