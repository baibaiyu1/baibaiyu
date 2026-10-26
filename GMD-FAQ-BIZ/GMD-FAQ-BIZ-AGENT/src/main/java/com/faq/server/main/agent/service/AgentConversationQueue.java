package com.gmd.faq.server.main.agent.service;

import com.gmd.faq.server.main.common.consts.TableConst;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 数据库串行门闩；租约只用于识别中断，不自动重试已开始的任务。 */
@Service
@RequiredArgsConstructor
public class AgentConversationQueue {
    private static final String LANE = TableConst.AGENT_RUN_LANE;
    private static final String MSG = TableConst.AGENT_QUEUED_MESSAGE;
    private static final String SCOPE = "tenant_id=? AND user_id=? AND session_id=?";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentInstructionService inbox;

    public record Scope(long tenantId, long userId, long sessionId) {
        public Scope {
            if (tenantId < 1 || userId < 1 || sessionId < 1) throw new IllegalArgumentException("无效的会话身份");
        }
        Object[] args() { return new Object[]{tenantId, userId, sessionId}; }
    }
    public record Options(boolean memoryActive, boolean planActive, boolean toolProcessActive, List<String> fileIds, boolean planMode) {
        public Options { fileIds = fileIds == null ? List.of() : List.copyOf(fileIds); }
    }
    public record Queued(String id, String requestId, String content, Options options, String state, String runId) {}
    public record Snapshot(String state, boolean paused, String runId, String nodeId, List<Queued> messages) {}
    public record Dispatch(Scope scope, Queued message) {}

    public Scope sessionScope(long tenantId, long sessionId) {
        return jdbc.queryForObject("SELECT tenant_id,user_id,id FROM " + TableConst.CHAT_SESSION + " WHERE tenant_id=? AND id=?",
                (rs,n) -> new Scope(rs.getLong(1),rs.getLong(2),rs.getLong(3)),tenantId,sessionId);
    }

    public void renewIdle(String nodeId) {
        jdbc.update("UPDATE " + LANE + " SET lease_until=DATE_ADD(NOW(3), INTERVAL 45 SECOND)"
                + " WHERE node_id=? AND state='IDLE' AND lease_until>NOW(3)", nodeId);
    }

    @Transactional
    public Queued enqueueOnNode(Scope scope, String requestId, String content, Options options, String nodeId) {
        Queued message = enqueue(scope, requestId, content, options);
        preferNode(scope, nodeId);
        return message;
    }

