package com.gmd.faq.server.main.tool.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.gmd.faq.server.main.common.entity.AgentTool;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.util.TenantUtils;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolDependencyRefreshTest {
    @Test void indirectSkillDependenciesAreIncludedAndTenantIsAlwaysBound() {
        var jdbc = mock(JdbcTemplate.class); var service = spy(new AgentToolServiceImpl(jdbc));
        @SuppressWarnings("unchecked") LambdaQueryChainWrapper<AgentTool> query = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
        doReturn(query).when(service).lambdaQuery();
        doReturn(query).when(query).in(any(), anyCollection());
        when(query.list()).thenReturn(List.of(new AgentTool(null, 7L, 10L, 77L)));
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(7L), eq(77L))).thenReturn(List.of(10L, 11L));
        try {
            TenantUtils.setCurrentTenant(7L, "test");
            assertEquals(List.of(10L, 11L), service.getAgentIds(List.of(77L)));
            var sql = ArgumentCaptor.forClass(String.class);
            verify(jdbc).queryForList(sql.capture(), eq(Long.class), eq(7L), eq(77L));
            assertTrue(sql.getValue().contains(TableConst.SKILL_TOOL));
            assertTrue(sql.getValue().contains(TableConst.AGENT_SKILL));
            assertTrue(sql.getValue().contains("a.tenant_id = s.tenant_id"));
            assertTrue(sql.getValue().contains("a.tenant_id = ?"));
        } finally { TenantUtils.clear(); }
        assertThrows(IllegalStateException.class, () -> service.getAgentIds(List.of(77L)));
    }
}
