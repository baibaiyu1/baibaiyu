package com.gmd.faq.server.main.engine.skill;

import com.gmd.faq.server.main.common.cluster.core.MessagePublisher;
import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import com.gmd.faq.server.main.common.entity.*;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.engine.agent.AgentExecutionRole;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import com.gmd.faq.server.main.skill.service.*;
import com.gmd.faq.server.main.skill.service.impl.SkillPackageServiceImpl;
import com.gmd.faq.server.main.tool.service.ToolService;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillToolAuthorizationTest {
    private final AgentSkillPackageService bindings = mock(AgentSkillPackageService.class);
    private final SkillToolService toolBindings = mock(SkillToolService.class);
    private final ResourceVisibilityService visibility = mock(ResourceVisibilityService.class);
    private final MessagePublisher publisher = mock(MessagePublisher.class);

    @Test void privateToolsAreRejectedBeforeAssociationsChange() {
        var service = service();
        doThrow(new IllegalStateException("无权使用")).when(visibility).assertUsable("TOOL", 77L);
        try (var user = mockStatic(UserUtils.class)) {
            user.when(UserUtils::getResourceAccountId).thenReturn(9L);
            assertThrows(IllegalStateException.class, () -> service.updateTools(30L, List.of(77L)));
            verifyNoInteractions(toolBindings, publisher);
        }
    }

    @Test void bindingChangesNotifyEveryDependentAgentAfterAuthorization() {
        var service = service();
        when(toolBindings.saveSkillTool(30L, List.of(77L))).thenReturn(true);
        when(bindings.getAgentIds(List.of(30L))).thenReturn(List.of(10L, 11L));
        try (var user = mockStatic(UserUtils.class)) {
            user.when(UserUtils::getResourceAccountId).thenReturn(9L);
            assertTrue(service.updateTools(30L, List.of(77L)));
            var order = inOrder(visibility, toolBindings, publisher);
            order.verify(visibility).assertUsable("TOOL", 77L);
            order.verify(toolBindings).saveSkillTool(30L, List.of(77L));
            order.verify(publisher).publishAfterCommit(RedisChannelTopic.AGENT_REREGISTER_CHANNEL, "10");
            order.verify(publisher).publishAfterCommit(RedisChannelTopic.AGENT_REREGISTER_CHANNEL, "11");
        }
    }

    @Test void unauthorizedLegacyBindingsCannotEnterTheRuntimeToolkit() {
        var packages = mock(SkillPackageService.class); var files = mock(SkillFileService.class); var tools = mock(ToolService.class);
        var factory = new SkillBoxFactory(packages, files, bindings, toolBindings, tools, visibility);
        when(packages.listByIds(List.of(30L))).thenReturn(List.of(skill()));
        when(files.listBySkillId(30L)).thenReturn(List.of());
        when(toolBindings.getToolIds(30L)).thenReturn(List.of(77L));
        doThrow(new IllegalStateException("旧关联已无使用权")).when(visibility).assertUsableForAccount("TOOL", 77L, 9L);
        Toolkit toolkit = new Toolkit();
        assertThrows(IllegalStateException.class, () -> factory.getSkillBox(List.of(30L), toolkit));
        verify(tools, never()).listByIds(any()); assertTrue(toolkit.getToolNames().isEmpty());
    }

    private SkillPackageServiceImpl service() {
        var service = spy(new SkillPackageServiceImpl(mock(JdbcTemplate.class), bindings, toolBindings, publisher, visibility));
        doReturn(skill()).when(service).getById(30L); return service;
    }
    private SkillPackage skill() {
        var skill = new SkillPackage(); skill.setId(30L); skill.setOwnershipType("PERSONAL"); skill.setOwnerId(9L);
        skill.setName("owned"); skill.setEnabled(true); return skill;
    }
}
