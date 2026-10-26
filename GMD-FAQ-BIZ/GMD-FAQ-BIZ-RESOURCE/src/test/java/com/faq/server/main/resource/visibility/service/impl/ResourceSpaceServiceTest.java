package com.gmd.faq.server.main.resource.visibility.service.impl;

import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import com.gmd.faq.server.main.common.mapper.ResourceSpaceMapper;
import com.gmd.faq.server.main.common.service.ResourceSpaceService;
import com.gmd.faq.server.main.common.util.ResourceOwnershipSupport;
import com.gmd.faq.server.main.common.util.UserUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResourceSpaceServiceTest {
    @Mock ResourceSpaceMapper mapper;
    @InjectMocks ResourceSpaceService service;

    @BeforeEach
    void setUp() {
        UserUtils.setBackgroundUserDetail(UserDetail.builder().id(100L).tenantId(1L).deptId(2L)
                .authType("ACCOUNT").perms(List.of()).build());
        lenient().when(mapper.departments()).thenReturn(List.of(
                Map.of("id", 1L, "parent_id", 0L, "name", "公司"),
                Map.of("id", 2L, "parent_id", 1L, "name", "财务"),
                Map.of("id", 3L, "parent_id", 1L, "name", "研发"),
                Map.of("id", 4L, "parent_id", 2L, "name", "财务下级")));
        lenient().when(mapper.accountDepartment(100L)).thenReturn(2L);
    }

    @AfterEach void tearDown() { UserUtils.setBackgroundUserDetail(null); }

    @Test void ordinaryMemberOnlyEntersOwnDepartmentAndCannotCreatePlatformAsset() {
        assertEquals(List.of("personal", "department:2", "platform"), service.spaces().stream().map(space -> space.key()).toList());
        assertThrows(IllegalStateException.class, () -> service.initialize(new KnowledgeBaseConfig(), "department:3"));
        assertThrows(IllegalStateException.class, () -> service.initialize(new KnowledgeBaseConfig(), "platform"));
    }

    @Test void departmentCreationIgnoresForgedOwnerAndSeparatesHistoricalDepartment() {
        KnowledgeBaseConfig resource = new KnowledgeBaseConfig();
        resource.setOwnerId(999L);
        resource.setOwningDeptId(3L);
        resource.setResourceDeptId(9L);
        service.initialize(resource, "department:2");
        assertEquals("DEPARTMENT", resource.getOwnershipType());
        assertNull(resource.getOwnerId());
        assertEquals(2L, resource.getOwningDeptId());
        assertEquals(100L, resource.getMaintainerId());
        assertEquals(9L, resource.getResourceDeptId());
        assertEquals("PRIVATE", resource.getVisibility());
    }

    @Test void leaderCanCreateInManagedDescendantWithoutAssigningAnOutsideMaintainer() {
        when(mapper.ledDepartments(100L)).thenReturn(List.of(2L));
        KnowledgeBaseConfig resource = new KnowledgeBaseConfig();
        service.initialize(resource, "department:4");
        assertEquals(4L, resource.getOwningDeptId());
        assertNull(resource.getMaintainerId());
        assertTrue(ResourceOwnershipSupport.canManage(resource, 100L, false, Set.of(2L, 4L)));
    }

    @Test void personalCreationDiscardsAllClientOwnershipFields() {
        KnowledgeBaseConfig resource = new KnowledgeBaseConfig();
        resource.setOwnerId(999L);
        resource.setOwnershipType("PLATFORM");
        resource.setMaintainerId(999L);
        resource.setOwningDeptId(3L);
        resource.setVisibility("TENANT");
        ResourceOwnershipSupport.initOwnership(resource);
        assertEquals("PERSONAL", resource.getOwnershipType());
        assertEquals(100L, resource.getOwnerId());
        assertNull(resource.getOwningDeptId());
        assertNull(resource.getMaintainerId());
        assertEquals(2L, resource.getResourceDeptId());
        assertEquals("PRIVATE", resource.getVisibility());
    }

    @Test void sharingDoesNotChangeMaintenanceAndNullOwnerNeverMakesDepartmentAssetPublic() {
        KnowledgeBaseConfig resource = new KnowledgeBaseConfig();
        resource.setOwnershipType("DEPARTMENT");
        resource.setOwningDeptId(2L);
        resource.setMaintainerId(200L);
        resource.setVisibility("TENANT");
        assertFalse(ResourceOwnershipSupport.isSharedPool(resource));
        assertFalse(ResourceOwnershipSupport.canManage(resource, 300L, false, Set.of()));
        assertTrue(ResourceOwnershipSupport.canManage(resource, 200L, false, Set.of()));
        assertTrue(ResourceOwnershipSupport.canManage(resource, 100L, false, Set.of(2L)));
    }
}
