package com.gmd.faq.server.main.engine.workspace.hook;

import com.gmd.faq.server.main.agent.service.ChatArtifactService;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.entity.ChatArtifact;
import com.gmd.faq.server.main.common.enums.WsMessageType;
import com.gmd.faq.server.main.common.util.AgentMetadataStore;
import com.gmd.faq.server.main.common.ws.WebSocketClient;
import com.gmd.faq.server.main.engine.tool.ToolResultPresentation;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * 描述：对话产物钩子
 *
 * <p>职责：把「智能体本次实际写出/修改了哪些文件」**确定性地**记录下来。
 *
 * <p>为什么不能沿用提示词里的 {@code <workspace-file>} 约定：
 * 那是「用户引用文件给模型看」的机制，模型愿不愿意在自己的回复里写这个标签全靠自觉。
 * 而产品要求「生成的文件必须出现在对话里」，这必须由代码保证，不能依赖模型行为。
 *
 * <p>判定方式：写工具执行**前**给工作空间拍一份 mtime 快照（PreActingEvent），
 * 执行**后**再拍一份（PostActingEvent），两者求差集即为本次新增/修改的文件。
 * 相比直接读工具入参（{@link WebsocketHook} 的做法），这样能覆盖两类它覆盖不到的情况：
 * <ul>
 *   <li>{@code search_replace_file} —— 该工具此前完全没被通知</li>
 *   <li>{@code execute_shell_command} —— 文件是脚本间接写出的（如 python 画图存 png），
 *       入参里根本没有文件名</li>
 * </ul>
 *
 **/
@Slf4j
@Component
@RequiredArgsConstructor
public class ArtifactHook implements Hook {

    private final ChatArtifactService chatArtifactService;
    private final WebSocketClient webSocketClient;

    /**
     * 写工具执行前的工作空间快照：threadId -> (相对路径 -> 最后修改毫秒)
     *
     * <p>ReAct 循环里工具是串行执行的，因此同一 threadId 上不会并发覆盖。
     * 用 remove 取用，避免快照长期驻留内存。
     */
    private static final Map<String, Map<String, Long>> BEFORE_SNAPSHOT = new ConcurrentHashMap<>();

    /** 不视为产物的路径片段：解释器缓存与隐藏产物，避免噪声卡片 */
    private static final Set<String> IGNORED_DIR_SEGMENTS = Set.of(
            "__pycache__", "node_modules", ".git", ".idea", ".vscode", ".ipynb_checkpoints", ".tool-results"
    );

