/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.spring.boot.agui.mvc;

import com.gmd.faq.server.main.agent.service.AgentDefinitionService;
import com.gmd.faq.server.main.agent.service.SessionAccessGuard;
import com.gmd.faq.server.main.common.config.auth.ChatKeyAccess;
import com.gmd.faq.server.main.common.config.auth.SkAccess;
import com.gmd.faq.server.main.common.entity.ChatSession;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.engine.tool.ToolResultImageSupport;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import io.agentscope.core.agui.model.RunAgentInput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST controller for AG-UI protocol endpoints.
 *
 * <p>This controller exposes the AG-UI run endpoints for Spring MVC applications.
 * It delegates the actual processing to {@link AguiMvcController}.
 */
@RestController
public class AguiRestController {

    private final AguiMvcController aguiMvcController;
    private final AgentDefinitionService agentDefinitionService;
    private final ResourceVisibilityService resourceVisibilityService;
    private final SessionAccessGuard sessionAccessGuard;
    private final Map<String, TemporaryThreadOwner> temporaryThreads = new ConcurrentHashMap<>();
    private static final long TEMP_THREAD_TTL_MS = TimeUnit.HOURS.toMillis(24);
    private final String pathPrefix;
    private final boolean enablePathRouting;

    /**
     * Creates a new AguiRestController.
     *
     * @param aguiMvcController The AG-UI MVC controller
     * @param pathPrefix The path prefix for endpoints
     * @param enablePathRouting Whether to enable path variable routing
     */
    public AguiRestController(
            AguiMvcController aguiMvcController,
            AgentDefinitionService agentDefinitionService,
            ResourceVisibilityService resourceVisibilityService,
            SessionAccessGuard sessionAccessGuard,
            String pathPrefix,
            boolean enablePathRouting) {
        this.aguiMvcController = aguiMvcController;
        this.agentDefinitionService = agentDefinitionService;
        this.resourceVisibilityService = resourceVisibilityService;
        this.sessionAccessGuard = sessionAccessGuard;
        this.pathPrefix = pathPrefix;
        this.enablePathRouting = enablePathRouting;
    }

