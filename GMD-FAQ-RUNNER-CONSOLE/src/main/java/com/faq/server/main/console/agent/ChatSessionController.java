package com.gmd.faq.server.main.console.agent;

import com.gmd.faq.server.main.agent.lock.SessionLockManager;
import com.gmd.faq.server.main.agent.service.ChatArtifactService;
import com.gmd.faq.server.main.agent.service.ChatSessionService;
import com.gmd.faq.server.main.agent.service.SessionAccessGuard;
import com.gmd.faq.server.main.common.config.auth.ChatKeyAccess;
import com.gmd.faq.server.main.common.config.auth.PermNeed;
import com.gmd.faq.server.main.common.config.auth.SkAccess;
import com.gmd.faq.server.main.common.dto.ChatMessageAppendDTO;
import com.gmd.faq.server.main.common.dto.ChatSessionCreateDTO;
import com.gmd.faq.server.main.common.dto.ChatSessionQueryDTO;
import com.gmd.faq.server.main.common.entity.ChatArtifact;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.common.vo.ChatArtifactVO;
import com.gmd.faq.server.main.common.vo.ChatMessageVO;
import com.gmd.faq.server.main.common.vo.ChatMessagePageVO;
import com.gmd.faq.server.main.common.vo.ChatSessionVO;
import com.gmd.faq.server.main.common.vo.MessageBranchVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 聊天会话 Controller：创建会话、追加/重新生成消息、切换分支、回显当前对话、会话列表与详情
 *
 */
@RestController
@RequestMapping("/agent/chat/session")
@RequiredArgsConstructor
public class ChatSessionController {

    private final ChatSessionService chatSessionService;
    private final ChatArtifactService chatArtifactService;
    private final SessionLockManager sessionLockManager;
    private final SessionAccessGuard sessionAccessGuard;

    /**
     * 创建新会话（插入根消息并设置 current_message_id）
     */
    @SkAccess
    @ChatKeyAccess
    @PostMapping
    public R<ChatSessionVO> create(@RequestBody ChatSessionCreateDTO dto) {
        if (dto == null || dto.getAgentId() == null) {
            return R.fail("agentId 不能为空");
        }
        return R.data(chatSessionService.createSession(dto, UserUtils.getId()));
    }

    /**
     * 正常对话追加消息
     */
    @SkAccess
    @ChatKeyAccess
    @PostMapping("/{sessionId}/message")
    public R<ChatMessageVO> appendMessage(@PathVariable("sessionId") Long sessionId, @RequestBody ChatMessageAppendDTO dto) {
        ReentrantLock lock = sessionLockManager.getLock(sessionId);
        lock.lock();
        try {
            return R.data(chatSessionService.appendMessage(sessionId, dto));
        } finally {
            lock.unlock();
            sessionLockManager.cleanupIfUnused(sessionId, lock);
        }
    }

    /**
     * 重新生成（新分支，更新 current_message_id）
     */
    @SkAccess
    @ChatKeyAccess
    @PostMapping("/{sessionId}/regenerate")
    public R<ChatMessageVO> regenerate(@PathVariable("sessionId") Long sessionId, @RequestBody ChatMessageAppendDTO dto) {
        return R.data(chatSessionService.regenerateMessage(sessionId, dto));
    }

    /**
     * 切换历史分支（仅更新 current_message_id）
     */
    @SkAccess
    @ChatKeyAccess
    @PutMapping("/{sessionId}/current")
    public R<Void> switchCurrentMessage(@PathVariable("sessionId") Long sessionId, @RequestParam("messageId") Integer messageId) {
        chatSessionService.switchCurrentMessage(sessionId, messageId);
        return R.success("操作成功");
    }

    /**
     * 当前对话路径上的消息分支信息（同一父节点下的多个候选回复）
     * <p>
     * 供前端展示「上一版 / 下一版」并调用切换分支接口。
     */
    @SkAccess
    @ChatKeyAccess
    @GetMapping("/{sessionId}/message-branches")
    public R<List<MessageBranchVO>> messageBranches(@PathVariable("sessionId") Long sessionId) {
        return R.data(chatSessionService.listMessageBranches(sessionId));
    }

