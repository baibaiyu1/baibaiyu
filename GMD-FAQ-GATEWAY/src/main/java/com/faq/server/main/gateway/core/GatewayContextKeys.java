package com.gmd.faq.server.main.gateway.core;

/**
 * 描述：网关请求上下文键常量
 *
 **/
public class GatewayContextKeys {
    /** 访问日志实体 */
    public static final String ACCESS_LOG = "gmd.faq.gateway.access.log";
    /** 转换后的工作流入参列表 */
    public static final String PARAM_ITEMS = "gmd.faq.gateway.param.items";
    /** 鉴权通过的平台用户信息 */
    public static final String AUTH_USER = "gmd.faq.gateway.auth.user";

    /** 响应头 Server 标识 */
    public static final String SERVER_NAME = "GMD-FAQ-Gateway";
}
