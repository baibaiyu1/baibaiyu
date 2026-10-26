package com.gmd.faq.server.main.runtime.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.agent.service.*;
import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.ChatSession;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.engine.model.ChatModelFactory;
import com.gmd.faq.server.main.engine.telemetry.AgentTelemetry;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VoiceConversationTest {
    private final SessionAccessGuard guard = mock(SessionAccessGuard.class);
    private final AgentDefinitionService agents = mock(AgentDefinitionService.class);
    private final ResourceVisibilityService visibility = mock(ResourceVisibilityService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final VoiceConversationService service = new VoiceConversationService(guard, agents, visibility,
            mock(ChatSessionService.class), mock(ChatModelFactory.class), mock(AgentConversationQueue.class), json,
            mock(AgentModelResiliencePolicy.class), mock(AgentTelemetry.class));

    private UserDetail user(String auth) {
        return UserDetail.builder().id(7L).tenantId(8L).authType(auth)
                .perms(List.of("agent:chat:instruct", "system:admin")).build();
    }

    @Test void rejectsForeignSessionsEvenForAdminAndRejectsServiceCredentials() {
        ChatSession session = new ChatSession(); session.setId(1L); session.setTenantId(8L); session.setUserId(99L);
        when(guard.requireActiveWrite(1L)).thenReturn(session);
        UserUtils.withUserDetail(user(UserUtils.AUTH_ACCOUNT), () -> {
            assertThrows(IllegalStateException.class, () -> service.requireOwnSession("1"));
            session.setUserId(7L); session.setTenantId(9L);
            assertThrows(IllegalStateException.class, () -> service.requireOwnSession("1"));
            assertThrows(IllegalArgumentException.class, () -> service.requireOwnSession("temporary"));
            assertThrows(IllegalArgumentException.class, () -> service.requireOwnSession("9999999999999999999"));
            return null;
        });
        for (String auth : List.of(UserUtils.AUTH_SK, UserUtils.AUTH_CHAT_KEY))
            UserUtils.withUserDetail(user(auth), () -> {
                assertThrows(IllegalStateException.class, () -> service.requireOwnSession("1")); return null;
            });
        verifyNoInteractions(agents, visibility);
    }

    @Test void rechecksAgentAndResourceUseOnEveryRequest() {
        ChatSession session = new ChatSession(); session.setId(1L); session.setTenantId(8L); session.setUserId(7L); session.setAgentId(4L);
        AgentDefinition agent = new AgentDefinition(); agent.setId(4L); agent.setEnabled(true); agent.setPublished(true); agent.setModelConfigId(5L);
        when(guard.requireActiveWrite(1L)).thenReturn(session); when(agents.getById(4L)).thenReturn(agent);
        UserUtils.withUserDetail(user(UserUtils.AUTH_ACCOUNT), () -> {
            assertSame(session, service.requireOwnSession("1"));
            doThrow(new IllegalStateException("无使用权")).when(visibility).assertUsable("AGENT", 4L);
            assertThrows(IllegalStateException.class, () -> service.requireOwnSession("1"));
            return null;
        });
        verify(visibility, times(2)).assertUsable("AGENT", 4L);
    }

    @Test void modelCannotExpandExecutionContentOrCreateCommandsInSummary() {
        var turn = new VoiceConversationService.Turn("utterance-1", "只查资料，不执行写入", List.of(), false);
        var reply = service.parse(turn, "{\"speech\":\"好的\",\"action\":\"TASK\",\"content\":\"删除所有数据\"}", null);
        assertEquals(turn.text(), reply.content());
        assertEquals("TASK", reply.action());
        var summary = new VoiceConversationService.Turn("summary-1", "说明结果", List.of(), true);
        assertEquals("NONE", service.parse(summary, "{\"speech\":\"有结果了\",\"action\":\"TASK\"}", null).action());
        assertEquals("TASK", service.parse(turn, "```json\n{\"speech\":\"收到\",\"action\":\"INSTRUCTION\"}\n```", null).action());
        assertEquals("active-run", service.parse(turn, "{\"speech\":\"收到\",\"action\":\"INSTRUCTION\"}", "active-run").runId());
        assertEquals("NONE", service.parse(turn, "{\"speech\":\"已确认\",\"action\":\"APPROVE\"}", null).action());
        assertEquals("NONE", service.parse(turn, "not-json", null).action());
    }

    @Test void roomTokenRestrictsMicrophoneAndNamedAgentWithoutExposingBusinessIdentity() throws Exception {
        VoiceConfiguration config = new VoiceConfiguration(); config.setApiKey("test-key");
        config.setApiSecret("012345678901234567890123456789012345678901234567");
        VoiceRoomTokens tokens = new VoiceRoomTokens(config, json);
        var first = tokens.issue(); var second = tokens.issue();
        assertNotEquals(first.roomName(), second.roomName());
        var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(config.getApiSecret().getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(first.participantToken()).getPayload();
        var node = json.valueToTree(claims);
        assertEquals(first.roomName(), node.at("/video/room").asText());
        assertEquals("microphone", node.at("/video/canPublishSources/0").asText());
        assertEquals(1, node.at("/video/canPublishSources").size());
        assertFalse(node.at("/video/canUpdateOwnMetadata").asBoolean());
        assertEquals(config.getAgentName(), node.at("/roomConfig/agents/0/agentName").asText());
        assertEquals(first.participantIdentity(), json.readTree(node.at("/roomConfig/agents/0/metadata").asText()).path("voiceParticipant").asText());
        assertTrue(first.expiresAt() - System.currentTimeMillis() <= 600_000);
        config.setApiSecret(""); assertThrows(IllegalStateException.class, tokens::issue);
    }
}
