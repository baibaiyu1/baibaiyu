package com.gmd.faq.server.main.agent.service.impl;

import com.gmd.faq.server.main.agent.service.AgentCodeExecutionService;
import com.gmd.faq.server.main.common.cluster.core.MessagePublisher;
import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import com.gmd.faq.server.main.common.entity.CodeExecutionConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodeExecutionRefreshTest {
    @Test void updatingAndDeletingInvalidateDependentAgentsAndFailuresDoNotPublish() {
        var bindings = mock(AgentCodeExecutionService.class); var publisher = mock(MessagePublisher.class);
        var service = spy(new CodeExecutionConfigServiceImpl(mock(JdbcTemplate.class), bindings, publisher));
        when(bindings.getAgentIds(List.of(4L))).thenReturn(List.of(10L, 10L, 11L));
        var config = new CodeExecutionConfig(); config.setId(4L); config.setEnabled(false);
        doReturn(true).when(service).updateById(config);
        assertTrue(service.doUpdate(config));
        verify(publisher).publishAfterCommit(RedisChannelTopic.AGENT_REREGISTER_CHANNEL, "10");
        verify(publisher).publishAfterCommit(RedisChannelTopic.AGENT_REREGISTER_CHANNEL, "11");
        clearInvocations(publisher);
        doReturn(true).when(service).removeByIds(List.of(4L));
        assertTrue(service.deleteConfigs(List.of(4L)));
        verify(publisher).publishAfterCommit(RedisChannelTopic.AGENT_REREGISTER_CHANNEL, "10");
        verify(publisher).publishAfterCommit(RedisChannelTopic.AGENT_REREGISTER_CHANNEL, "11");
        clearInvocations(publisher);
        doReturn(false).when(service).updateById(config);
        assertFalse(service.doUpdate(config)); verifyNoInteractions(publisher);
    }
}