    @Transactional
    public Queued enqueue(Scope scope, String requestId, String content, Options options) {
        validate(requestId, content, options);
        lock(scope);
        var duplicate = find(scope, " AND request_id=?", requestId);
        if (!duplicate.isEmpty()) {
            Queued old = duplicate.getFirst();
            if (!old.content().equals(content) || !old.options().equals(options))
                throw new IllegalArgumentException("同一请求标识不能提交不同消息");
            return old;
        }
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + MSG + " WHERE " + SCOPE
                + " AND state='QUEUED'", Long.class, scope.args());
        if (count != null && count >= 20) throw new IllegalStateException("最多排队20条消息，请等待或取消后再发送");
        jdbc.update("INSERT INTO " + MSG + " (tenant_id,user_id,session_id,request_id,content,options) VALUES (?,?,?,?,?,?)",
                scope.tenantId, scope.userId, scope.sessionId, requestId, content, encode(options));
        return find(scope, " AND request_id=?", requestId).getFirst();
    }

    @Transactional
    public void edit(Scope scope, long id, String content) {
        if (content == null || content.isBlank() || content.length() > 16000) throw new IllegalArgumentException("消息须为1～16000字");
        lock(scope);
        if (jdbc.update("UPDATE " + MSG + " SET content=? WHERE " + SCOPE + " AND id=? AND state='QUEUED'",
                args(new Object[]{content}, args(scope.args(), id))) != 1) throw new IllegalStateException("消息已开始或已取消，请刷新队列");
    }

    @Transactional
    public void cancel(Scope scope, long id) {
        lock(scope);
        if (jdbc.update("UPDATE " + MSG + " SET state='CANCELLED' WHERE " + SCOPE + " AND id=? AND state='QUEUED'",
                args(scope.args(), id)) != 1) throw new IllegalStateException("消息已开始或已取消，请刷新队列");
    }

    /** 与调度共用会话行锁，收件与移出队列在同一事务完成；按消息 ID 幂等。 */
    @Transactional
    public Queued steer(Scope scope, long id, String runId, String nodeId) {
        var lane = lock(scope);
        var found = find(scope, " AND id=?", id);
        if (found.isEmpty()) throw new IllegalStateException("消息不存在，请刷新后重试");
        Queued message = found.getFirst();
        if ("STEERED".equals(message.state()) && Objects.equals(message.runId(), runId)) return message;
        if (!"QUEUED".equals(message.state())) throw new IllegalStateException("消息已开始或已删除，请刷新后重试");
        if (runId == null || !"RUNNING".equals(lane.get("state")) || !Objects.equals(lane.get("run_id"), runId)
                || !Objects.equals(lane.get("node_id"), nodeId))
            throw new IllegalStateException("当前任务已结束、正在停止或不在当前节点，消息仍保留待发送");
        if (!message.options().fileIds().isEmpty()) throw new IllegalArgumentException("带附件的消息暂不支持插队，可保留等待发送");
        if (message.content().length() > 4000) throw new IllegalArgumentException("插队消息最多4000字，请先编辑");
        inbox.receive(new AgentInstructionService.Scope(scope.tenantId, scope.userId, scope.sessionId, runId),
                "queue-" + message.id(), message.content());
        if (jdbc.update("UPDATE " + MSG + " SET state='STEERED',run_id=? WHERE " + SCOPE + " AND id=? AND state='QUEUED'",
                args(new Object[]{runId}, args(scope.args(), id))) != 1)
            throw new IllegalStateException("消息状态已变化，请刷新后重试");
        return new Queued(message.id(), message.requestId(), message.content(), message.options(), "STEERED", runId);
    }

    @Transactional
    public Snapshot snapshot(Scope scope) {
        var lane = lock(scope);
        return new Snapshot((String) lane.get("state"), Boolean.TRUE.equals(lane.get("paused")) || Objects.equals(lane.get("paused"), 1),
                (String) lane.get("run_id"), (String) lane.get("node_id"), find(scope, " ORDER BY id DESC LIMIT 100").reversed());
    }

    /** 手动发送和审批续跑也经过同一门闩，必须在读写 SDK 会话状态前调用。 */
    @Transactional
    public void begin(Scope scope, String runId, String nodeId, boolean resume) {
        var lane = lock(scope);
        String state = (String) lane.get("state");
        if ("RUNNING".equals(state) || "STOPPING".equals(state)) throw new IllegalStateException("当前会话正在运行，请将消息加入队列");
        if ("WAITING".equals(state) && !resume) throw new IllegalStateException("请先处理当前任务的审批");
        jdbc.update("UPDATE " + LANE + " SET state='RUNNING',run_id=?,node_id=?,lease_until=DATE_ADD(NOW(3), INTERVAL 45 SECOND) WHERE " + SCOPE,
                args(new Object[]{runId, nodeId}, scope.args()));
        if (lane.get("queue_id") != null) jdbc.update("UPDATE " + MSG + " SET run_id=?,state='RUNNING' WHERE " + SCOPE + " AND id=?",
                args(new Object[]{runId}, args(scope.args(), lane.get("queue_id"))));
    }

    public boolean renew(Scope scope, String runId, String nodeId) {
        return jdbc.update("UPDATE " + LANE + " SET lease_until=DATE_ADD(NOW(3), INTERVAL 45 SECOND) WHERE " + SCOPE
                        + " AND run_id=? AND node_id=? AND state IN ('RUNNING','STOPPING') AND lease_until>NOW(3)",
                args(scope.args(), runId, nodeId)) == 1;
    }

    public boolean owns(Scope scope, String runId, String nodeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)=1 FROM " + LANE + " WHERE " + SCOPE
                + " AND run_id=? AND node_id=? AND state='RUNNING' AND lease_until>NOW(3)", Boolean.class,
                args(scope.args(), runId, nodeId)));
    }

    @Transactional
    public void finish(Scope scope, String runId, boolean waiting, boolean failed) {
        var lane = lock(scope);
        if (!Objects.equals(lane.get("run_id"), runId)) return;
        if (!List.of("RUNNING", "STOPPING").contains(lane.get("state"))) return;
        boolean stopped = "STOPPING".equals(lane.get("state"));
        if (lane.get("queue_id") != null) jdbc.update("UPDATE " + MSG + " SET state=? WHERE " + SCOPE + " AND id=?",
                args(new Object[]{stopped ? "INTERRUPTED" : failed ? "FAILED" : waiting ? "WAITING" : "COMPLETED"}, args(scope.args(), lane.get("queue_id"))));
        jdbc.update("UPDATE " + LANE + " SET state=?,paused=IF(?,TRUE,paused),queue_id=?,"
                + "lease_until=IF(?,NULL,DATE_ADD(NOW(3), INTERVAL 45 SECOND)) WHERE " + SCOPE,
                args(new Object[]{waiting ? "WAITING" : "IDLE", stopped || failed, waiting ? lane.get("queue_id") : null, waiting}, scope.args()));
    }

    @Transactional
    public void pause(Scope scope) {
        lock(scope);
        jdbc.update("UPDATE " + LANE + " SET paused=TRUE,state=IF(state='RUNNING','STOPPING',state) WHERE " + SCOPE, scope.args());
    }

    @Transactional
    public void continueQueue(Scope scope, String nodeId) {
        var lane = lock(scope);
        if (!List.of("IDLE", "INTERRUPTED").contains(lane.get("state"))) throw new IllegalStateException("请先等待当前任务结束或完成审批");
        jdbc.update("UPDATE " + LANE + " SET state='IDLE',paused=FALSE,node_id=?,lease_until=DATE_ADD(NOW(3), INTERVAL 45 SECOND) WHERE " + SCOPE, args(new Object[]{nodeId},scope.args()));
    }

    public void preferNode(Scope scope,String nodeId) {
        jdbc.update("UPDATE " + LANE + " SET node_id=?,lease_until=DATE_ADD(NOW(3), INTERVAL 45 SECOND) WHERE " + SCOPE + " AND state='IDLE'", args(new Object[]{nodeId},scope.args()));
    }

    /** 候选无需信任客户端；最终认领在会话行锁下按 ID 排序。 */
    public List<Scope> candidates(String nodeId) {
        return jdbc.query("SELECT DISTINCT q.tenant_id,q.user_id,q.session_id FROM " + MSG + " q JOIN " + LANE
                + " l ON q.tenant_id=l.tenant_id AND q.user_id=l.user_id AND q.session_id=l.session_id"
                + " WHERE q.state='QUEUED' AND l.paused=FALSE AND l.state='IDLE' AND l.node_id=? LIMIT 20",
                (rs, n) -> new Scope(rs.getLong(1), rs.getLong(2), rs.getLong(3)),nodeId);
    }

    @Transactional
    public Dispatch claim(Scope scope, String runId, String nodeId) {
        var lane = lock(scope);
        if (!"IDLE".equals(lane.get("state")) || !Objects.equals(lane.get("node_id"),nodeId)
                || Boolean.TRUE.equals(lane.get("paused")) || Objects.equals(lane.get("paused"), 1)) return null;
        var next = find(scope, " AND state='QUEUED' ORDER BY id LIMIT 1");
        if (next.isEmpty()) return null;
        Queued message = next.getFirst();
        jdbc.update("UPDATE " + LANE + " SET state='RUNNING',run_id=?,node_id=?,queue_id=?,lease_until=DATE_ADD(NOW(3), INTERVAL 45 SECOND) WHERE " + SCOPE,
                args(new Object[]{runId, nodeId, Long.valueOf(message.id())}, scope.args()));
        jdbc.update("UPDATE " + MSG + " SET state='RUNNING',run_id=? WHERE " + SCOPE + " AND id=?",
                args(new Object[]{runId}, args(scope.args(), Long.valueOf(message.id()))));
        return new Dispatch(scope, new Queued(message.id(), message.requestId(), message.content(), message.options(), "RUNNING", runId));
    }

    @Transactional
    public void expire() {
        // 仅处理少量候选，每个会话重新在行锁下判定，避免过期检查与续租竞争。
        var scopes = jdbc.query("SELECT tenant_id,user_id,session_id FROM " + LANE
                + " l WHERE lease_until<NOW(3) AND (state IN ('RUNNING','STOPPING') OR state='IDLE' AND EXISTS (SELECT 1 FROM "
                + MSG + " q WHERE q.tenant_id=l.tenant_id AND q.user_id=l.user_id AND q.session_id=l.session_id AND q.state='QUEUED'))"
                + " ORDER BY tenant_id,user_id,session_id LIMIT 50",
                (rs,n) -> new Scope(rs.getLong(1),rs.getLong(2),rs.getLong(3)));
        scopes.forEach(this::lock);
    }

    @Transactional
    public void clearSession(long tenantId, long userId, long sessionId) {
        Scope scope = new Scope(tenantId, userId, sessionId);
        var lane=lock(scope);
        if (List.of("RUNNING","STOPPING").contains(lane.get("state"))) throw new IllegalStateException("请先停止当前会话再清空或删除");
        jdbc.update("DELETE FROM " + MSG + " WHERE " + SCOPE, scope.args());
        jdbc.update("DELETE FROM " + LANE + " WHERE " + SCOPE, scope.args());
    }

    private Map<String,Object> lock(Scope scope) {
        // 重复行也先取得排他锁，避免 INSERT IGNORE 的共享锁升级造成并发认领死锁。
        jdbc.update("INSERT INTO " + LANE + " (tenant_id,user_id,session_id) VALUES (?,?,?)"
                + " ON DUPLICATE KEY UPDATE session_id=VALUES(session_id)", scope.args());
        var lane = jdbc.queryForMap("SELECT *,lease_until<NOW(3) AS expired FROM " + LANE + " WHERE " + SCOPE + " FOR UPDATE", scope.args());
        if (Boolean.TRUE.equals(lane.get("expired")) || Objects.equals(lane.get("expired"), 1L) || Objects.equals(lane.get("expired"), 1)) {
            if (lane.get("queue_id") != null) jdbc.update("UPDATE " + MSG + " SET state='INTERRUPTED' WHERE " + SCOPE + " AND id=?",
                    args(scope.args(), lane.get("queue_id")));
            jdbc.update("UPDATE " + LANE + " SET state='INTERRUPTED',paused=TRUE,queue_id=NULL,lease_until=NULL WHERE " + SCOPE, scope.args());
            lane.put("state", "INTERRUPTED"); lane.put("paused", true); lane.put("queue_id", null);
        }
        jdbc.update("UPDATE " + MSG + " SET state='EXPIRED' WHERE " + SCOPE + " AND state='QUEUED' AND created_at<DATE_SUB(NOW(3), INTERVAL 1 DAY)", scope.args());
        return lane;
    }

    private List<Queued> find(Scope scope, String suffix, Object... extra) {
        return jdbc.query("SELECT * FROM " + MSG + " WHERE " + SCOPE + suffix, (rs,n) -> new Queued(rs.getString("id"),
                rs.getString("request_id"),rs.getString("content"),decode(rs.getString("options")),
                rs.getString("state"),rs.getString("run_id")), args(scope.args(),extra));
    }
    private String encode(Options options) {
        try { return mapper.writeValueAsString(options); }
        catch (JsonProcessingException error) { throw new IllegalStateException("排队选项保存失败", error); }
    }
    private Options decode(String json) {
        try { return mapper.readValue(json, Options.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("排队选项读取失败", error); }
    }
    private static void validate(String requestId, String content, Options options) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,64}") || content == null || content.isBlank()
                || content.length()>16000 || options == null || options.fileIds().size()>20
                || options.fileIds().stream().anyMatch(id -> id == null || !id.matches("[0-9]{1,19}")))
            throw new IllegalArgumentException("消息须为1～16000字，附件最多20个，并携带有效请求标识");
    }
    private static Object[] args(Object[] first, Object... rest) {
        Object[] result = java.util.Arrays.copyOf(first,first.length+rest.length);
        System.arraycopy(rest,0,result,first.length,rest.length); return result;
    }
}
