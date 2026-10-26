package com.gmd.faq.server.main.agent.service;

import com.gmd.faq.server.main.common.dto.ChatMessageAppendDTO;
import com.gmd.faq.server.main.common.dto.ChatSessionCreateDTO;
import com.gmd.faq.server.main.common.dto.ChatSessionQueryDTO;
import com.gmd.faq.server.main.common.entity.ChatSession;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.vo.ChatMessageVO;
import com.gmd.faq.server.main.common.vo.ChatMessagePageVO;
import com.gmd.faq.server.main.common.vo.ChatSessionVO;
import com.gmd.faq.server.main.common.vo.MessageBranchVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 聊天会话 Service
 *
 */
public interface ChatSessionService extends IService<ChatSession> {

    /**
     * 创建新会话：插入根消息并设置 current_message_id
     *
     * @param dto 创建参数（agentId、title）
     * @param userId 用户ID
     * @return 会话 VO
     */
    ChatSessionVO createSession(ChatSessionCreateDTO dto, Long userId);

    /**
     * 正常对话追加：在 current_message_id 后插入新消息并更新 current_message_id
     *
     * @param sessionId 会话 ID
     * @param dto       消息内容（role、content）
     * @return 新消息 VO
     */
    ChatMessageVO appendMessage(Long sessionId, ChatMessageAppendDTO dto);

    /**
     * 重新生成：以当前消息为父节点插入新消息（新分支），并更新 current_message_id
     *
     * @param sessionId 会话 ID
     * @param dto       消息内容（role、content）
     * @return 新消息 VO
     */
    ChatMessageVO regenerateMessage(Long sessionId, ChatMessageAppendDTO dto);

    /**
     * 切换历史分支：仅更新 current_message_id
     *
     * @param sessionId 会话 ID
     * @param messageId 目标消息 ID
     */
    void switchCurrentMessage(Long sessionId, Integer messageId);

    /**
     * 回显当前完整对话：根据 current_message_id 取 path，按 path 的 id 列表查询并按 depth 排序
     *
     * @param sessionId 会话 ID
     * @return 当前路径上的消息列表（按深度升序）
     */
    List<ChatMessageVO> getCurrentMessages(Long sessionId);

    /**
     * 分页加载当前对话消息：首次加载最新 size 条，后续按 beforeDepth 向前加载
     *
     * @param sessionId    会话 ID
     * @param beforeDepth  游标：加载此 depth 之前的消息，null 表示首次加载（取最新）
     * @param size         每页大小
     * @return 分页消息结果
     */
    ChatMessagePageVO getCurrentMessagesPaged(Long sessionId, Integer beforeDepth, int size);

    /**
     * 查询当前对话路径上的消息分支情况（同一父节点下的多个候选回复）
     * <p>
     * 仅返回兄弟数大于 1 的消息，供前端展示「上一版 / 下一版」并切换分支。
     *
     * @param sessionId 会话 ID
     * @return 分支信息列表（无分支时返回空列表）
     */
    List<MessageBranchVO> listMessageBranches(Long sessionId);

    /**
     * 分页或列表查询会话（未删除，可按 userId、agentId 筛选）
     *
     * @param query 查询条件
     * @return 会话 VO 列表
     */
    List<ChatSessionVO> listSessions(ChatSessionQueryDTO query);

    /**
     * 分页查询会话（支持 isPinned 筛选）
     *
     * @param query 分页查询条件
     * @return 分页会话 VO
     */
    IPage<ChatSessionVO> pageSessions(PageParams pageParams, ChatSessionQueryDTO query);

    /** 当前身份可选的会话管理范围。 */
    java.util.Map<String, Boolean> accessScopes();

    /**
     * 会话详情
     *
     * @param id 会话 ID
     * @return 会话 VO，不存在返回 null
     */
    ChatSessionVO getSessionDetail(Long id);

    /**
     * 置顶会话
     *
     * @param id 会话 ID
     */
    void pinSession(Long id);

    /**
     * 取消置顶会话
     *
     * @param id 会话 ID
     */
    void unpinSession(Long id);

    /**
     * 更新会话标题
     *
     * @param id    会话 ID
     * @param title 新标题
     */
    void updateTitle(Long id, String title);

    /**
     * 更新当前消息内容（通过 session 的 current_message_id 定位，用于持久化 UIP 提交数据等场景）
     *
     * @param sessionId 会话 ID
     * @param content   新内容
     */
    void updateCurrentMessageContent(Long sessionId, String content);

    /**
     * 删除会话（物理删除会话及其消息）
     *
     * @param id 会话 ID
     */
    void deleteSession(Long id);

    /**
     * 删除指定智能体下当前用户的全部会话（一键清空对话记录）
     * <p>
     * 逐条复用 {@link #deleteSession(Long)} 的清理口径：归档分表消息、主表消息、子智能体轨迹、
     * AgentScope 会话与工作空间目录。用户未登录时返回 0、不做任何删除。
     *
     * @param agentId  智能体 ID
     * @param tenantId 租户 ID，可为 null（不额外过滤租户）
     * @return 实际删除的会话数量
     */
    int deleteSessionsByAgent(Long agentId, Long tenantId);

    /**
     * 清空单条会话的对话记录：消息、消息分支与子智能体轨迹，并重置会话游标，保留会话本身
     *
     * @param sessionId 会话 ID
     * @return 删除的消息条数
     */
    int clearSessionMessages(Long sessionId);

    // ==================== 游客会话（分享链接产生） ====================

    /**
     * 判断给定会话是否为游客会话
     * <p>
     * 判定口径：会话的 {@code user_id} 在 {@code gmd_faq_account} 中**没有**对应账号。
     * 分享链接每次访问都由 {@code AccountServiceImpl#chatKeyToken} 用随机 ID 现场造
     * 一个身份，这个身份从不落账号表，因此「不在账号表」就是游客的可靠特征。
     *
     * @param sessionIds 待判定的会话 ID 集合，为空返回空集合
     * @return 其中的游客会话 ID 集合
     */
    Set<Long> findGuestSessionIds(Collection<Long> sessionIds);

    /**
     * 删除指定会话，跳过归属人校验（调用方必须已确认是游客会话并完成权限校验）
     * <p>
     * 游客会话的 {@code user_id} 是随机值，正常登录用户永远不可能等于它，
     * 因此不能复用按登录用户校验归属的 {@link #deleteSession(Long)}。
     *
     * @param sessionId 会话 ID
     * @return 是否删除了记录（行已不在返回 false）
     */
    boolean deleteGuestSession(Long sessionId);

    /**
     * 批量删除游客会话（可按智能体限定），连带清理消息、轨迹与工作空间目录
     *
     * @param agentId  智能体 ID，为 null 表示不限
     * @param tenantId 租户 ID，为 null 表示不额外过滤
     * @return 实际删除的会话数量
     */
    int deleteGuestSessions(Long agentId, Long tenantId);

    /**
     * 清理长期无活动的游客会话（供定时任务调用）
     * <p>
     * 不做权限校验 —— 调用方是系统定时任务，没有登录上下文；
     * 对外暴露的接口一律走 {@link #deleteGuestSessions(Long, Long)}。
     *
     * @param cutoff  最后活动时间早于此值的游客会话会被删除
     * @param maxCount 单次最多删除的会话数，用于给定时任务一个可预期的执行上限
     * @return 实际删除的会话数量
     */
    int deleteInactiveGuestSessions(LocalDateTime cutoff, int maxCount);
}

