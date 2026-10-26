package com.gmd.faq.server.main.common.consts;

/**
 * 描述：RedisChannelTopic
 *
 **/
public class RedisChannelTopic {
    public static final String AGENT_REREGISTER_CHANNEL = "gmd-faq:agent:cluster:reRegister";
    public static final String AGENT_UNREGISTER_CHANNEL = "gmd-faq:agent:cluster:unRegister";

    public static final String AGENT_CONSTRUCTOR_REREGISTER_CHANNEL = "gmd-faq:agent:cluster:constructor:reRegister";
    public static final String AGENT_CONSTRUCTOR_UNREGISTER_CHANNEL = "gmd-faq:agent:cluster:constructor:unRegister";

    public static final String SK_SYNC_CHANNEL = "gmd-faq:sk:sync";
    public static final String JOB_CLUSTER_CONTROL = "gmd-faq:job:cluster:control";
    public static final String WS_CHANNEL_PATTERN = "gmd-faq:ws:cluster:*";

    /** 系统参数变更频道（任一节点增/改 Params 后广播，订阅者按 paramKey 过滤处理） */
    public static final String PARAM_CHANGE_CHANNEL = "gmd-faq:params:change";

    /** 分布式锁：内置工具同步到数据库 */
    public static final String LOCK_TOOLS_SYNC = "gmd-faq:lock:tools:sync";
    /** 分布式锁：内置Hook同步到数据库 */
    public static final String LOCK_HOOKS_SYNC = "gmd-faq:lock:hooks:sync";
    /** 分布式锁：技能包文件初始化同步 */
    public static final String LOCK_SKILL_INIT = "gmd-faq:lock:skill:init";
    /** 分布式锁：聊天消息月度归档 */
    public static final String LOCK_MESSAGE_ARCHIVE = "gmd-faq:lock:message:archive";

    /** 技能文件同步频道（通知 runner-file 节点增量同步） */
    public static final String SKILL_FILE_SYNC_CHANNEL = "gmd-faq:skill:file:sync";

    /** 网关集群同步频道（应用/API/客户端变更广播，网关节点按消息类型刷新本地路由状态） */
    public static final String GATEWAY_SYNC_CHANNEL = "gmd-faq:gateway:cluster:sync";
    /** 工作流发布/下线频道（发布、回滚、删除后广播工作流ID，订阅者刷新编译缓存并重注册关联API） */
    public static final String WORKFLOW_PUBLISHED_CHANNEL = "gmd-faq:workflow:published";

    /** Agent 会话缓存版本变更通知频道。 */
    public static final String AGENT_SESSION_VERSION_CHANNEL = "gmd-faq:agent:session:version";

    /** 工作空间快照同步通知频道。 */
    public static final String WORKSPACE_SYNC_CHANNEL = "gmd-faq:workspace:sync";
    public static final String WORKSPACE_DELETE_CHANNEL = "gmd-faq:workspace:delete";
}
