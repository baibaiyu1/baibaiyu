package com.gmd.faq.server.main.prompt.service.impl;

import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.entity.SystemPromptTemplate;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.prompt.mapper.SystemPromptTemplateMapper;
import com.gmd.faq.server.main.prompt.service.AgentTemplateFollowerSync;
import org.mockito.InOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class SystemPromptTemplateServiceImplTest {
    private final SystemPromptTemplateMapper mapper = mock(SystemPromptTemplateMapper.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AgentTemplateFollowerSync followerSync = mock(AgentTemplateFollowerSync.class);
    private final SystemPromptTemplateServiceImpl service =
            new SystemPromptTemplateServiceImpl(jdbcTemplate, followerSync);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        TenantUtils.setCurrentTenant(3L, "tenant-three");
    }

    @AfterEach
    void tearDown() {
        TenantUtils.clear();
    }

    @Test
    void changedContentSynchronizesFollowersInPersistedTenant() {
        SystemPromptTemplate submitted = template("updated prompt", 999L);
        when(mapper.selectById(10L)).thenReturn(template("old prompt", 3L), template("updated prompt", 3L));
        when(mapper.updateById(submitted)).thenReturn(1);
        when(jdbcTemplate.queryForList(lockSql(), String.class, 10L, 3L)).thenReturn(List.of("old prompt"));

        assertTrue(service.doUpdate(submitted));

        assertEquals(3L, submitted.getTenantId());
        verify(followerSync).syncFollowers(10L, 3L, "updated prompt");
    }

    @Test
    void unchangedContentDoesNotInvalidateFollowers() {
        SystemPromptTemplate submitted = template("same prompt", 3L);
        when(mapper.selectById(10L)).thenReturn(template("same prompt", 3L), template("same prompt", 3L));
        when(mapper.updateById(submitted)).thenReturn(1);
        when(jdbcTemplate.queryForList(lockSql(), String.class, 10L, 3L)).thenReturn(List.of("same prompt"));

        assertTrue(service.doUpdate(submitted));

        verifyNoInteractions(followerSync);
    }

    @Test
    void emptyContentStillSynchronizesFollowers() {
        SystemPromptTemplate submitted = template("", 3L);
        when(mapper.selectById(10L)).thenReturn(template("old prompt", 3L), template("", 3L));
        when(mapper.updateById(submitted)).thenReturn(1);
        when(jdbcTemplate.queryForList(lockSql(), String.class, 10L, 3L)).thenReturn(List.of("old prompt"));

        assertTrue(service.doUpdate(submitted));

        verify(followerSync).syncFollowers(10L, 3L, "");
    }

    @Test
    void failedUpdateDoesNotChangeAgents() {
        SystemPromptTemplate submitted = template("new prompt", 3L);
        when(mapper.selectById(10L)).thenReturn(template("old prompt", 3L));
        when(jdbcTemplate.queryForList(lockSql(), String.class, 10L, 3L)).thenReturn(List.of("old prompt"));
        when(mapper.updateById(submitted)).thenReturn(0);

        assertFalse(service.doUpdate(submitted));

        verifyNoInteractions(followerSync);
    }

    @Test
    void missingTenantContextFailsBeforeAnyWrite() {
        TenantUtils.clear();

        assertThrows(IllegalStateException.class, () -> service.doUpdate(template("new prompt", 3L)));

        verifyNoInteractions(mapper, jdbcTemplate, followerSync);
    }

    @Test
    void differentPersistedTenantCannotBeUpdated() {
        when(mapper.selectById(10L)).thenReturn(template("old prompt", 4L));

        assertFalse(service.doUpdate(template("new prompt", 3L)));

        verify(mapper, never()).updateById(any(SystemPromptTemplate.class));
        verifyNoInteractions(jdbcTemplate, followerSync);
    }

    @Test
    void deletionDetachesAgentsBeforeRemovingTemplate() {
        when(jdbcTemplate.queryForList(deleteLockSql(), Long.class, 10L, 3L)).thenReturn(List.of(10L));
        when(jdbcTemplate.update(deleteSql(), 10L, 3L)).thenReturn(1);

        assertTrue(service.deleteByIds(List.of(10L, 10L)));

        InOrder ordered = inOrder(jdbcTemplate, followerSync);
        ordered.verify(jdbcTemplate).queryForList(deleteLockSql(), Long.class, 10L, 3L);
        ordered.verify(followerSync).detachDeletedTemplates(List.of(10L), 3L);
        ordered.verify(jdbcTemplate).update(deleteSql(), 10L, 3L);
    }

    @Test
    void deletionCannotTouchTemplatesOutsideCurrentTenant() {
        when(jdbcTemplate.queryForList(deleteLockSql(), Long.class, 10L, 3L)).thenReturn(List.of());

        assertFalse(service.deleteByIds(List.of(10L)));

        verifyNoInteractions(followerSync);
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void deletionWithoutTenantFailsBeforeQuerying() {
        TenantUtils.clear();

        assertThrows(IllegalStateException.class, () -> service.deleteByIds(List.of(10L)));

        verifyNoInteractions(jdbcTemplate, followerSync);
    }

    @Test
    void failedDeleteThrowsToTriggerTransactionRollback() {
        when(jdbcTemplate.queryForList(deleteLockSql(), Long.class, 10L, 3L)).thenReturn(List.of(10L));
        when(jdbcTemplate.update(deleteSql(), 10L, 3L)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.deleteByIds(List.of(10L)));

        verify(followerSync).detachDeletedTemplates(List.of(10L), 3L);
    }

    private SystemPromptTemplate template(String content, Long tenantId) {
        SystemPromptTemplate template = new SystemPromptTemplate();
        template.setId(10L);
        template.setTenantId(tenantId);
        template.setContent(content);
        return template;
    }

    private String lockSql() {
        return "SELECT content FROM " + TableConst.PROMPT
                + " WHERE id = ? AND tenant_id = ? FOR UPDATE";
    }

    private String deleteLockSql() {
        return "SELECT id FROM " + TableConst.PROMPT
                + " WHERE id IN (?) AND tenant_id = ? FOR UPDATE";
    }

    private String deleteSql() {
        return "DELETE FROM " + TableConst.PROMPT
                + " WHERE id IN (?) AND tenant_id = ?";
    }
}