    /** 不视为产物的扩展名 */
    private static final Set<String> IGNORED_EXTENSIONS = Set.of("pyc", "pyo", "class", "tmp", "swp", "lock");

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent preActing) {
            onPreActing(event, preActing.getToolUse());
        } else if (event instanceof PostActingEvent postActing) {
            onPostActing(postActing, postActing.getToolUse());
            // SDK 的直接返回会丢弃 PostActing 改写，单独把产物交给当前流订阅。
            return Mono.deferContextual(context -> {
                BiConsumer<Agent, ToolResultBlock> listener =
                        context.getOrDefault(ToolResultPresentation.ARTIFACTS_LISTENER_CONTEXT_KEY, null);
                ToolResultBlock result = postActing.getToolResult();
                if (listener != null && result != null
                        && result.getMetadata().containsKey(ToolResultPresentation.ARTIFACTS_METADATA_KEY)) {
                    listener.accept(event.getAgent(), result);
                }
                return Mono.just(event);
            });
        }

        return Mono.just(event);
    }

    /**
     * 写工具执行前：拍快照
     */
    private void onPreActing(HookEvent event, ToolUseBlock toolUse) {
        if (!isWriteTool(toolUse)) {
            return;
        }
        String threadId = extractThreadId(event);
        if (threadId == null) {
            return;
        }
        Path dir = resolveWorkspaceDir(event, threadId);
        if (dir == null) {
            return;
        }
        BEFORE_SNAPSHOT.put(threadId, snapshot(dir));
    }

    /**
     * 写工具执行后：与快照求差集，落库并广播
     */
    private void onPostActing(PostActingEvent event, ToolUseBlock toolUse) {
        if (!isWriteTool(toolUse)) {
            return;
        }
        String threadId = extractThreadId(event);
        if (threadId == null) {
            return;
        }

        Path dir = resolveWorkspaceDir(event, threadId);
        if (dir == null) {
            BEFORE_SNAPSHOT.remove(threadId);
            return;
        }

        Map<String, Long> before = BEFORE_SNAPSHOT.remove(threadId);
        if (before == null) {
            // 没有前置快照（例如会话中途才开始记录）：本次只建立基线，不判定产物，
            // 否则会把工作空间里所有历史文件一次性报成"新产物"。
            return;
        }

        // 编排页预览等临时 threadId 不落库（与 ChatLogHook.parseSessionId 同一口径）
        Long sessionId = parseSessionId(threadId);
        Long tenantId = extractTenantId(event);

        List<ChatArtifact> produced = new ArrayList<>();
        Map<String, Long> after = snapshot(dir);
        for (Map.Entry<String, Long> entry : after.entrySet()) {
            Long previous = before.get(entry.getKey());
            // 新增（此前不存在）或已修改（mtime 变化）
            if (previous == null || !previous.equals(entry.getValue())) {
                ChatArtifact artifact = buildArtifact(dir, entry.getKey(), toolUse.getName());
                if (artifact != null) {
                    produced.add(artifact);
                }
            }
        }

        if (produced.isEmpty()) {
            return;
        }

        // 产物与实际工具结果绑定；即使隐藏调用详情，流式与历史也能定位到同一步骤。
        ToolResultBlock result = event.getToolResult();
        if (result != null && event.getToolResultMsg() != null) {
            Map<String, Object> metadata = new HashMap<>(result.getMetadata());
            metadata.put(ToolResultPresentation.ARTIFACTS_METADATA_KEY, produced.stream().map(artifact ->
                    new ToolResultPresentation.Artifact(artifact.getFilePath(), artifact.getFileName(),
                            artifact.getExtension(), artifact.getSizeBytes(), artifact.getSourceTool(),
                            artifact.getCreateTime().toString())).toList());
            ToolResultBlock decorated = ToolResultBlock.of(result.getId(), result.getName(), result.getOutput(), metadata)
                    .withState(result.getState());
            event.setToolResult(decorated);
            Msg message = event.getToolResultMsg();
            List<ContentBlock> blocks = message.getContent().stream()
                    .map(block -> block instanceof ToolResultBlock ? (ContentBlock) decorated : block).toList();
            event.setToolResultMsg(Msg.builder().id(message.getId()).name(message.getName()).role(message.getRole())
                    .content(blocks).metadata(message.getMetadata()).timestamp(message.getTimestamp()).build());
        }

        for (ChatArtifact artifact : produced) {
            if (sessionId != null && tenantId != null) {
                artifact.setSessionId(sessionId);
                artifact.setTenantId(tenantId);
                chatArtifactService.recordArtifact(artifact);
            }
            broadcast(sessionId, artifact);
        }
    }

    /**
     * 广播产物生成事件，供对话页实时插入卡片
     */
    private void broadcast(Long sessionId, ChatArtifact artifact) {
        if (sessionId == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("sessionId", String.valueOf(sessionId));
        payload.put("filePath", artifact.getFilePath());
        payload.put("fileName", artifact.getFileName());
        payload.put("extension", artifact.getExtension());
        payload.put("sizeBytes", artifact.getSizeBytes());
        payload.put("sourceTool", artifact.getSourceTool());
        payload.put("createTime", artifact.getCreateTime() == null ? null : artifact.getCreateTime().toString());
        try {
            webSocketClient.broadcastCluster(WsMessageType.ARTIFACT_CREATED.name(), payload);
        } catch (Exception e) {
            // 广播失败不影响落库结果
            log.error("产物广播失败: {}", e.getMessage());
        }
    }

    /**
     * 由文件路径构建产物实体
     */
    private ChatArtifact buildArtifact(Path dir, String relativePath, String toolName) {
        Path file = dir.resolve(relativePath);
        try {
            long size = Files.size(file);
            String fileName = file.getFileName().toString();

            ChatArtifact artifact = new ChatArtifact();
            artifact.setFilePath(relativePath);
            artifact.setFileName(fileName);
            artifact.setExtension(extensionOf(fileName));
            artifact.setSizeBytes(size);
            artifact.setSourceTool(toolName);
            artifact.setCreateTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
            return artifact;
        } catch (IOException e) {
            log.warn("读取产物信息失败: {} - {}", relativePath, e.getMessage());
            return null;
        }
    }

    /**
     * 扫描工作空间，返回 相对路径 -> 最后修改毫秒
     *
     * <p>工作空间有容量上限（{@link SysConst#WORKSPACE_CAPACITY_MB} MB），
     * 因此全量扫描的成本可控。
     */
    private Map<String, Long> snapshot(Path dir) {
        Map<String, Long> snapshot = new HashMap<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return snapshot;
        }

        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                String relative = dir.relativize(file).toString().replace('\\', '/');
                if (isIgnored(relative)) {
                    return;
                }
                try {
                    snapshot.put(relative, Files.getLastModifiedTime(file).toMillis());
                } catch (IOException ignored) {
                    // 文件可能在扫描期间被删除，跳过即可
                }
            });
        } catch (IOException e) {
            log.warn("扫描工作空间失败: {} - {}", dir, e.getMessage());
        }

        return snapshot;
    }

    /**
     * 是否忽略该相对路径（缓存目录与临时文件不作为产物）
     */
    private boolean isIgnored(String relativePath) {
        for (String segment : relativePath.split("/")) {
            if (IGNORED_DIR_SEGMENTS.contains(segment)) {
                return true;
            }
        }
        return IGNORED_EXTENSIONS.contains(extensionOf(relativePath));
    }

    /**
     * 取小写扩展名（不含点号）
     */
    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > -1 ? name.substring(dot + 1).toLowerCase() : "";
    }

    /**
     * 解析会话路径：{workspace}/{tenantCode}/{threadId}
     */
    private Path resolveWorkspaceDir(HookEvent event, String threadId) {
        String tenantCode = extractTenantCode(event);
        if (tenantCode == null) {
            return null;
        }
        return Paths.get(SysConst.getWorkspacePath(tenantCode), threadId).toAbsolutePath().normalize();
    }

    /**
     * 是否为写文件的工具
     */
    private boolean isWriteTool(ToolUseBlock toolUse) {
        return toolUse != null && ToolConstants.WRITE_TOOLS.contains(toolUse.getName());
    }

    /**
     * 非数字的临时 threadId（编排页预览调试）没有可落库的会话
     */
    private Long parseSessionId(String threadId) {
        if (threadId == null || threadId.isEmpty()) {
            return null;
        }
        if (!threadId.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Long.valueOf(threadId);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String extractThreadId(HookEvent event) {
        if (event.getAgent() instanceof AgentBase agentBase) {
            return AgentMetadataStore.get(agentBase.getAgentId(), "threadId");
        }
        return null;
    }

    private String extractTenantCode(HookEvent event) {
        if (event.getAgent() instanceof AgentBase agentBase) {
            return AgentMetadataStore.get(agentBase.getAgentId(), "tenantCode");
        }
        return null;
    }

    private Long extractTenantId(HookEvent event) {
        if (event.getAgent() instanceof AgentBase agentBase) {
            return AgentMetadataStore.get(agentBase.getAgentId(), "tenantId");
        }
        return null;
    }
}