    /**
     * Handle an AG-UI run request.
     *
     * <p>Agent ID is resolved from (in priority order):
     * <ol>
     *   <li>HTTP header (configurable, default: X-Agent-Id)</li>
     *   <li>forwardedProps.agentId in request body</li>
     *   <li>config.defaultAgentId</li>
     *   <li>"default"</li>
     * </ol>
     *
     * @param input The run agent input
     * @param agentIdHeader The agent ID from HTTP header (optional)
     * @return An SseEmitter for streaming AG-UI events
     */
    @SkAccess
    @ChatKeyAccess
    @PostMapping(
            value = "${agentscope.agui.path-prefix:/agui}/run",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(
            @RequestBody RunAgentInput input,
            @RequestHeader(
                            value = "${agentscope.agui.agent-id-header:X-Agent-Id}",
                            required = false)
                    String agentIdHeader) {
        String agentCode = agentIdHeader;
        if (agentCode == null || agentCode.isBlank()) {
            Object fromBody = input.getForwardedProp("agentId");
            agentCode = fromBody == null ? null : fromBody.toString();
        }
        authorizeRun(input, agentCode);
        return aguiMvcController.handle(input, agentIdHeader);
    }

    /**
     * Handle an AG-UI run request with agent ID in the URL path.
     *
     * <p>The path variable takes highest priority for agent resolution.
     *
     * @param agentId The agent ID from path variable
     * @param input The run agent input
     * @param agentIdHeader The agent ID from HTTP header (optional)
     * @return An SseEmitter for streaming AG-UI events
     */
    @SkAccess
    @ChatKeyAccess
    @PostMapping(
            value = "${agentscope.agui.path-prefix:/agui}/run/{agentId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runWithAgentId(
            @PathVariable("agentId") String agentId,
            @RequestBody RunAgentInput input,
            @RequestHeader(
                            value = "${agentscope.agui.agent-id-header:X-Agent-Id}",
                            required = false)
                    String agentIdHeader) {
        authorizeRun(input, agentId);
        return aguiMvcController.handleWithAgentId(input, agentIdHeader, agentId);
    }

    /**
     * SSE 重连端点：断线后重新获取事件流。
     *
     * @param threadId 会话 ID
     * @return SseEmitter 回放缓冲区事件后接续实时流
     */
    @SkAccess
    @ChatKeyAccess
    @GetMapping(
            value = "${agentscope.agui.path-prefix:/agui}/reconnect/{threadId}",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter reconnect(
            @PathVariable("threadId") String threadId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        authorizeThreadRead(threadId);
        return aguiMvcController.reconnect(threadId, lastEventId);
    }

    /**
     * HITL resume 端点：提交逐工具确认决策，
     * 后端用 AgentScope 官方「暂停-恢复」从暂停点续跑（全允许→agent 继续执行 pending；
     * 含拒绝→喂入「用户已拒绝执行」后继续），并续接暂停前的 SSE 事件流。
     *
     * @param threadId 会话 ID（暂停态的 key）
     * @param body 逐工具确认决策 + 是否开启记忆
     * @return SseEmitter 流式续接恢复后的事件
     */
    @SkAccess
    @ChatKeyAccess
    @PostMapping(
            value = "${agentscope.agui.path-prefix:/agui}/resume/{threadId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(
            @PathVariable("threadId") String threadId, @RequestBody ResumeRequest body) {
        ChatSession session = authorizeThreadWrite(threadId, true);
        Long agentId = session == null ? temporaryThreads.get(threadId).agentId() : session.getAgentId();
        resourceVisibilityService.assertUsable("AGENT", agentId);
        return aguiMvcController.handleResume(threadId, body.decisions(), body.memoryActive());
    }

    /**
     * HITL 待确认列表端点：从持久 Session 的暂停态重建「待确认工具」，供前端刷新/重进会话时
     * 重建确认 UI（暂停态会话已不在 active-runs、RunTracker 内存态跨重启会丢，故不能靠 reconnect 回放）。
     *
     * @param threadId 会话 ID
     * @return { pending: [{toolUseId,name,input}] }；无暂停态则 pending 为空数组
     */
    @SkAccess
    @ChatKeyAccess
    @GetMapping(
            value = "${agentscope.agui.path-prefix:/agui}/pending/{threadId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> pending(@PathVariable("threadId") String threadId) {
        authorizeThreadRead(threadId);
        return ResponseEntity.ok(Map.of("pending", aguiMvcController.getPendingConfirms(threadId)));
    }

    /**
     * 运行状态查询端点。
     *
     * @param threadId 会话 ID
     * @return { running: boolean }
     */
    @SkAccess
    @ChatKeyAccess
    @GetMapping(value = "${agentscope.agui.path-prefix:/agui}/status/{threadId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable("threadId") String threadId) {
        authorizeThreadRead(threadId);
        boolean running = aguiMvcController.getStatus(threadId);
        return ResponseEntity.ok(Map.of(
                "running", running,
                "state", aguiMvcController.getRunState(threadId).name()));
    }

    /**
     * 强制停止端点。
     *
     * @param threadId 会话 ID
     * @return { accepted: true, state: "STOPPING" }
     */
    @SkAccess
    @ChatKeyAccess
    @PostMapping(value = "${agentscope.agui.path-prefix:/agui}/stop/{threadId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> stop(@PathVariable("threadId") String threadId) {
        authorizeThreadWrite(threadId, false);
        aguiMvcController.stop(threadId);
        RunTracker.RunState state = aguiMvcController.getRunState(threadId);
        return ResponseEntity.ok(Map.of(
                "accepted", true,
                "stopped", state == RunTracker.RunState.COMPLETED,
                "state", state.name()));
    }

    /**
     * 活跃运行列表端点。
     *
     * @return 所有活跃运行的 threadId 列表
     */
    @SkAccess
    @ChatKeyAccess
    @GetMapping(value = "${agentscope.agui.path-prefix:/agui}/active-runs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Set<String>> getActiveRuns() {
        return ResponseEntity.ok(aguiMvcController.getActiveRuns().stream()
                .filter(this::canReadThread)
                .collect(Collectors.toSet()));
    }

    /** 临时调试会话没有 chat_session，仅允许已注册归属人读取本次工具生成的图片。 */
    @SkAccess
    @ChatKeyAccess
    @GetMapping("${agentscope.agui.path-prefix:/agui}/tool-images/{threadId}")
    public ResponseEntity<byte[]> temporaryToolImage(@PathVariable("threadId") String threadId,
                                                     @RequestParam("fileName") String fileName) throws IOException {
        if (threadId == null || !threadId.matches("[A-Za-z0-9_-]+") || threadId.matches("[0-9]+")) {
            throw new IllegalArgumentException("无效的临时会话ID");
        }
        authorizeThreadRead(threadId);
        if (fileName == null || !fileName.matches("\\.tool-results/[a-f0-9]{16}/[a-f0-9]{64}\\.(png|jpg|gif|webp|bmp)")) {
            throw new IllegalArgumentException("仅允许预览临时会话的工具图片");
        }
        Path workspace = Path.of(SysConst.getWorkspacePath(TenantUtils.getCurrentTenantCode()), threadId)
                .toAbsolutePath().normalize();
        Path file = ToolResultImageSupport.checkedPath(workspace, fileName);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > ToolResultImageSupport.MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("图片不可用或超过大小限制");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(ToolResultImageSupport.MAX_IMAGE_BYTES + 1);
        }
        if (bytes.length == 0 || bytes.length > ToolResultImageSupport.MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("图片不可用或超过大小限制");
        }
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1);
        String mime = extension.equals("jpg") ? "image/jpeg" : "image/" + extension;
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime))
                .cacheControl(CacheControl.noStore()).body(bytes);
    }

    private void authorizeRun(RunAgentInput input, String agentCode) {
        if (agentCode == null || agentCode.isBlank()) {
            throw new IllegalArgumentException("必须指定智能体编号");
        }
        AgentDefinition agent = agentDefinitionService.lambdaQuery()
                .eq(AgentDefinition::getAgentCode, agentCode)
                .one();
        if (agent == null || !Boolean.TRUE.equals(agent.getEnabled())) {
            throw new IllegalStateException("智能体不存在或已禁用");
        }
        resourceVisibilityService.assertUsable("AGENT", agent.getId());
        boolean ownerPreview = !UserUtils.isChatKey() && !UserUtils.isSk()
                && com.gmd.faq.server.main.common.util.ResourceOwnershipSupport.isManageable(agent);
        if (!Boolean.TRUE.equals(agent.getPublished()) && !ownerPreview && !UserUtils.isAdmin()) {
            throw new IllegalStateException("智能体尚未发布");
        }
        if (UserUtils.isSk() && !Boolean.TRUE.equals(agent.getPublished())) {
            throw new IllegalStateException("SK只能运行已发布的智能体");
        }
        if (UserUtils.isChatKey() && (!Objects.equals(UserUtils.getBoundAgentId(), agent.getId())
                || !Boolean.TRUE.equals(agent.getPublished())
                || !Boolean.TRUE.equals(agent.getExternalShareEnabled()))) {
            throw new IllegalStateException("分享链接无权运行该智能体");
        }
        String threadId = input.getThreadId();
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId不能为空");
        }
        if (threadId.matches("[0-9]+")) {
            ChatSession session = authorizeThreadWrite(threadId, true);
            if (!Objects.equals(session.getAgentId(), agent.getId())) {
                throw new IllegalStateException("会话与智能体不匹配");
            }
            if (session.getMessageTable() != null && !session.getMessageTable().isEmpty()) {
                throw new IllegalStateException("当前会话已经归档，不支持继续对话");
            }
            return;
        }
        registerTemporaryThread(threadId, agent.getId());
    }

    private void registerTemporaryThread(String threadId, Long agentId) {
        long now = System.currentTimeMillis();
        temporaryThreads.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        TemporaryThreadOwner current = currentThreadOwner(agentId, now + TEMP_THREAD_TTL_MS);
        TemporaryThreadOwner owner = temporaryThreads.compute(threadId, (key, existing) ->
                existing == null || existing.expiresAt() < now ? current : existing);
        if (!samePrincipal(owner, current) || !Objects.equals(owner.agentId(), agentId)) {
            throw new IllegalStateException("无权使用该临时会话");
        }
    }

    private ChatSession authorizeThreadRead(String threadId) {
        if (threadId != null && threadId.matches("[0-9]+")) {
            try {
                return sessionAccessGuard.requireRead(Long.valueOf(threadId));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("会话ID无效");
            }
        }
        requireTemporaryOwner(threadId);
        return null;
    }

    private ChatSession authorizeThreadWrite(String threadId, boolean active) {
        if (threadId != null && threadId.matches("[0-9]+")) {
            try {
                Long id = Long.valueOf(threadId);
                return active ? sessionAccessGuard.requireActiveWrite(id) : sessionAccessGuard.requireWrite(id);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("会话ID无效");
            }
        }
        requireTemporaryOwner(threadId);
        return null;
    }

    private void requireTemporaryOwner(String threadId) {
        TemporaryThreadOwner stored = threadId == null ? null : temporaryThreads.get(threadId);
        if (stored == null || stored.expiresAt() < System.currentTimeMillis()
                || !samePrincipal(stored, currentThreadOwner(stored.agentId(), stored.expiresAt()))) {
            throw new IllegalStateException("无权访问该临时会话");
        }
    }

    private boolean canReadThread(String threadId) {
        try {
            authorizeThreadRead(threadId);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private TemporaryThreadOwner currentThreadOwner(Long agentId, long expiresAt) {
        String authType = UserUtils.getUserDetail() == null ? null : UserUtils.getUserDetail().getAuthType();
        return new TemporaryThreadOwner(authType, UserUtils.getId(), UserUtils.getTenantId(),
                UserUtils.getChatKeyId(), UserUtils.getSkId(), agentId, expiresAt);
    }

    private boolean samePrincipal(TemporaryThreadOwner left, TemporaryThreadOwner right) {
        return Objects.equals(left.authType(), right.authType())
                && Objects.equals(left.userId(), right.userId())
                && Objects.equals(left.tenantId(), right.tenantId())
                && Objects.equals(left.chatKeyId(), right.chatKeyId())
                && Objects.equals(left.skId(), right.skId());
    }

    private record TemporaryThreadOwner(String authType, Long userId, Long tenantId,
                                        String chatKeyId, Long skId, Long agentId, long expiresAt) {
    }
}
