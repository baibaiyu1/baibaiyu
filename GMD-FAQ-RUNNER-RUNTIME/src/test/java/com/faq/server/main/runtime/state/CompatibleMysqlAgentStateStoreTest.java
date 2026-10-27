package com.gmd.faq.server.main.runtime.state;

import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.util.AgentStateScope;
import com.gmd.faq.server.main.common.entity.Attach;
import com.gmd.faq.server.main.common.vo.AccountVO;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.engine.tool.DocumentTextAccess;
import com.gmd.faq.server.main.resource.service.AttachService;
import io.agentscope.core.message.*;
import io.agentscope.core.state.*;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 在独立临时库验证真实 MySQL 兼容读取和乐观锁；普通构建未配置数据库时跳过。 */
@EnabledIfEnvironmentVariable(named = "AGENTSCOPE_TEST_MYSQL_URL", matches = ".*/gmd_agentscope_upgrade_check.*")
class CompatibleMysqlAgentStateStoreTest {
    private static final String SESSION = "908888800001";
    private static final String USER = AgentStateScope.userId(71L, 72L);
    private JdbcTemplate jdbc;
    private CompatibleMysqlAgentStateStore store;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource(System.getenv("AGENTSCOPE_TEST_MYSQL_URL"),
                System.getenv("AGENTSCOPE_TEST_MYSQL_USER"), System.getenv("AGENTSCOPE_TEST_MYSQL_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        store = new CompatibleMysqlAgentStateStore(source, jdbc.queryForObject("SELECT DATABASE()", String.class));
        jdbc.update("DELETE FROM " + TableConst.AGENT_SCOPE_SESSIONS);
        jdbc.update("DELETE FROM " + TableConst.CHAT_SESSION + " WHERE id = ?", SESSION);
        jdbc.update("INSERT INTO " + TableConst.CHAT_SESSION + " (id,tenant_id,user_id,agent_id,title) VALUES (?,?,?,?,?)",
                SESSION, 71L, 72L, 1L, "升级兼容测试");
    }

    @Test void migratesLegacyHistoryApprovalAndPlanOnlyForTheOwner() {
        // 使用 1.x 的序列化布局（工具块没有 state 字段，暂停原因保存在 metadata）。
        String legacyMessage = """
            {"id":"legacy-message","name":"agent","role":"ASSISTANT","timestamp":"2026-10-01 10:00:00",
             "content":[{"type":"tool_use","id":"legacy-call","name":"create_plan",
             "input":{"subtasks":[{"name":"分析","description":"保留说明","expectedOutcome":"完成报告"}]}}],
             "metadata":{"agentscope_generate_reason":"REASONING_STOP_REQUESTED","custom":"preserved"}}
            """;
        seed("memory_messages", 0, legacyMessage);
        seed("toolkit_activeGroups", 0, "{\"activeGroups\":[\"files\"]}");
        seed("planNotebook_state", 0, """
            {"currentPlan":{"name":"历史计划","subtasks":[
             {"name":"分析","description":"保留说明","state":"IN_PROGRESS","expectedOutcome":"完成报告"}]}}
            """);
        assertFalse(store.getVersioned(AgentStateScope.userId(71L, 73L), SESSION, "agent_state", AgentState.class).isPresent());
        assertFalse(store.getVersioned(AgentStateScope.userId(74L, 72L), SESSION, "agent_state", AgentState.class).isPresent());
        var loaded = store.getVersioned(USER, SESSION, "agent_state", AgentState.class);
        assertTrue(loaded.isPresent()); assertEquals(0L, loaded.version());
        var message = loaded.value().getContext().getFirst();
        assertEquals("legacy-message", message.getId());
        assertEquals("preserved", message.getMetadata().get("custom"));
        var tool = message.getContentBlocks(ToolUseBlock.class).getFirst();
        assertEquals(ToolCallState.ASKING, tool.getState());
        assertEquals("todo_write", tool.getName());
        assertTrue(tool.getInput().containsKey("todos"));
        assertEquals(Task.State.IN_PROGRESS, loaded.value().getTasksContext().getTasks().getFirst().getState());
        assertEquals(List.of("files"), loaded.value().getToolContext().getActivatedGroups());
        assertEquals(1L, store.saveIfVersion(USER, SESSION, "agent_state", loaded.value(), loaded.version()));
        assertEquals(1L, store.getVersioned(USER, SESSION, "agent_state", AgentState.class).version());
        assertTrue(store.getList(USER, SESSION, "memory_messages", Msg.class).isEmpty(), "新键存在后不得回退旧键");
        assertEquals(AgentStateStore.UNVERSIONED, store.saveIfVersion(USER, SESSION, "agent_state", loaded.value(), 0));
    }

    @Test void deletionClearsParentAndChildSlotsWithoutTouchingOtherOwners() {
        store.save(USER, SESSION, "agent_state", state(USER, SESSION));
        store.save(USER, SESSION + ":subagent:test:child", "agent_state", state(USER, SESSION));
        String other = AgentStateScope.userId(71L, 73L);
        store.save(other, SESSION, "agent_state", state(other, SESSION));
        seed("memory_messages", 0, JsonUtils.getJsonCodec().toJson(Msg.builder().role(MsgRole.USER).textContent("旧消息").build()));
        store.delete(USER, SESSION);
        assertFalse(store.exists(USER, SESSION));
        assertFalse(store.exists(USER, SESSION + ":subagent:test:child"));
        assertTrue(store.exists(other, SESSION));
    }

    @Test void attachmentGrantsReloadIndependentlyOfLegacyHistoryAndRequireTheSameOwner() {
        var attachments = mock(AttachService.class);
        var file = new Attach(); file.setId(77L); file.setTenantId(71L); file.setCreateBy(72L);
        when(attachments.getById(77L)).thenReturn(file);
        var user = new AccountVO(); user.setId(72L);
        var owner = new AgentContext(); owner.setTenantId(71L); owner.setUserInfo(user); owner.setThreadId(SESSION);
        owner.setFileIds(List.of("77"));
        var access = new DocumentTextAccess(attachments);
        ReflectionTestUtils.setField(access, "stateStore", store);
        seed("memory_messages", 0, JsonUtils.getJsonCodec().toJson(Msg.builder().role(MsgRole.USER).textContent("旧消息").build()));
        access.authorizeForSession(owner);

        var reloaded = new DocumentTextAccess(attachments);
        ReflectionTestUtils.setField(reloaded, "stateStore", store);
        owner.setFileIds(List.of());
        assertDoesNotThrow(() -> reloaded.requireForSession(77L, owner));
        assertTrue(store.getVersioned(USER, SESSION, "agent_state", AgentState.class).isPresent(),
                "附件授权不能遮蔽尚未迁移的旧会话");
        owner.setThreadId(SESSION + "other");
        assertThrows(IllegalStateException.class, () -> reloaded.requireForSession(77L, owner));
        owner.setThreadId(SESSION);
        file.setCreateBy(73L);
        assertThrows(IllegalStateException.class, () -> reloaded.requireForSession(77L, owner));
        file.setCreateBy(72L);
        store.delete(USER, SESSION + ":attachments");
        assertThrows(IllegalStateException.class, () -> reloaded.requireForSession(77L, owner));
    }

    private static AgentState state(String user, String session) {
        return AgentState.builder().userId(user).sessionId(session).build();
    }

    private void seed(String key, int index, String json) {
        jdbc.update("INSERT INTO " + TableConst.AGENT_SCOPE_SESSIONS
                + " (session_id,state_key,item_index,state_data) VALUES (?,?,?,?)", SESSION, key, index, json);
    }
}
