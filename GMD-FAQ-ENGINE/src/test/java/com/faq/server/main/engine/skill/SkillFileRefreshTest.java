package com.gmd.faq.server.main.engine.skill;

import com.gmd.faq.server.main.common.entity.SkillFile;
import com.gmd.faq.server.main.skill.mapper.SkillFileMapper;
import com.gmd.faq.server.main.skill.service.SkillPackageService;
import com.gmd.faq.server.main.skill.service.impl.SkillFileServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillFileRefreshTest {
    @Test void editingPersistedResourcesRefreshesAgentsOnlyAfterASuccessfulWrite() {
        var packages = mock(SkillPackageService.class); var mapper = mock(SkillFileMapper.class);
        var files = spy(new SkillFileServiceImpl(packages));
        ReflectionTestUtils.setField(files, "baseMapper", mapper);
        var current = new SkillFile(); current.setId(77L); current.setSkillId(30L);
        doReturn(current).when(files).getById(77L);
        when(mapper.updateById(any(SkillFile.class))).thenReturn(1);
        assertTrue(files.updateContent(77L, "更新技能资源"));
        var order = inOrder(mapper, packages);
        order.verify(mapper).updateById(any(SkillFile.class)); order.verify(packages).refreshBoundAgents(30L);
        clearInvocations(packages);
        when(mapper.updateById(any(SkillFile.class))).thenReturn(0);
        assertFalse(files.updateContent(77L, "失败内容")); verifyNoInteractions(packages);
    }
}
