package com.gmd.faq.server.main.runtime.state;

import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.util.AgentStateScope;
import io.agentscope.core.state.State;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.LegacyStateLoader;
import io.agentscope.core.state.VersionedState;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** 在新命名空间中保存状态，仅为会话真实归属人读取 1.x 的原始键。 */
public class CompatibleMysqlAgentStateStore extends MysqlAgentStateStore {
    private final JdbcTemplate jdbc;

    public CompatibleMysqlAgentStateStore(DataSource source, String databaseName) {
        super(source, databaseName, TableConst.AGENT_SCOPE_SESSIONS, false);
        jdbc = new JdbcTemplate(source);
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        if (type == AgentState.class && "agent_state".equals(key)) {
            return Optional.ofNullable(getVersioned(userId, sessionId, key, type).value());
        }
        Optional<T> current = super.get(userId, sessionId, key, type);
        if (current.isPresent() || super.exists(userId, sessionId) || !ownsLegacySession(userId, sessionId)) return current;
        List<T> legacy = readLegacy(sessionId, key, type);
        return legacy.isEmpty() ? Optional.empty() : Optional.of(legacy.getFirst());
    }

    @Override
    public <T extends State> VersionedState<T> getVersioned(String userId, String sessionId, String key, Class<T> type) {
        VersionedState<T> current = super.getVersioned(userId, sessionId, key, type);
        if (current.isPresent() || type != AgentState.class || !"agent_state".equals(key)
                || super.exists(userId, sessionId) || !ownsLegacySession(userId, sessionId)) return current;
        LegacyStateLoader.LegacyLoadResult loaded = LegacyStateLoader.loadFromLegacySessionWithPresence(this, userId, sessionId);
        if (!loaded.found()) return current;
        List<LegacyPlan> plans = readLegacy(sessionId, "planNotebook_state", LegacyPlan.class);
        AgentState migrated = LegacyAgentStateMigration.migrate(loaded.state(), userId, sessionId,
                plans.isEmpty() ? null : plans.getFirst().currentPlan());
        return new VersionedState<>(type.cast(migrated), 0L);
    }

    @Override
    public void delete(String userId, String sessionId) {
        boolean ownsLegacy = ownsLegacySession(userId, sessionId);
        super.delete(userId, sessionId);
        if (ownsLegacy) jdbc.update("DELETE FROM " + TableConst.AGENT_SCOPE_SESSIONS + " WHERE session_id = ?", sessionId);
        String prefix = (userId == null || userId.isBlank() ? "__anon__" : userId) + ":" + sessionId + ":subagent:";
        jdbc.update("DELETE FROM " + TableConst.AGENT_SCOPE_SESSIONS + " WHERE session_id LIKE ? ESCAPE '!'",
                prefix.replace("!", "!!").replace("_", "!_").replace("%", "!%") + "%");
    }

    private record LegacyPlan(JsonNode currentPlan) implements State {}

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> type) {
        List<T> current = super.getList(userId, sessionId, key, type);
        if (!current.isEmpty() || super.exists(userId, sessionId)
                || !ownsLegacySession(userId, sessionId)) return current;
        return readLegacy(sessionId, key, type);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return super.exists(userId, sessionId) || ownsLegacySession(userId, sessionId)
                && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM "
                        + TableConst.AGENT_SCOPE_SESSIONS + " WHERE session_id = ?)", Boolean.class, sessionId));
    }

    private boolean ownsLegacySession(String userId, String sessionId) {
        if (userId == null || sessionId == null || !sessionId.matches("[0-9]{1,19}")) return false;
        final long id;
        try { id = Long.parseLong(sessionId); } catch (NumberFormatException error) { return false; }
        List<String> owners = jdbc.query("SELECT tenant_id, user_id FROM " + TableConst.CHAT_SESSION
                        + " WHERE id = ?",
                (rs, index) -> AgentStateScope.userId(rs.getLong("tenant_id"), rs.getLong("user_id")), id);
        return owners.size() == 1 && userId.equals(owners.getFirst());
    }

    private <T extends State> List<T> readLegacy(String sessionId, String key, Class<T> type) {
        return jdbc.query("SELECT state_data FROM " + TableConst.AGENT_SCOPE_SESSIONS
                        + " WHERE session_id = ? AND state_key = ? ORDER BY item_index",
                (rs, index) -> JsonUtils.getJsonCodec().fromJson(rs.getString("state_data"), type), sessionId, key);
    }
}
