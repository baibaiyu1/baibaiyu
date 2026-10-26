package com.gmd.faq.server.main.common.enums;

/**
 * 描述：WebSocket 消息类型
 * <p>
 * 原 {@code ACCOUNT_ROLE_CHANGE}（切换组织后通知其他标签页刷新身份）已随多组织下线删除。
 *
 **/
public enum WsMessageType {
    /**
     * 服务端向客户端发送客户端在线验证
     */
    PING,
    /**
     * 户端向服务端响应在连接正常的
     */
    PONG,
    /**
     * 账号
     */
    CLIENT,
    /**
     * 工作区文件变化
     */
    WORKSPACE_FILE_CHANGE,
    /**
     * 对话产物生成（智能体实际写出/修改了工作空间文件）
     */
    ARTIFACT_CREATED
}
