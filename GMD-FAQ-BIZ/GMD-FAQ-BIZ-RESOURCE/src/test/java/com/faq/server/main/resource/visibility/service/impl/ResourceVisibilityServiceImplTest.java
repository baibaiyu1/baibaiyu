package com.gmd.faq.server.main.resource.visibility.service.impl;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.entity.ResourceAccessGrant;
import com.gmd.faq.server.main.common.entity.ResourceDepartmentGrant;
import com.gmd.faq.server.main.common.mapper.PermsMapper;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.resource.visibility.mapper.ResourceAccessGrantMapper;
import com.gmd.faq.server.main.resource.visibility.mapper.ResourceDepartmentGrantMapper;
import com.gmd.faq.server.main.resource.visibility.mapper.ResourceFavoriteMapper;
import com.gmd.faq.server.main.resource.visibility.mapper.ResourceGovernanceMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceVisibilityServiceImplTest {

    @Mock private ResourceFavoriteMapper favoriteMapper;
    @Mock private ResourceGovernanceMapper governanceMapper;
    @Mock private ResourceAccessGrantMapper grantMapper;
    @Mock private ResourceDepartmentGrantMapper departmentGrantMapper;
    @Mock private PermsMapper permsMapper;
    @Mock private com.gmd.faq.server.main.common.cluster.core.MessagePublisher messagePublisher;
    @Mock private com.gmd.faq.server.main.common.service.ResourceSpaceService resourceSpaceService;
    @InjectMocks private ResourceVisibilityServiceImpl service;

    @BeforeEach
    void setUp() {
        if (TableInfoHelper.getTableInfo(ResourceDepartmentGrant.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "resource-grant-test"),
                    ResourceDepartmentGrant.class);
        }
        setUser(false);
        when(governanceMapper.selectDeptIdAndParent()).thenReturn(List.of(
                Map.of("id", 1L, "parent_id", 0L),
                Map.of("id", 2L, "parent_id", 1L),
                Map.of("id", 3L, "parent_id", 1L),
                Map.of("id", 4L, "parent_id", 2L)));
        lenient().when(governanceMapper.selectAccountDeptId(100L)).thenReturn(2L);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        TenantUtils.clear();
    }

    @Test
    void leaderCanViewDescendantPrivateResourceButCannotUseIt() {
        when(governanceMapper.selectLedDepartmentIds(100L)).thenReturn(List.of(2L));
        when(governanceMapper.selectAgentAccessRow(10L))
                .thenReturn(List.of(resource(200L, "PRIVATE", 4L)));

        assertTrue(service.canView("AGENT", 10L));
        assertFalse(service.canUse("AGENT", 10L));
    }

    @Test
    void siblingPublicResourceRequiresExplicitGrant() {
        when(governanceMapper.selectLedDepartmentIds(100L)).thenReturn(List.of());
        when(governanceMapper.selectAgentAccessRow(11L))
                .thenReturn(List.of(resource(200L, "TENANT", 3L)));
        assertFalse(service.canView("AGENT", 11L));
        assertFalse(service.canUse("AGENT", 11L));

        ResourceAccessGrant grant = new ResourceAccessGrant();
        grant.setAllowUse(false);
        when(grantMapper.selectOne(any())).thenReturn(grant);
        assertTrue(service.canView("AGENT", 11L));
        assertFalse(service.canUse("AGENT", 11L));

        grant.setAllowUse(true);
        assertTrue(service.canUse("AGENT", 11L));
    }

    @Test
    void ancestorDepartmentGrantAppliesToCurrentMemberAndAllowsUse() {
        when(governanceMapper.selectLedDepartmentIds(100L)).thenReturn(List.of());
        when(governanceMapper.selectAgentAccessRow(17L))
                .thenReturn(List.of(resource(200L, "PRIVATE", 3L)));
        ResourceDepartmentGrant grant = new ResourceDepartmentGrant();
        grant.setDepartmentId(1L);
        grant.setAllowUse(true);
        when(departmentGrantMapper.selectList(any())).thenReturn(List.of(grant));

        assertTrue(service.canView("AGENT", 17L));
        assertTrue(service.canUse("AGENT", 17L));
        verify(departmentGrantMapper, times(2)).selectList(argThat(query ->
                queriesDepartments(query, List.of(1L, 2L), 3L)));
    }

    @Test
    void viewOnlyDepartmentGrantReachesDescendantButDoesNotPermitUse() {
        when(governanceMapper.selectAccountDeptId(100L)).thenReturn(4L);
        when(governanceMapper.selectLedDepartmentIds(100L)).thenReturn(List.of());
        when(governanceMapper.selectAgentAccessRow(23L))
                .thenReturn(List.of(resource(200L, "PRIVATE", 3L)));
        ResourceDepartmentGrant grant = new ResourceDepartmentGrant();
        grant.setResourceId(23L);
        grant.setDepartmentId(2L);
        grant.setAllowUse(false);
        when(departmentGrantMapper.selectList(any())).thenReturn(List.of(grant));
        when(governanceMapper.selectAgentVisibility(List.of(23L)))
                .thenReturn(List.of(Map.of("id", 23L, "owner_id", 200L,
                        "visibility", "PRIVATE", "resource_dept_id", 3L,
                        "enabled", true, "published", true)));

        assertTrue(service.canView("AGENT", 23L));
        assertFalse(service.canUse("AGENT", 23L));
        var visibility = service.visibilityMap("AGENT", List.of(23L)).get("23");
        assertFalse(visibility.getCanUse());
        assertFalse(visibility.getCanManage());
        verify(departmentGrantMapper, times(3)).selectList(argThat(query ->
                queriesDepartments(query, List.of(4L, 2L, 1L), 3L)));
    }

    @Test
    void siblingDepartmentGrantDoesNotExtendToCurrentMember() {
        when(governanceMapper.selectLedDepartmentIds(100L)).thenReturn(List.of());
        when(governanceMapper.selectAgentAccessRow(24L))
                .thenReturn(List.of(resource(200L, "PRIVATE", 3L)));
        when(departmentGrantMapper.selectList(any())).thenReturn(List.of());

        assertFalse(service.canView("AGENT", 24L));
        assertFalse(service.canUse("AGENT", 24L));
        verify(departmentGrantMapper, times(2)).selectList(argThat(query ->
                queriesDepartments(query, List.of(1L, 2L), 3L)));
    }

    @Test
    void departmentGrantReachesMembersDeeperThanThirtyTwoLevels() {
        List<Map<String, Object>> departments = new ArrayList<>();
        for (long id = 1; id <= 40; id++) {
            departments.add(Map.of("id", id, "parent_id", id - 1));
        }
        when(governanceMapper.selectDeptIdAndParent()).thenReturn(departments);
        when(governanceMapper.selectAccountDeptId(100L)).thenReturn(40L);
        when(governanceMapper.selectLedDepartmentIds(100L)).thenReturn(List.of());
        when(governanceMapper.selectAgentAccessRow(20L))
                .thenReturn(List.of(resource(200L, "PRIVATE", 1L)));
        ResourceDepartmentGrant grant = new ResourceDepartmentGrant();
        grant.setDepartmentId(1L);
        grant.setAllowUse(true);
        when(departmentGrantMapper.selectList(any())).thenReturn(List.of(grant));

        assertTrue(service.canUse("AGENT", 20L));
    }

    @Test
    void departmentGrantAppearsInListScopeAndVisibilityMap() {
        when(governanceMapper.selectLedDepartmentIds(100L)).thenReturn(List.of());
        ResourceDepartmentGrant grant = new ResourceDepartmentGrant();
        grant.setResourceId(21L);
        grant.setDepartmentId(1L);
        grant.setAllowUse(true);
        when(departmentGrantMapper.selectList(any())).thenReturn(List.of(grant));
        when(governanceMapper.selectAgentVisibility(List.of(21L)))
                .thenReturn(List.of(Map.of("id", 21L, "owner_id", 200L,
                        "visibility", "PRIVATE", "resource_dept_id", 3L,
                        "enabled", true, "published", true)));

        QueryWrapper<Object> scope = new QueryWrapper<>();
        service.applyScope(scope, "AGENT", "all");
        assertTrue(scope.getSqlSegment().contains("OR id IN"));
        assertTrue(scope.getParamNameValuePairs().values().stream()
                .anyMatch(value -> "21".equals(String.valueOf(value))));
        assertTrue(service.visibilityMap("AGENT", List.of(21L)).get("21").getCanUse());
    }

    @Test
    void backgroundRunAcceptsDepartmentGrantForTargetAccount() {
        TenantUtils.setCurrentTenant(1L, "test");
        when(governanceMapper.countAccountInTenant(200L, 1L)).thenReturn(1L);
        when(governanceMapper.selectAccountDeptId(200L)).thenReturn(2L);
        when(governanceMapper.selectLedDepartmentIds(200L)).thenReturn(List.of());
        when(governanceMapper.selectAgentAccessRow(22L))
                .thenReturn(List.of(resource(300L, "PRIVATE", 3L)));
        when(permsMapper.selectPerms(200L, 1L, List.of(2L, 1L))).thenReturn(List.of());
        ResourceDepartmentGrant grant = new ResourceDepartmentGrant();
        grant.setDepartmentId(1L);
        grant.setAllowUse(true);
        when(departmentGrantMapper.selectList(any())).thenReturn(List.of(grant));

        assertDoesNotThrow(() -> service.assertUsableForAccount("AGENT", 22L, 200L));
    }

    @Test
    void batchGrantRejectsDepartmentOutsideTenantBeforeWriting() {
        setUser(true);
        when(governanceMapper.selectAgentAccessRow(18L))
                .thenReturn(List.of(resource(100L, "PRIVATE", 2L)));

        assertThrows(IllegalArgumentException.class,
                () -> service.upsertGrants("AGENT", 18L, List.of(), List.of(999L), true));
        verifyNoInteractions(grantMapper, departmentGrantMapper);
    }

    @Test
    void batchGrantSavesSelectedAccountAndDepartment() {
        setUser(true);
        when(governanceMapper.selectAgentAccessRow(19L))
                .thenReturn(List.of(resource(100L, "PRIVATE", 2L)));
        when(governanceMapper.countAccountInTenant(200L, 1L)).thenReturn(1L);

        assertEquals(2, service.upsertGrants("AGENT", 19L, List.of(200L), List.of(2L), true));
        verify(grantMapper).insert(any(ResourceAccessGrant.class));
        verify(departmentGrantMapper).insert(any(ResourceDepartmentGrant.class));
    }

    @Test
    void backgroundCheckUsesTargetAccountsAdminPermission() {
        TenantUtils.setCurrentTenant(1L, "test");
        setUser(true);
        when(governanceMapper.countAccountInTenant(200L, 1L)).thenReturn(1L);
        when(governanceMapper.selectAccountDeptId(200L)).thenReturn(2L);
        when(governanceMapper.selectLedDepartmentIds(200L)).thenReturn(List.of());
        when(governanceMapper.selectAgentAccessRow(12L))
                .thenReturn(List.of(resource(300L, "PRIVATE", 3L)));
        when(permsMapper.selectPerms(200L, 1L, List.of(2L, 1L))).thenReturn(List.of());

        assertThrows(IllegalStateException.class,
                () -> service.assertUsableForAccount("AGENT", 12L, 200L));
    }

    @Test
    void sharedDraftIsVisibleButCannotRun() {
        when(governanceMapper.selectLedDepartmentIds(100L)).thenReturn(List.of());
        Map<String, Object> draft = resource(200L, "TENANT", 2L, false, false);
        when(governanceMapper.selectAgentAccessRow(13L)).thenReturn(List.of(draft));
        when(governanceMapper.selectAgentVisibility(List.of(13L)))
                .thenReturn(List.of(Map.of("id", 13L, "owner_id", 200L,
                        "visibility", "TENANT", "resource_dept_id", 2L,
                        "enabled", false, "published", false)));

        assertTrue(service.canView("AGENT", 13L));
        assertFalse(service.canUse("AGENT", 13L));
        assertEquals(false, service.visibilityMap("AGENT", List.of(13L)).get("13").getCanUse());
    }

    @Test
    void ownerAndAdminCanPreviewDraft() {
        when(governanceMapper.selectLedDepartmentIds(100L)).thenReturn(List.of());
        when(governanceMapper.selectAgentAccessRow(14L))
                .thenReturn(List.of(resource(100L, "PRIVATE", 2L, false, false)));
        assertTrue(service.canUse("AGENT", 14L));

        setUser(true);
        when(governanceMapper.selectAgentAccessRow(15L))
                .thenReturn(List.of(resource(200L, "PRIVATE", 3L, false, false)));
        assertTrue(service.canUse("AGENT", 15L));
    }

    @Test
    void sharingDoesNotRemoveOwnersMaintenanceRights() {
        setUserWithPerms(List.of("agent:definition:edit"));
        when(governanceMapper.selectAgentAccessRow(16L)).thenReturn(List.of(resource(100L, "TENANT", 2L)));
        Map<String, Object> row = new java.util.HashMap<>(resource(100L, "TENANT", 2L));
        row.put("id", 16L);
        when(governanceMapper.selectAgentVisibility(List.of(16L))).thenReturn(List.of(row));
        when(governanceMapper.lockOwnership(com.gmd.faq.server.main.common.consts.TableConst.AGENT, 16L)).thenReturn(List.of(row));
        when(governanceMapper.updateVisibility(com.gmd.faq.server.main.common.consts.TableConst.AGENT, 16L, "PRIVATE", 100L)).thenReturn(1);
        var access = service.visibilityMap("AGENT", List.of(16L)).get("16");
        assertTrue(access.getCanManage());
        assertTrue(access.getCanChangeVisibility());
        assertDoesNotThrow(() -> service.listGrants("AGENT", 16L));
        assertTrue(service.updateVisibility("AGENT", 16L, "PRIVATE"));
    }

    @Test
    void departmentMembersCanUseInternalAssetsButCannotMaintainThem() {
        setUserWithPerms(List.of("agent:definition:edit"));
        Map<String, Object> row = departmentResource(2L, 200L);
        when(governanceMapper.selectAgentAccessRow(40L)).thenReturn(List.of(row));
        when(governanceMapper.selectAgentVisibility(List.of(40L))).thenReturn(List.of(row));
        assertTrue(service.canView("AGENT", 40L));
        assertTrue(service.canUse("AGENT", 40L));
        assertFalse(service.visibilityMap("AGENT", List.of(40L)).get("40").getCanManage());
    }

    @Test
    void departmentAssetsWithNullOwnerDoNotBecomePlatformPublic() {
        when(governanceMapper.selectAgentAccessRow(40L)).thenReturn(List.of(departmentResource(3L, 200L)));
        assertFalse(service.canView("AGENT", 40L));
        assertFalse(service.canUse("AGENT", 40L));
    }

    @Test
    void descendantMemberCanUseParentDepartmentAssets() {
        when(governanceMapper.selectAccountDeptId(100L)).thenReturn(4L);
        when(governanceMapper.selectAgentAccessRow(40L)).thenReturn(List.of(departmentResource(2L, 200L)));
        assertTrue(service.canUse("AGENT", 40L));
    }

    @Test
    void maintainerCanPreviewAndMaintainDepartmentDraftButCannotPrivatizeIt() {
        setUserWithPerms(List.of("agent:definition:edit"));
        Map<String, Object> row = departmentResource(2L, 100L);
        row.put("published", false);
        when(governanceMapper.selectAgentAccessRow(40L)).thenReturn(List.of(row));
        when(governanceMapper.selectAgentVisibility(List.of(40L))).thenReturn(List.of(row));
        when(governanceMapper.lockOwnership(com.gmd.faq.server.main.common.consts.TableConst.AGENT, 40L)).thenReturn(List.of(row));
        assertTrue(service.canUse("AGENT", 40L));
        var access = service.visibilityMap("AGENT", List.of(40L)).get("40");
        assertTrue(access.getCanManage());
        assertFalse(access.getCanTransfer());
        assertThrows(IllegalStateException.class, () -> service.transferOwnership("AGENT", 40L, "PERSONAL", 100L, null, null));
    }

    @Test
    void departmentLeaderMayMaintainAndReassignButCannotTakeDepartmentAsset() {
        setUserWithPerms(List.of("agent:definition:edit"));
        when(governanceMapper.selectLedDepartmentIds(100L)).thenReturn(List.of(2L));
        Map<String, Object> row = departmentResource(4L, 200L);
        when(governanceMapper.selectAgentVisibility(List.of(40L))).thenReturn(List.of(row));
        when(governanceMapper.lockOwnership(com.gmd.faq.server.main.common.consts.TableConst.AGENT, 40L)).thenReturn(List.of(row));
        var access = service.visibilityMap("AGENT", List.of(40L)).get("40");
        assertTrue(access.getCanManage());
        assertTrue(access.getCanTransfer());
        assertThrows(IllegalStateException.class, () -> service.transferOwnership("AGENT", 40L, "PERSONAL", 100L, null, null));
    }

    @Test
    void ownershipTransferKeepsAccessGrantsAndBusinessIdentity() {
        setUserWithPerms(List.of("agent:definition:edit"));
        when(governanceMapper.lockOwnership(com.gmd.faq.server.main.common.consts.TableConst.AGENT, 41L))
                .thenReturn(List.of(resource(100L, "TENANT", 2L)));
        when(governanceMapper.transferOwnership(com.gmd.faq.server.main.common.consts.TableConst.AGENT,
                41L, "DEPARTMENT", null, 2L, null, 100L)).thenReturn(1);
        service.transferOwnership("AGENT", 41L, "DEPARTMENT", null, 2L, null);
        verifyNoInteractions(grantMapper, departmentGrantMapper);
        verify(resourceSpaceService).assertSelectable("department:2");
    }

    @Test
    void transferRejectsMaintainerOutsideDepartment() {
        setUserWithPerms(List.of("agent:definition:edit"));
        when(governanceMapper.lockOwnership(com.gmd.faq.server.main.common.consts.TableConst.AGENT, 41L))
                .thenReturn(List.of(resource(100L, "PRIVATE", 2L)));
        when(governanceMapper.countAccountInTenant(200L, 1L)).thenReturn(1L);
        when(governanceMapper.selectAccountDeptId(200L)).thenReturn(3L);
        assertThrows(IllegalArgumentException.class, () -> service.transferOwnership("AGENT", 41L, "DEPARTMENT", null, 2L, 200L));
        verify(governanceMapper, org.mockito.Mockito.never()).transferOwnership(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void spaceFilterCannotExpandVisibilityAndRejectsInaccessibleDepartment() {
        org.mockito.Mockito.doThrow(new IllegalStateException("无权进入"))
                .when(resourceSpaceService).assertSelectable("department:3");
        assertThrows(IllegalStateException.class, () -> service.applyScope(new QueryWrapper<>(), "AGENT", "all", "department:3"));
        QueryWrapper<Object> wrapper = new QueryWrapper<>();
        service.applyScope(wrapper, "AGENT", "all", "department:2");
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("owning_dept_id ="));
        assertTrue(sql.contains("ownership_type ="));
        assertTrue(sql.contains("maintainer_id"));
    }

    private Map<String, Object> departmentResource(Long departmentId, Long maintainerId) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("id", 40L);
        row.put("ownership_type", "DEPARTMENT");
        row.put("owning_dept_id", departmentId);
        row.put("maintainer_id", maintainerId);
        row.put("visibility", "PRIVATE");
        row.put("resource_dept_id", 3L);
        row.put("enabled", true);
        row.put("published", true);
        return row;
    }

    private Map<String, Object> resource(Long ownerId, String visibility, Long deptId) {
        return resource(ownerId, visibility, deptId, true, true);
    }

    private boolean queriesDepartments(Wrapper<ResourceDepartmentGrant> query,
                                       List<Long> included, Long excluded) {
        if (!(query instanceof AbstractWrapper<?, ?, ?> conditions)) {
            return false;
        }
        query.getSqlSegment();
        return conditions.getParamNameValuePairs().values().containsAll(included)
                && !conditions.getParamNameValuePairs().values().contains(excluded);
    }

    private Map<String, Object> resource(Long ownerId, String visibility, Long deptId,
                                         boolean enabled, boolean published) {
        return Map.of("owner_id", ownerId, "visibility", visibility, "resource_dept_id", deptId,
                "enabled", enabled, "published", published);
    }

    private void setUser(boolean admin) {
        setUserWithPerms(admin ? List.of("system:admin") : List.of());
    }

    private void setUserWithPerms(List<String> perms) {
        UserDetail detail = UserDetail.builder().id(100L).tenantId(1L)
                .authType("ACCOUNT").perms(perms).build();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(SysConst.USER_DETAIL, detail);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
