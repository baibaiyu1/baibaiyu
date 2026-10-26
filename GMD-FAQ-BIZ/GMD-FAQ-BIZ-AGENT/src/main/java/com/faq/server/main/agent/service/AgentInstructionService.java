package com.gmd.faq.server.main.agent.service;

import com.gmd.faq.server.main.common.consts.TableConst;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 持久收件箱：明确区分接收、注入与应用；不重放上次进程未确认的指令。 */
@Service
@RequiredArgsConstructor
public class AgentInstructionService {
    private final JdbcTemplate jdbc;
    private static final String RUN = TableConst.AGENT_INSTRUCTION_RUN;
    private static final String MSG = TableConst.AGENT_INSTRUCTION;
    private static final String SCOPE = "tenant_id=? AND user_id=? AND session_id=? AND run_id=?";

    public record Scope(long tenantId, long userId, long sessionId, String runId) {
        public Scope {
            if (tenantId < 1 || userId < 1 || sessionId < 1 || runId == null
                    || !runId.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("无效的运行身份");
        }
        Object[] args() { return new Object[]{tenantId, userId, sessionId, runId}; }
    }

    public record Instruction(String id, String runId, String requestId, String content, String state) {}

    public boolean isActive(Scope scope) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + RUN + " WHERE " + SCOPE
                + " AND state='ACTIVE' AND expires_at>?", Long.class, append(scope.args(), Timestamp.from(Instant.now())));
        return count != null && count == 1;
    }

    @Transactional
    public void begin(Scope scope, long agentId) {
        jdbc.update("INSERT INTO " + RUN + " (tenant_id,user_id,session_id,run_id,agent_id,state,expires_at) VALUES (?,?,?,?,?,'ACTIVE',?)",
                scope.tenantId, scope.userId, scope.sessionId, scope.runId, agentId,
                Timestamp.from(Instant.now().plusSeconds(86400)));
    }

    @Transactional
    public Instruction receive(Scope scope, String requestId, String content) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,64}")
                || content == null || content.isBlank() || content.length() > 4000)
            throw new IllegalArgumentException("补充要求不能为空、不能超过4000字，且必须携带有效请求标识");
        String state = lock(scope);
        List<Instruction> duplicate = query(scope, " AND request_id=?", requestId);
        if (!duplicate.isEmpty()) {
            if (!Objects.equals(duplicate.getFirst().content(), content))
                throw new IllegalArgumentException("同一请求标识不能用于不同补充要求");
            return duplicate.getFirst();
        }
        if (!"ACTIVE".equals(state)) throw new IllegalStateException("当前运行已结束或正在等待确认，不能接收补充要求");
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + MSG + " WHERE " + SCOPE, Long.class, scope.args());
        if (count != null && count >= 50) throw new IllegalStateException("本轮补充要求已达50条上限");
        jdbc.update("INSERT INTO " + MSG + " (tenant_id,user_id,session_id,run_id,request_id,content) VALUES (?,?,?,?,?,?)",
                scope.tenantId, scope.userId, scope.sessionId, scope.runId, requestId, content);
        return query(scope, " AND request_id=?", requestId).getFirst();
    }

    @Transactional
    public List<Instruction> claim(Scope scope) {
        if (!"ACTIVE".equals(lock(scope))) return List.of();
        List<Instruction> pending = query(scope, " AND state='RECEIVED'");
        jdbc.update("UPDATE " + MSG + " SET state='INJECTING' WHERE " + SCOPE + " AND state='RECEIVED'", scope.args());
        return pending;
    }

    @Transactional
    public void applied(Scope scope, List<Instruction> messages) {
        for (Instruction msg : messages) jdbc.update("UPDATE " + MSG
                        + " SET state='APPLIED' WHERE " + SCOPE + " AND id=? AND state='INJECTING'",
                append(scope.args(), Long.valueOf(msg.id())));
    }

    @Transactional
    public void finish(Scope scope, String reason) {
        lock(scope);
        jdbc.update("UPDATE " + RUN + " SET state=? WHERE " + SCOPE, append(new Object[]{reason}, scope.args()));
        jdbc.update("UPDATE " + MSG + " SET state='CANCELLED' WHERE " + SCOPE + " AND state='RECEIVED'", scope.args());
        jdbc.update("UPDATE " + MSG + " SET state='UNCONFIRMED' WHERE " + SCOPE + " AND state='INJECTING'", scope.args());
    }

    @Transactional
    public List<Instruction> list(long tenantId, long userId, long sessionId) {
        // 过期运行只更新当前调用者自己的记录；不会把未确认消息投递到其他运行。
        jdbc.update("UPDATE " + MSG + " SET state=CASE WHEN state='INJECTING' THEN 'UNCONFIRMED' ELSE 'EXPIRED' END"
                + " WHERE tenant_id=? AND user_id=? AND session_id=? AND state IN ('RECEIVED','INJECTING')"
                + " AND created_at < ?", tenantId, userId, sessionId, Timestamp.from(Instant.now().minusSeconds(86400)));
        return jdbc.query("SELECT * FROM " + MSG + " WHERE tenant_id=? AND user_id=? AND session_id=? ORDER BY id DESC LIMIT 100",
                (rs, row) -> new Instruction(rs.getString("id"), rs.getString("run_id"), rs.getString("request_id"),
                        rs.getString("content"), rs.getString("state")), tenantId, userId, sessionId);
    }

    @Transactional
    public void clearSession(long tenantId, long userId, long sessionId) {
        jdbc.queryForList("SELECT run_id FROM " + RUN
                + " WHERE tenant_id=? AND user_id=? AND session_id=? FOR UPDATE", String.class,
                tenantId, userId, sessionId);
        jdbc.update("DELETE FROM " + MSG + " WHERE tenant_id=? AND user_id=? AND session_id=?", tenantId, userId, sessionId);
        jdbc.update("DELETE FROM " + RUN + " WHERE tenant_id=? AND user_id=? AND session_id=?", tenantId, userId, sessionId);
    }

    private String lock(Scope scope) {
        List<String> states = jdbc.query("SELECT state,expires_at FROM " + RUN + " WHERE " + SCOPE + " FOR UPDATE",
                (rs, row) -> rs.getTimestamp("expires_at").toInstant().isBefore(Instant.now()) ? "EXPIRED" : rs.getString("state"), scope.args());
        return states.isEmpty() ? "MISSING" : states.getFirst();
    }

    private List<Instruction> query(Scope scope, String suffix, Object... more) {
        return jdbc.query("SELECT * FROM " + MSG + " WHERE " + SCOPE + suffix + " ORDER BY id",
                (rs, row) -> new Instruction(rs.getString("id"), rs.getString("run_id"), rs.getString("request_id"),
                        rs.getString("content"), rs.getString("state")), append(scope.args(), more));
    }

    private static Object[] append(Object[] first, Object... rest) {
        Object[] result = java.util.Arrays.copyOf(first, first.length + rest.length);
        System.arraycopy(rest, 0, result, first.length, rest.length);
        return result;
    }
}