    /**
     * 回显当前完整对话（按 path 查消息链，按 depth 排序）
     */
    @SkAccess
    @ChatKeyAccess
    @GetMapping("/{sessionId}/messages/current")
    public R<List<ChatMessageVO>> getCurrentMessages(@PathVariable("sessionId") Long sessionId) {
        return R.data(chatSessionService.getCurrentMessages(sessionId));
    }

    /**
     * 分页加载当前对话消息（滚动加载历史）
     * 首次加载：不传 beforeDepth，返回最新 50 条
     * 加载更多：传入上一次返回的 nextBeforeDepth，返回更早的 50 条
     */
    @SkAccess
    @ChatKeyAccess
    @GetMapping("/{sessionId}/messages/paged")
    public R<ChatMessagePageVO> getCurrentMessagesPaged(
            @PathVariable("sessionId") Long sessionId,
            @RequestParam(value = "beforeDepth", required = false) Integer beforeDepth,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return R.data(chatSessionService.getCurrentMessagesPaged(sessionId, beforeDepth, size));
    }

    /**
     * 会话产物列表（智能体实际写出/修改的工作空间文件）
     *
     * <p>与相邻接口一致，走 @SkAccess / @ChatKeyAccess，不额外设权限点。
     */
    @SkAccess
    @ChatKeyAccess
    @GetMapping("/{sessionId}/artifacts")
    public R<List<ChatArtifactVO>> getArtifacts(@PathVariable("sessionId") Long sessionId) {
        sessionAccessGuard.requireRead(sessionId);
        return R.data(chatArtifactService.listBySession(sessionId).stream()
                .map(this::toArtifactVO)
                .toList());
    }

    /**
     * 实体转 VO（不下发 tenantId）
     */
    private ChatArtifactVO toArtifactVO(ChatArtifact artifact) {
        ChatArtifactVO vo = new ChatArtifactVO();
        vo.setId(artifact.getId());
        vo.setSessionId(artifact.getSessionId());
        vo.setFilePath(artifact.getFilePath());
        vo.setFileName(artifact.getFileName());
        vo.setExtension(artifact.getExtension());
        vo.setSizeBytes(artifact.getSizeBytes());
        vo.setSourceTool(artifact.getSourceTool());
        vo.setCreateTime(artifact.getCreateTime());
        return vo;
    }

    /**
     * 会话列表（未删除，默认当前用户，可按 agentId 筛选）
     * <p>
     * 归属固定为当前登录用户：DTO 上的 {@code userId} 已被服务层忽略（避免越权列他人会话），
     * 跨用户查看只能走 {@code guestOnly=true}，该分支要求 {@code agent:chat:guest:manage} 权限。
     */
    @SkAccess
    @ChatKeyAccess
    @GetMapping("/list")
    public R<List<ChatSessionVO>> list(ChatSessionQueryDTO query) {
        return R.data(chatSessionService.listSessions(query));
    }

    /**
     * 分页查询会话（支持 isPinned 筛选；guestOnly=true 时只看游客会话，需专门权限）
     */
    @SkAccess
    @ChatKeyAccess
    @GetMapping("/page")
    public R<IPage<ChatSessionVO>> page(PageParams pageParams, ChatSessionQueryDTO query) {
        return R.data(chatSessionService.pageSessions(pageParams, query));
    }

    /** 会话文件管理页可选择的查询范围。 */
    @GetMapping("/access")
    public R<Map<String, Boolean>> access() {
        return R.data(chatSessionService.accessScopes());
    }

    /**
     * 会话详情
     */
    @SkAccess
    @ChatKeyAccess
    @GetMapping("/{id}")
    public R<ChatSessionVO> detail(@PathVariable("id") Long id) {
        return R.data(chatSessionService.getSessionDetail(id));
    }

    /**
     * 置顶会话
     */
    @SkAccess
    @ChatKeyAccess
    @PutMapping("/{id}/pin")
    public R<Void> pin(@PathVariable("id") Long id) {
        chatSessionService.pinSession(id);
        return R.success("操作成功");
    }

