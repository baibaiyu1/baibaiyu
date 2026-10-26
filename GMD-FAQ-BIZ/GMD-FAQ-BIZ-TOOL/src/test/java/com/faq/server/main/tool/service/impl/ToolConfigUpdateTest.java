package com.gmd.faq.server.main.tool.service.impl;

import com.gmd.faq.server.main.common.cluster.core.MessagePublisher;
import com.gmd.faq.server.main.common.entity.ToolConfig;
import com.gmd.faq.server.main.common.enums.ToolType;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import com.gmd.faq.server.main.tool.mapper.ISkillToolMapper;
import com.gmd.faq.server.main.tool.mapper.ToolMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.gmd.faq.server.main.tool.service.AgentToolService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolConfigUpdateTest {
    @Test void forgedBuiltinTypeCannotBypassOwnership() {
        var service = service();
        var current = tool(ToolType.CUSTOM);
        var update = tool(ToolType.BUILTIN);
        doReturn(current).when(service).getById(1L);
        try (var user = mockStatic(UserUtils.class)) {
            user.when(UserUtils::getResourceAccountId).thenReturn(9L);
            current.setOwnerId(12L);
            assertThrows(IllegalStateException.class, () -> service.doUpdate(update));
            current.setVisibility("TENANT");
            assertThrows(IllegalStateException.class, () -> service.doUpdate(update));
            verify(service, never()).updateById(any(ToolConfig.class));
            verify(service, never()).lambdaUpdate();
        }
    }

    @Test void onlyAdministratorsCanUpdateBuiltinPublicTools() {
        var service = service();
        doReturn(tool(ToolType.BUILTIN)).when(service).getById(1L);
        try (var user = mockStatic(UserUtils.class)) {
            assertThrows(IllegalStateException.class, () -> service.doUpdate(tool(ToolType.BUILTIN)));
            verify(service, never()).lambdaUpdate();
        }
    }

    @Test void ownedCustomToolKeepsPersistedTypeAndUpdatesNativeOptions() {
        var service = service();
        var current = tool(ToolType.CUSTOM);
        current.setOwnerId(9L);
        var update = tool(ToolType.BUILTIN);
        update.setReturnDirect(true);
        doReturn(current).when(service).getById(1L);
        doReturn(true).when(service).updateById(update);
        try (var user = mockStatic(UserUtils.class)) {
            user.when(UserUtils::getResourceAccountId).thenReturn(9L);
            assertTrue(service.doUpdate(update));
            assertEquals(ToolType.CUSTOM, update.getToolType());
            assertTrue(update.getReturnDirect());
            verify(service).updateById(update);
            verify(service, never()).lambdaUpdate();
        }
    }

    @Test void sharingDoesNotRemovePersonalOwnerMaintenance() {
        var service = service();
        var current = tool(ToolType.CUSTOM);
        current.setOwnerId(9L);
        current.setVisibility("TENANT");
        doReturn(current).when(service).getById(1L);
        doReturn(true).when(service).updateById(any(ToolConfig.class));
        try (var user = mockStatic(UserUtils.class)) {
            user.when(UserUtils::getResourceAccountId).thenReturn(9L);
            assertTrue(service.doUpdate(tool(ToolType.CUSTOM)));
        }
    }

    @Test @SuppressWarnings({"rawtypes", "unchecked"})
    void builtinEnabledOnlyUpdatePreservesUnsubmittedMetadataAndNativeOptions() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "builtin-update"), ToolConfig.class);
        var mapper = mock(ToolMapper.class);
        var service = service();
        doReturn(tool(ToolType.BUILTIN)).when(service).getById(1L);
        doReturn(new LambdaUpdateChainWrapper<ToolConfig>(mapper)).when(service).lambdaUpdate();
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        var update = new ToolConfig(); update.setId(1L); update.setEnabled(false);
        try (var user = mockStatic(UserUtils.class)) {
            user.when(UserUtils::isAdmin).thenReturn(true);
            user.when(UserUtils::getResourceAccountId).thenReturn(9L);
            assertTrue(service.doUpdate(update));
            var wrapper = ArgumentCaptor.forClass(Wrapper.class);
            verify(mapper).update(isNull(), wrapper.capture());
            assertTrue(wrapper.getValue().getSqlSet().contains("enabled="));
            assertFalse(wrapper.getValue().getSqlSet().contains("name="));
            assertFalse(wrapper.getValue().getSqlSet().contains("return_direct="));
        }
    }

    private static ToolServiceImpl service() {
        var agentTools = mock(AgentToolService.class);
        when(agentTools.getAgentIds(List.of(1L))).thenReturn(List.of());
        return spy(new ToolServiceImpl(mock(JdbcTemplate.class), mock(ISkillToolMapper.class),
                agentTools, mock(MessagePublisher.class), mock(ResourceVisibilityService.class)));
    }

    private static ToolConfig tool(ToolType type) {
        var tool = new ToolConfig();
        tool.setId(1L);
        tool.setToolType(type);
        tool.setVisibility("PRIVATE");
        return tool;
    }
}
