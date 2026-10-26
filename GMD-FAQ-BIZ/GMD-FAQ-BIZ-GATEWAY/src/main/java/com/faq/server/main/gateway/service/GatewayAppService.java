package com.gmd.faq.server.main.gateway.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gmd.faq.server.main.gateway.entity.GatewayApp;

import java.util.List;

/**
 * 描述：网关应用服务
 *
 **/
public interface GatewayAppService extends IService<GatewayApp> {

    /**
     * 新建应用
     */
    boolean saveApp(GatewayApp app);

    /**
     * 更新应用
     */
    boolean updateApp(GatewayApp app);

    /**
     * 批量删除应用（存在API时拒绝删除）
     */
    boolean deleteApps(List<Long> ids);

    /**
     * 应用上下线
     *
     * @param id 应用ID
     * @param v  1上线、0下线
     */
    boolean updateOnline(Long id, Integer v);
}