    /**
     * 取消置顶会话
     */
    @SkAccess
    @ChatKeyAccess
    @PutMapping("/{id}/unpin")
    public R<Void> unpin(@PathVariable("id") Long id) {
        chatSessionService.unpinSession(id);
        return R.success("操作成功");
    }

    /**
     * 更新会话标题
     */
    @SkAccess
    @ChatKeyAccess
    @PutMapping("/{id}/title")
    public R<Void> updateTitle(@PathVariable("id") Long id, @RequestParam("title") String title) {
        chatSessionService.updateTitle(id, title);
        return R.success("操作成功");
    }

    /**
     * 更新当前消息内容（后端通过 session 的 current_message_id 定位，调用方无需传 messageId）
     */
    @SkAccess
    @ChatKeyAccess
    @PutMapping("/{sessionId}/current-message/content")
    public R<Void> updateCurrentMessageContent(
            @PathVariable("sessionId") Long sessionId,
            @RequestBody Map<String, String> body) {
        String content = body.get("content");
        if (content == null) {
            return R.fail("content 不能为空");
        }
        chatSessionService.updateCurrentMessageContent(sessionId, content);
        return R.success("操作成功");
    }

    /**
     * 删除会话
     */
    @SkAccess
    @ChatKeyAccess
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable("id") Long id) {
        chatSessionService.deleteSession(id);
        return R.success("操作成功");
    }

    /**
     * 一键清空对话记录：删除指定智能体下当前登录用户的全部会话
     * <p>
     * 只作用于调用者自己的会话（服务层按当前登录账号过滤），管理员身份也不放宽这条边界。
     * 运行中的轮次由调用方先调运行时的 {@code /agui/stop/{threadId}} 打断，本接口不做打断。
     */
    @SkAccess
    @ChatKeyAccess
    @DeleteMapping("/agent/{agentId}")
    public R<Map<String, Object>> clearByAgent(@PathVariable("agentId") Long agentId) {
        int deleted = chatSessionService.deleteSessionsByAgent(agentId, TenantUtils.getCurrentTenantId());
        return R.data(Map.of("deleted", deleted));
    }

    /**
     * 清空单条会话的对话记录：消息/分支/子智能体轨迹清空，会话本身保留并可继续对话
     */
    @SkAccess
    @ChatKeyAccess
    @DeleteMapping("/{id}/messages")
    public R<Map<String, Object>> clearMessages(@PathVariable("id") Long id) {
        int deleted = chatSessionService.clearSessionMessages(id);
        return R.data(Map.of("deleted", deleted));
    }

    // ==================== 游客会话（分享 chatKey 链接产生） ====================
    // 游客身份是分享链接每次访问现场生成的随机 userId，在 gmd_faq_account 中不存在，
    // 因此这类会话不会出现在任何登录用户的列表里，其工作空间目录会长期占盘。
    // 下面两个接口是唯一的清理入口，统一要求 agent:chat:guest:manage（V21 迁移种的权限点）。

    /**
     * 删除单条游客会话（连带消息、轨迹与工作空间目录）
     * <p>
     * 服务层会二次确认该会话确实是游客会话：传入普通用户的会话 ID 会被拒绝，
     * 避免这个接口变成「管理员可删任意人的会话」。
     */
    @PermNeed({"agent:chat:guest:manage"})
    @DeleteMapping("/guest/{id}")
    public R<Map<String, Object>> deleteGuest(@PathVariable("id") Long id) {
        boolean deleted = chatSessionService.deleteGuestSession(id);
        return R.data(Map.of("deleted", deleted ? 1 : 0));
    }

    /**
     * 批量清理游客会话：可按智能体限定，不传 agentId 表示清理当前租户下全部游客会话
     */
    @PermNeed({"agent:chat:guest:manage"})
    @DeleteMapping("/guest")
    public R<Map<String, Object>> clearGuestSessions(
            @RequestParam(value = "agentId", required = false) Long agentId) {
        int deleted = chatSessionService.deleteGuestSessions(agentId, TenantUtils.getCurrentTenantId());
        return R.data(Map.of("deleted", deleted));
    }
}

