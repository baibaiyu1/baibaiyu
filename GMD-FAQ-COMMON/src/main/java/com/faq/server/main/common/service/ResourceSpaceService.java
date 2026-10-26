package com.gmd.faq.server.main.common.service;

import com.gmd.faq.server.main.common.entity.OwnedResource;
import com.gmd.faq.server.main.common.enums.ResourceOwnershipType;
import com.gmd.faq.server.main.common.mapper.ResourceSpaceMapper;
import com.gmd.faq.server.main.common.util.ResourceDeptSupport;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.common.vo.ResourceSpaceVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** 所有入口共用的部门创建、维护和空间选择资格。权限点仍由各资源域校验。 */
@Service
@RequiredArgsConstructor
public class ResourceSpaceService {
    private final ResourceSpaceMapper mapper;

    public Set<Long> managedDepartments() {
        Map<Long, Long> parents = ResourceDeptSupport.parentMap(mapper.departments());
        if (UserUtils.isAdmin()) return parents.keySet();
        Set<Long> result = new LinkedHashSet<>();
        for (Long id : mapper.ledDepartments(UserUtils.getResourceAccountId())) {
            if (parents.containsKey(id)) result.addAll(ResourceDeptSupport.collectSubtree(parents, id));
        }
        return result;
    }

    public List<ResourceSpaceVO> spaces() {
        List<Map<String, Object>> rows = mapper.departments();
        Map<Long, Long> parents = ResourceDeptSupport.parentMap(rows);
        Map<Long, String> names = new HashMap<>();
        rows.forEach(row -> names.put(ResourceDeptSupport.toLong(row.get("id")), String.valueOf(row.get("name"))));
        Set<Long> managed = managedDepartments();
        Long own = mapper.accountDepartment(UserUtils.getResourceAccountId());
        List<ResourceSpaceVO> result = new ArrayList<>();
        result.add(new ResourceSpaceVO("personal", "个人空间", null, true));
        for (Map<String, Object> row : rows) {
            Long id = ResourceDeptSupport.toLong(row.get("id"));
            if (!Objects.equals(id, own) && !managed.contains(id)) continue;
            if (Boolean.FALSE.equals(row.get("enabled")) || "0".equals(String.valueOf(row.get("enabled")))) continue;
            List<Long> ancestors = new ArrayList<>(ResourceDeptSupport.collectAncestors(parents, id));
            Collections.reverse(ancestors);
            String path = String.join(" / ", ancestors.stream().map(names::get).toList());
            result.add(new ResourceSpaceVO("department:" + id, path, id, true));
        }
        result.add(new ResourceSpaceVO("platform", "平台公共", null, UserUtils.isAdmin()));
        return result;
    }

    public void assertSelectable(String space) {
        if (space == null || space.isBlank() || "all".equals(space)) return;
        if (spaces().stream().noneMatch(item -> item.key().equals(space))) {
            throw new IllegalStateException("空间不存在或无权进入，请重新选择空间");
        }
    }

    public void initialize(OwnedResource resource, String space) {
        if (UserUtils.isChatKey() || UserUtils.isSk() || UserUtils.getResourceAccountId() == null) {
            throw new IllegalStateException("只有登录账号可以创建资源");
        }
        String selected = space == null || space.isBlank() ? "personal" : space;
        ResourceSpaceVO target = spaces().stream().filter(item -> item.key().equals(selected)).findFirst()
                .orElseThrow(() -> new IllegalStateException("请选择有权创建资源的空间"));
        if (!target.canCreate()) throw new IllegalStateException("无权在此空间创建资源");
        resource.setOwnershipType(target.departmentId() != null ? ResourceOwnershipType.DEPARTMENT.name()
                : "platform".equals(selected) ? ResourceOwnershipType.PLATFORM.name() : ResourceOwnershipType.PERSONAL.name());
        resource.setOwningDeptId(target.departmentId());
        resource.setOwnerId("personal".equals(selected) ? UserUtils.getResourceAccountId() : null);
        Long maintainer = "personal".equals(selected) ? null : UserUtils.getResourceAccountId();
        if (target.departmentId() != null) {
            Map<Long, Long> parents = ResourceDeptSupport.parentMap(mapper.departments());
            Long ownDepartment = mapper.accountDepartment(UserUtils.getResourceAccountId());
            if (!ResourceDeptSupport.collectSubtree(parents, target.departmentId()).contains(ownDepartment)) maintainer = null;
        }
        resource.setMaintainerId(maintainer);
        resource.setVisibility("platform".equals(selected) ? "TENANT" : "PRIVATE");
    }
}
