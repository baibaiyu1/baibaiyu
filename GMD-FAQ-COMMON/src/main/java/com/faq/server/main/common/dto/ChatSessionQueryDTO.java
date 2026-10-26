package com.gmd.faq.server.main.common.dto;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * 会话列表查询 DTO（默认本人；管理页可显式选择负责部门或全部）
 *
 */
@Getter
@Setter
@EqualsAndHashCode(callSuper = true)
public class ChatSessionQueryDTO extends PageParams implements SerializableEnable {
    private Long userId;
    private Long agentId;
    private Boolean isPinned;

    /**
     * 只看游客会话（分享 chatKey 链接产生、userId 不在 account 表中的会话）
     * <p>
     * 默认 null/false，按 scope 指定的范围过滤。置 true 时服务层会额外校验
     * {@code agent:chat:guest:manage} 权限点，普通用户拿不到该权限，
     * 避免借这个开关绕过「会话按归属人隔离」。
     */
    private Boolean guestOnly;

    /** mine（默认）/ team（所负责部门）/ all（管理员）。 */
    private String scope;
}
