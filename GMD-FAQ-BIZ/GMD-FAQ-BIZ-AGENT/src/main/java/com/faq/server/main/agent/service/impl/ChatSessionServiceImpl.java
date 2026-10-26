package com.gmd.faq.server.main.agent.service.impl;

import com.gmd.faq.server.main.agent.mapper.AgentScopeSessionMapper;
import com.gmd.faq.server.main.agent.mapper.ChatSessionMapper;
import com.gmd.faq.server.main.agent.service.AgentDefinitionService;
import com.gmd.faq.server.main.agent.service.ChatMessageService;
import com.gmd.faq.server.main.agent.service.ChatSessionService;
import com.gmd.faq.server.main.agent.service.SessionAccessGuard;
import com.gmd.faq.server.main.agent.service.SubAgentTraceHistoryService;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.cluster.core.MessagePublisher;
import com.gmd.faq.server.main.common.dto.ChatMessageAppendDTO;
import com.gmd.faq.server.main.common.dto.ChatSessionCreateDTO;
import com.gmd.faq.server.main.common.dto.ChatSessionQueryDTO;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.ChatMessage;
import com.gmd.faq.server.main.common.entity.ChatSession;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.util.BeanUtils;
import com.gmd.faq.server.main.common.util.FolderUtils;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.common.vo.ChatMessageVO;
import com.gmd.faq.server.main.common.vo.ChatMessagePageVO;
import com.gmd.faq.server.main.common.vo.ChatSessionVO;
import com.gmd.faq.server.main.common.vo.MessageBranchVO;
import com.gmd.faq.server.main.common.router.MessageTableRouter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gmd.faq.server.main.common.mp.support.MP;
import io.agentscope.spring.boot.agui.common.ThreadSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 聊天会话 Service 实现：新会话插根消息并设 current_message_id；追加/重新生成插新消息并更新 current_message_id；切换分支仅更新 current_message_id；回显按 path 查消息链。
 *
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatSessionServiceImpl extends ServiceImpl<ChatSessionMapper, ChatSession> implements ChatSessionService {

    /**
     * 游客会话管理权限点
     * <p>
     * 与 V21 迁移里 {@code gmd_faq_menu.id = 3112} 的 perms 保持一致；
     * 默认只授权给 superadmin / system_admin，不下发给 editor / viewer。
     */
    private static final String PERM_GUEST_MANAGE = "agent:chat:guest:manage";

    private final ChatMessageService chatMessageService;
    private final MessageTableRouter messageTableRouter;
    private final ThreadSessionManager sessionManager;
    private final AgentScopeSessionMapper agentScopeSessionMapper;
    private final AgentDefinitionService agentDefinitionService;
    private final SubAgentTraceHistoryService subAgentTraceHistoryService;
    private final JdbcTemplate jdbcTemplate;
    private final SessionAccessGuard sessionAccessGuard;
    private final MessagePublisher messagePublisher;
    private final ResourceVisibilityService resourceVisibilityService;
    private final com.gmd.faq.server.main.agent.service.AgentInstructionService instructionService;
    private final com.gmd.faq.server.main.agent.service.AgentTelemetryService telemetryService;
    private final com.gmd.faq.server.main.agent.service.AgentConversationQueue conversationQueue;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatSessionVO createSession(ChatSessionCreateDTO dto, Long userId) {
        if (userId == null || userId == 0L) {
            throw new RuntimeException("用户未登录");
        }
        if (dto.getAgentId() == null ) {
            throw new RuntimeException("agentId 不能为空");
        }
        if (UserUtils.isChatKey() && !Objects.equals(dto.getAgentId(), UserUtils.getBoundAgentId())) {
            throw new RuntimeException("分享链接无权使用该智能体");
        }
        if (UserUtils.getUserDetail() == null) {
            resourceVisibilityService.assertUsableForAccount("AGENT", dto.getAgentId(), userId);
        } else {
            resourceVisibilityService.assertUsable("AGENT", dto.getAgentId());
        }

        // 校验智能体的合法性
        AgentDefinition agentDefinition = agentDefinitionService.getOne(
                new LambdaQueryWrapper<AgentDefinition>()
                        .eq(AgentDefinition::getId, dto.getAgentId())
                        .eq(AgentDefinition::getEnabled, true),
                false);

        if (agentDefinition == null) {
            throw new RuntimeException("智能体不存在或已禁用");
        }
        if (!Boolean.TRUE.equals(agentDefinition.getPublished())) {
            boolean realAccount = !UserUtils.isChatKey() && !UserUtils.isSk();
            boolean ownerOrAdmin = com.gmd.faq.server.main.common.util.ResourceOwnershipSupport.isManageable(agentDefinition)
                    || (UserUtils.getUserDetail() != null && UserUtils.isAdmin());
            if (!realAccount || !ownerOrAdmin) {
                throw new RuntimeException("智能体尚未发布，无法创建会话");
            }
        }

        ChatSession session = new ChatSession();
        session.setUserId(userId);
        if (UserUtils.getUserDetail() != null && Objects.equals(UserUtils.getId(), userId)) {
            session.setOwnerDeptId(UserUtils.getUserDetail().getDeptId());
        } else {
            List<Long> departments = jdbcTemplate.queryForList(
                    "SELECT dept_id FROM " + TableConst.ACCOUNT + " WHERE id = ?", Long.class, userId);
            session.setOwnerDeptId(departments.isEmpty() ? null : departments.getFirst());
        }
        session.setAgentId(dto.getAgentId());
        session.setTitle(dto.getTitle() != null ? dto.getTitle() : "新对话");

        save(session);

        ChatMessage root = new ChatMessage();
        root.setSessionId(session.getId());
        root.setRole("system");
        root.setContent("");
        root.setParentId(null);
        chatMessageService.save(root);
        root.setPath(String.valueOf(root.getId()));
        root.setDepth(0);
        chatMessageService.updateById(root);

        session.setCurrentMessageId(root.getId());
        updateById(session);

        if (dto.getInitWorkspace() != null && dto.getInitWorkspace()) {
            FolderUtils.mkdirsByRelativePath(SysConst.getWorkspacePath() + "/" + session.getId());
        }

        return toSessionVO(session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageVO appendMessage(Long sessionId, ChatMessageAppendDTO dto) {
        ChatSession session = sessionAccessGuard.requireActiveWrite(sessionId);
        if (session.getMessageTable() == null || session.getMessageTable().isEmpty()) {
            ChatMessage parent = getMessageBy(session.getCurrentMessageId(), sessionId);
            return saveNewMessageAndMoveCursor(session, parent, dto.getRole(), dto.getContent());
        }
        throw new RuntimeException("当前会话已经归档，不支持继续对话");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageVO regenerateMessage(Long sessionId, ChatMessageAppendDTO dto) {
        ChatSession session = sessionAccessGuard.requireActiveWrite(sessionId);
        ChatMessage parent = getMessageBy(session.getCurrentMessageId(), sessionId);
        return saveNewMessageAndMoveCursor(session, parent, dto.getRole(), dto.getContent());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void switchCurrentMessage(Long sessionId, Integer messageId) {
        ChatSession session = sessionAccessGuard.requireActiveWrite(sessionId);
        getMessageBy(messageId, sessionId);
        session.setCurrentMessageId(messageId);
        updateById(session);
    }

    @Override
    public List<ChatMessageVO> getCurrentMessages(Long sessionId) {
        ChatSession session = sessionAccessGuard.requireRead(sessionId);
        Integer curId = session.getCurrentMessageId();
        if (curId == null) {
            return new ArrayList<>();
        }
        // 根据 messageTable 决定从哪张表查 current message
        ChatMessage cur;
        if (isArchived(session)) {
            cur = messageTableRouter.getById(curId, session.getMessageTable());
        } else {
            cur = chatMessageService.getById(curId);
        }
        if (cur == null || !cur.getSessionId().equals(session.getId())) {
            return new ArrayList<>();
        }
        String path = cur.getPath();
        if (path == null || path.isEmpty()) {
            return new ArrayList<>();
        }
        List<Integer> ids = Arrays.stream(path.split("/"))
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            ids.add(curId);
        }
        // 仅已归档会话才传归档表名；message_table 为 ARCHIVE_FAILED 等失败标记时必须回落主表，
        // 否则 MessageTableRouter 会抛“非法的归档表名”异常
        List<ChatMessage> list = chatMessageService.listByIdsOrderByDepth(ids, isArchived(session) ? session.getMessageTable() : null);
        List<ChatMessageVO> result = BeanUtils.copyList(list, ChatMessageVO.class);
        hydrateSubAgentRuns(sessionId, result);
        return result;
    }
    @Override
    public List<MessageBranchVO> listMessageBranches(Long sessionId) {
        ChatSession session = sessionAccessGuard.requireRead(sessionId);
        List<ChatMessageVO> path = getCurrentMessages(sessionId);
        if (path.isEmpty()) {
            return new ArrayList<>();
        }
        // 归档会话（按月分表）的消息不在本接口支持范围内：表内无按父节点查询的服务端方法
        if (isArchived(session)) {
            return new ArrayList<>();
        }

        // 路径上所有消息的父节点，一次性查出这些父节点下的全部子消息（即兄弟分支）
        Set<Integer> parentIds = path.stream()
                .map(ChatMessageVO::getParentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (parentIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<ChatMessage> siblings = chatMessageService.list(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .in(ChatMessage::getParentId, parentIds));

        Map<Integer, List<Integer>> childrenByParent = new HashMap<>();
        for (ChatMessage item : siblings) {
            childrenByParent.computeIfAbsent(item.getParentId(), key -> new ArrayList<>()).add(item.getId());
        }

        List<MessageBranchVO> result = new ArrayList<>();
        for (ChatMessageVO vo : path) {
            List<Integer> children = childrenByParent.get(vo.getParentId());
            if (children == null || children.size() < 2) {
                continue;
            }
            children.sort(Integer::compareTo);
            MessageBranchVO branch = new MessageBranchVO();
            branch.setMessageId(vo.getId());
            branch.setParentId(vo.getParentId());
            branch.setSiblingIds(children);
            branch.setTotal(children.size());
            branch.setCurrentIndex(children.indexOf(vo.getId()));
            result.add(branch);
        }
        return result;
    }

    @Override
    public ChatMessagePageVO getCurrentMessagesPaged(Long sessionId, Integer beforeDepth, int size) {
        // 1. 解析当前路径上的所有消息 ID
        ChatSession session = sessionAccessGuard.requireRead(sessionId);
        ChatMessagePageVO result = new ChatMessagePageVO();
        if (session.getCurrentMessageId() == null) {
            result.setMessages(new ArrayList<>());
            result.setHasMore(false);
            return result;
        }
        // 根据 messageTable 决定从哪张表查 current message
        ChatMessage cur;
        if (isArchived(session)) {
            cur = messageTableRouter.getById(session.getCurrentMessageId(), session.getMessageTable());
        } else {
            cur = chatMessageService.getById(session.getCurrentMessageId());
        }
        if (cur == null || !cur.getSessionId().equals(session.getId()) || cur.getPath() == null || cur.getPath().isEmpty()) {
            result.setMessages(new ArrayList<>());
            result.setHasMore(false);
            return result;
        }
        List<Integer> ids = Arrays.stream(cur.getPath().split("/"))
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            ids.add(session.getCurrentMessageId());
        }

        // 2. 查询该路径上全部消息并按 depth 升序
        // 与 getCurrentMessages 一致：仅已归档会话才传归档表名，失败标记回落主表
        List<ChatMessage> allMessages = chatMessageService.listByIdsOrderByDepth(ids, isArchived(session) ? session.getMessageTable() : null);

        // 3. 根据游标切片
        List<ChatMessage> page;
        if (beforeDepth == null) {
            // 首次加载：取末尾 size 条
            int from = Math.max(0, allMessages.size() - size);
            page = allMessages.subList(from, allMessages.size());
        } else {
            // 加载 beforeDepth 之前的消息：取 depth < beforeDepth 的末尾 size 条
            List<ChatMessage> candidates = allMessages.stream()
                    .filter(m -> m.getDepth() < beforeDepth)
                    .collect(Collectors.toList());
            int from = Math.max(0, candidates.size() - size);
            page = candidates.subList(from, candidates.size());
        }

        // 4. 判断是否还有更早的消息
        boolean hasMore;
        if (page.isEmpty()) {
            hasMore = false;
        } else {
            int earliestDepth = page.getFirst().getDepth();
            hasMore = allMessages.stream().anyMatch(m -> m.getDepth() < earliestDepth);
        }

        List<ChatMessageVO> pageMessages = BeanUtils.copyList(page, ChatMessageVO.class);
        hydrateSubAgentRuns(sessionId, pageMessages);
        result.setMessages(pageMessages);
        result.setHasMore(hasMore);
        result.setNextBeforeDepth(page.isEmpty() ? null : page.getFirst().getDepth());
        return result;
    }

    private void hydrateSubAgentRuns(Long sessionId, List<ChatMessageVO> messages) {
        if (messages == null || messages.isEmpty()) return;
        List<String> invocationIds = messages.stream()
                .filter(message -> "subagent".equals(message.getRole()))
                .map(message -> {
                    try {
                        var node = JsonUtils.parse(message.getContent());
                        return node == null ? null : JsonUtils.getStringValue(node, "invocationId", false);
                    } catch (Exception ignored) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        var runs = subAgentTraceHistoryService.loadByInvocations(sessionId, invocationIds);
        messages.stream()
                .filter(message -> "subagent".equals(message.getRole()))
                .forEach(message -> {
                    try {
                        var node = JsonUtils.parse(message.getContent());
                        String invocationId = node == null ? null : JsonUtils.getStringValue(node, "invocationId", false);
                        message.setSubAgentRun(invocationId == null ? null : runs.get(invocationId));
                    } catch (Exception ignored) {
                        // A malformed legacy anchor remains visible as a normal system card.
                    }
                });
    }



    @Override
    public List<ChatSessionVO> listSessions(ChatSessionQueryDTO query) {
        List<ChatSession> sessions = list(buildSessionWrapper(query));
        List<ChatSessionVO> result = sessions.stream().map(this::toSessionVO).toList();
        fillGuestFlag(result);
        return result;
    }

    @Override
    public IPage<ChatSessionVO> pageSessions(PageParams pageParams, ChatSessionQueryDTO query) {
        IPage<ChatSession> page = page(MP.getPage(pageParams), buildSessionWrapper(query));
        IPage<ChatSessionVO> result = BeanUtils.copyPage(page, ChatSessionVO.class);
        if (result != null && result.getRecords() != null) {
            for (ChatSessionVO vo : result.getRecords()) {
                ChatSession session = page.getRecords().stream()
                        .filter(item -> Objects.equals(item.getId(), vo.getId())).findFirst().orElse(null);
                vo.setCanRead(session != null && sessionAccessGuard.canRead(session));
                vo.setCanWrite(session != null && sessionAccessGuard.canWrite(session)
                        && (session.getMessageTable() == null || session.getMessageTable().isBlank()));
            }
        }
        fillGuestFlag(result == null ? null : result.getRecords());
        return result;
    }

    @Override
    public Map<String, Boolean> accessScopes() {
        boolean admin = UserUtils.isAdmin() && !UserUtils.isChatKey() && !UserUtils.isSk();
        return Map.of("canViewTeam", admin || !sessionAccessGuard.leaderDepartmentIds().isEmpty(),
                "canViewAll", admin);
    }

    /**
     * 构造会话查询条件
     * <p>
     * 默认只看本人。team 范围仅负责人和管理员可用，all 仅管理员可用；
     * guestOnly 还需独立的游客会话管理权限。userId 只在 team/all 范围内生效。
     *
     * @param query 查询条件
     * @return 查询包装器
     */
    private LambdaQueryWrapper<ChatSession> buildSessionWrapper(ChatSessionQueryDTO query) {
        LambdaQueryWrapper<ChatSession> wrapper = new LambdaQueryWrapper<>();
        if (Boolean.TRUE.equals(query.getGuestOnly())) {
            appendGuestCondition(wrapper);
            // 游客会话跨用户可见，不能只靠入参开关判权。
            if (!UserUtils.hasPerm(PERM_GUEST_MANAGE)) {
                throw new RuntimeException("无权限查看游客会话");
            }
        } else if ("all".equalsIgnoreCase(query.getScope())) {
            if (!Boolean.TRUE.equals(accessScopes().get("canViewAll"))) {
                throw new RuntimeException("无权限查看全部会话");
            }
            wrapper.eq(query.getUserId() != null, ChatSession::getUserId, query.getUserId());
        } else if ("team".equalsIgnoreCase(query.getScope())) {
            Set<Long> departmentIds = sessionAccessGuard.leaderDepartmentIds();
            if (departmentIds.isEmpty() && !UserUtils.isAdmin()) {
                throw new RuntimeException("无权限查看部门会话");
            }
            Long userId = UserUtils.getId();
            if (UserUtils.isAdmin() && departmentIds.isEmpty()) {
                wrapper.eq(query.getUserId() != null, ChatSession::getUserId, query.getUserId());
            } else {
                wrapper.and(w -> w.eq(ChatSession::getUserId, userId)
                        .or().in(ChatSession::getOwnerDeptId, departmentIds));
                wrapper.eq(query.getUserId() != null, ChatSession::getUserId, query.getUserId());
            }
        } else if (query.getScope() == null || query.getScope().isBlank()
                || "mine".equalsIgnoreCase(query.getScope())) {
            wrapper.eq(ChatSession::getUserId, UserUtils.getId());
        } else {
            throw new RuntimeException("无效的会话范围");
        }
        wrapper.eq(query.getAgentId() != null, ChatSession::getAgentId, query.getAgentId())
                .eq(query.getIsPinned() != null, ChatSession::getIsPinned, query.getIsPinned())
                .orderByDesc(ChatSession::getIsPinned)
                .orderByDesc(ChatSession::getUpdatedAt);
        return wrapper;
    }

    /**
     * 追加「游客会话」条件：会话归属人在账号表中不存在
     * <p>
     * 为什么用 NOT EXISTS 子查询而不是「查出全部游客 user_id 再 IN」：
     * 游客身份每次访问分享链接都会新生成，数量没有上界，全量拉取会随运行时间劣化。
     * <p>
     * <b>只能用 gmd_faq_account，不能用 gmd_faq_account_tenant</b>：
     * `AccountServiceImpl#createAccount` 只写 account 表，**不写 account_tenant**
     * （只有 `TenantServiceImpl#addMember` 与租户初始化才写）。若拿 account_tenant 判定，
     * 所有经「用户列表」正常创建的账号都会被误判成游客，进而被管理员批量清理掉。
     * <p>
     * 表名走 {@link TableConst}（本仓禁止字面量表名）。
     * account 是跨租户共享的 B 类表（无 tenant_id 列，且在 `MybatisPlusConfig.IGNORE_TABLES`
     * 白名单里，租户拦截器不会给它注入条件），所以这里**不能**写 `a.tenant_id = ...`，
     * 否则直接报 Unknown column。会话侧的租户收敛由外层查询的拦截器注入完成。
     */
    private void appendGuestCondition(LambdaQueryWrapper<ChatSession> wrapper) {
        String sql = "NOT EXISTS (SELECT 1 FROM " + TableConst.ACCOUNT + " a"
                + " WHERE a.id = " + TableConst.CHAT_SESSION + ".user_id)";
        wrapper.apply(sql);
    }

    /**
     * 回填 isGuest 标记（批量判定，避免逐条查库）
     *
     * @param records 会话 VO 列表，允许为 null
     */
    private void fillGuestFlag(List<ChatSessionVO> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Long> ids = records.stream()
                .map(ChatSessionVO::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Set<Long> guestIds = findGuestSessionIds(ids);
        for (ChatSessionVO vo : records) {
            vo.setIsGuest(guestIds.contains(vo.getId()));
        }
    }
    @Override
    public Set<Long> findGuestSessionIds(Collection<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = sessionIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Set.of();
        }
        // 复用与列表筛选**完全相同**的包装器路径（走 MyBatis-Plus + 租户拦截器），
        // 不再手写 JdbcTemplate SQL：那条路既绕开租户拦截器、又容易在参数绑定上出错，
        // 而且一旦与 appendGuestCondition 有两套写法，判定就会和筛选结果不一致。
        LambdaQueryWrapper<ChatSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(ChatSession::getId);
        wrapper.in(ChatSession::getId, ids);
        appendGuestCondition(wrapper);
        return list(wrapper).stream()
                .map(ChatSession::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteGuestSession(Long sessionId) {
        if (sessionId == null) {
            return false;
        }
        // 与 deleteGuestSessions 保持同一口径的二次判权：控制器上已有 @PermNeed，
        // 但服务方法可能被别的入口调用，不能只靠注解兜底。
        if (!UserUtils.hasPerm(PERM_GUEST_MANAGE)) {
            throw new RuntimeException("无权限删除游客会话");
        }
        ChatSession session = getById(sessionId);
        if (session == null) {
            return false;
        }
        // 二次确认归属：调用方可能传了普通用户的会话 ID，必须挡住
        if (!findGuestSessionIds(List.of(sessionId)).contains(sessionId)) {
            throw new RuntimeException("该会话不是游客会话，拒绝删除");
        }
        deleteSessionInternal(session);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteGuestSessions(Long agentId, Long tenantId) {
        if (!UserUtils.hasPerm(PERM_GUEST_MANAGE)) {
            throw new RuntimeException("无权限清理游客会话");
        }
        // 注意：list(Wrapper) 是 IService 的方法 —— 不能链在 LambdaQueryWrapper 后面
        LambdaQueryWrapper<ChatSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(agentId != null, ChatSession::getAgentId, agentId);
        wrapper.eq(tenantId != null, ChatSession::getTenantId, tenantId);
        appendGuestCondition(wrapper);
        List<ChatSession> sessions = list(wrapper);
        int deleted = 0;
        for (ChatSession session : sessions) {
            // 列表可能被并发删掉，逐条删除时行已不在视为已删除，继续处理剩余会话
            deleteSessionInternal(session);
            deleted++;
        }
        return deleted;
    }

    /**
     * 仅含游客条件与租户条件的查询包装器（供批量清理使用）
     */
    private LambdaQueryWrapper<ChatSession> buildGuestOnlyWrapper() {
        LambdaQueryWrapper<ChatSession> wrapper = new LambdaQueryWrapper<>();
        appendGuestCondition(wrapper);
        return wrapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteInactiveGuestSessions(LocalDateTime cutoff, int maxCount) {
        if (cutoff == null || maxCount <= 0) {
            return 0;
        }
        List<ChatSession> sessions = list(buildGuestOnlyWrapper().lt(ChatSession::getUpdatedAt, cutoff)
                .orderByAsc(ChatSession::getUpdatedAt)
                .last("LIMIT " + maxCount));
        int deleted = 0;
        for (ChatSession session : sessions) {
            deleteSessionInternal(session);
            deleted++;
        }
        return deleted;
    }


    @Override
    public ChatSessionVO getSessionDetail(Long id) {
        ChatSession session = getById(id);
        if (session == null) {
            return null;
        }
        sessionAccessGuard.requireRead(id);
        ChatSessionVO vo = toSessionVO(session);
        vo.setIsGuest(findGuestSessionIds(List.of(id)).contains(id));
        return vo;
    }

    private ChatSession getAndCheckSession(Long sessionId) {
        return sessionAccessGuard.requireWrite(sessionId);
    }

    private ChatMessage getMessageBy(Integer messageId, Long sessionId) {
        // 先查 session 确定消息所在表
        ChatSession session = getById(sessionId);
        if (session == null) {
            throw new RuntimeException("会话不存在或已删除");
        }
        ChatMessage msg;
        if (isArchived(session)) {
            // 已归档：从归档表查询
            msg = messageTableRouter.getById(messageId, session.getMessageTable());
        } else {
            msg = chatMessageService.getById(messageId);
        }
        if (msg == null || !msg.getSessionId().equals(sessionId)) {
            throw new RuntimeException("当前消息不存在或不属于该会话");
        }
        return msg;
    }

    /**
     * 在父消息后插入新消息并更新会话的 current_message_id（用于正常追加与重新生成）
     */
    private ChatMessageVO saveNewMessageAndMoveCursor(ChatSession session, ChatMessage parent, String role, String content) {
        if (role == null || content == null) {
            throw new RuntimeException("role 与 content 不能为空");
        }
        // 归档会话只读：阻止追加消息
        if (isArchived(session)) {
            throw new RuntimeException("该会话已被归档，无法继续对话，请创建新会话");
        }

        ChatMessage msg = new ChatMessage();
        msg.setSessionId(session.getId());
        msg.setRole(role);
        msg.setContent(content);
        msg.setParentId(parent.getId());
        chatMessageService.save(msg);

        String parentPath = parent.getPath();
        String newPath = (parentPath == null || parentPath.isEmpty())
                ? String.valueOf(msg.getId())
                : parentPath + "/" + msg.getId();
        msg.setPath(newPath);
        msg.setDepth((parent.getDepth() == null ? 0 : parent.getDepth()) + 1);
        chatMessageService.updateById(msg);

        session.setCurrentMessageId(msg.getId());
        updateById(session);

        return BeanUtils.copy(msg, ChatMessageVO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pinSession(Long id) {
        ChatSession session = getAndCheckSession(id);
        session.setIsPinned(true);
        session.setPinTime(java.time.LocalDateTime.now());
        updateById(session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unpinSession(Long id) {
        ChatSession session = getAndCheckSession(id);
        session.setIsPinned(false);
        session.setPinTime(null);
        updateById(session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTitle(Long id, String title) {
        ChatSession session = getAndCheckSession(id);
        session.setTitle(title != null ? title : "新对话");
        updateById(session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCurrentMessageContent(Long sessionId, String content) {
        ChatSession session = sessionAccessGuard.requireActiveWrite(sessionId);
        if (isArchived(session)) {
            throw new RuntimeException("已归档会话不支持修改消息");
        }
        Integer msgId = session.getCurrentMessageId();
        if (msgId == null) {
            throw new RuntimeException("当前会话无活跃消息");
        }
        ChatMessage msg = chatMessageService.getById(msgId);
        if (msg == null || !msg.getSessionId().equals(sessionId)) {
            throw new RuntimeException("当前消息不存在或不属于该会话");
        }
        msg.setContent(content);
        chatMessageService.updateById(msg);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(Long id) {
        ChatSession session = getAndCheckSession(id);
        deleteSessionInternal(session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteSessionsByAgent(Long agentId, Long tenantId) {
        if (agentId == null) {
            throw new RuntimeException("agentId 不能为空");
        }
        // 会话归属人固定取当前登录用户：即便是管理员也不能借这个入口删掉别人的对话记录
        Long userId = UserUtils.getId();        if (userId == null || userId == 0L) {
            return 0;
        }

        List<ChatSession> sessions = lambdaQuery()
                .eq(ChatSession::getAgentId, agentId)
                .eq(ChatSession::getUserId, userId)
                .eq(tenantId != null, ChatSession::getTenantId, tenantId)
                .list();

        int deleted = 0;
        for (ChatSession session : sessions) {
            // 列表可能被并发删掉，逐条删除时行已不在视为已删除，继续处理剩余会话
            deleteSessionInternal(session);
            deleted++;
        }
        return deleted;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int clearSessionMessages(Long sessionId) {
        ChatSession session = sessionAccessGuard.requireActiveWrite(sessionId);
        int removed = deleteSessionMessages(session);
        clearAgentScopeState(session);
        // 重新插入根消息并把游标指回它，会话变成一个可继续对话的空会话
        ChatMessage root = new ChatMessage();
        root.setSessionId(session.getId());
        root.setRole("system");
        root.setContent("");
        root.setParentId(null);
        chatMessageService.save(root);
        root.setPath(String.valueOf(root.getId()));
        root.setDepth(0);
        chatMessageService.updateById(root);

        session.setCurrentMessageId(root.getId());
        updateById(session);
        return removed;
    }

    /**
     * 删除单条会话及其全部从属数据（调用方已完成归属校验）
     *
     * @param session 已通过归属校验的会话
     */
    private void deleteSessionInternal(ChatSession session) {
        deleteSessionMessages(session);
        removeById(session.getId());
        clearAgentScopeState(session);

        // 删除工作空间目录（文件 + sessionId文件夹本身）
        Path workspacePath = Paths.get(SysConst.getWorkspacePath(), String.valueOf(session.getId()))
                .toAbsolutePath().normalize();
        try (var paths = Files.walk(workspacePath)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (java.nio.file.NoSuchFileException ignored) {
            // 该会话从未创建工作空间。
        } catch (IOException e) {
            log.warn("删除会话工作空间失败: sessionId={}", session.getId(), e);
        }
        messagePublisher.publishAfterCommit(RedisChannelTopic.WORKSPACE_DELETE_CHANNEL,
                JsonUtils.toJsonStr(Map.of("tenantCode", TenantUtils.getCurrentTenantCode(),
                        "sessionId", String.valueOf(session.getId()))));
    }

    private void clearAgentScopeState(ChatSession session) {
        instructionService.clearSession(session.getTenantId(), session.getUserId(), session.getId());
        telemetryService.clearSession(session.getTenantId(), session.getUserId(), session.getId());
        conversationQueue.clearSession(session.getTenantId(), session.getUserId(), session.getId());
        String durableKey = com.gmd.faq.server.main.common.util.AgentStateScope.sessionKey(
                session.getTenantId(), session.getUserId(), String.valueOf(session.getId()));
        String transientKey = com.gmd.faq.server.main.common.util.AgentStateScope.userId(
                session.getTenantId(), session.getUserId()) + "_transient:" + session.getId();
        agentScopeSessionMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.gmd.faq.server.main.common.entity.AgentScopeSession>()
                .in("session_id", String.valueOf(session.getId()), durableKey, transientKey)
                .or().apply("session_id LIKE {0} ESCAPE '!'", durableKey.replace("_", "!_") + ":subagent:%")
                .or().apply("session_id LIKE {0} ESCAPE '!'", transientKey.replace("_", "!_") + ":subagent:%"));
        if (sessionManager != null) sessionManager.removeSession(String.valueOf(session.getId()));
    }

    /**
     * 删除会话的消息与派生数据，不动会话本身
     *
     * @param session 会话
     * @return 主表删除的消息条数
     */
    private int deleteSessionMessages(ChatSession session) {
        // 如果已归档，先删除归档表中的消息
        if (isArchived(session)) {
            messageTableRouter.deleteBySessionId(session.getId(), session.getMessageTable());
        }
        // 统计后删除，供调用方回显「清理了多少条记录」
        Long total = chatMessageService.lambdaQuery()
                .eq(ChatMessage::getSessionId, session.getId())
                .count();
        // 再删除主表 chat_message 中的消息
        chatMessageService.lambdaUpdate()
                .eq(ChatMessage::getSessionId, session.getId())
                .remove();
        // Trace rows are a projection owned by the chat session and must not outlive a deletion.
        jdbcTemplate.update("DELETE FROM " + TableConst.CHAT_SUBAGENT_EVENT + " WHERE session_id = ?", session.getId());
        jdbcTemplate.update("DELETE FROM " + TableConst.CHAT_SUBAGENT_RUN + " WHERE session_id = ?", session.getId());
        // 对话产物同理：它挂在会话的消息上，会话/消息没了就不该留下孤儿行。
        // 表名走 TableConst —— 本仓禁止字面量表名（历史上正是手写 SQL 漏改表名导致大面积故障）。
        jdbcTemplate.update("DELETE FROM " + TableConst.CHAT_ARTIFACT + " WHERE session_id = ?", session.getId());
        return total == null ? 0 : total.intValue();
    }

    private ChatSessionVO toSessionVO(ChatSession session) {
        ChatSessionVO vo = BeanUtils.copy(session, ChatSessionVO.class);
        vo.setCanRead(sessionAccessGuard.canRead(session));
        vo.setCanWrite(sessionAccessGuard.canWrite(session)
                && (session.getMessageTable() == null || session.getMessageTable().isBlank()));
        return vo;
    }

    /**
     * 判断会话是否已成功归档到分表。
     * 仅当 message_table 以 gmd_faq_chat_message_ 开头时才视为已归档，
     * 排除 null、空串、ARCHIVE_FAILED 等非归档表名。
     */
    private boolean isArchived(ChatSession session) {
        String mt = session.getMessageTable();
        return mt != null && !mt.isBlank() && mt.startsWith(TableConst.CHAT_MESSAGE + "_");
    }
}

