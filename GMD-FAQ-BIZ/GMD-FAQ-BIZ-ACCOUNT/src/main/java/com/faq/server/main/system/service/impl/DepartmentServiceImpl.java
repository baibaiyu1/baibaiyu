package com.gmd.faq.server.main.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gmd.faq.server.main.common.dto.DepartmentDTO;
import com.gmd.faq.server.main.common.entity.Account;
import com.gmd.faq.server.main.common.entity.Department;
import com.gmd.faq.server.main.common.entity.DepartmentLeader;
import com.gmd.faq.server.main.common.exception.BusinessException;
import com.gmd.faq.server.main.common.util.BeanUtils;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.vo.DepartmentVO;
import com.gmd.faq.server.main.account.mapper.AccountMapper;
import com.gmd.faq.server.main.system.mapper.DepartmentLeaderMapper;
import com.gmd.faq.server.main.system.mapper.DepartmentMapper;
import com.gmd.faq.server.main.system.service.DepartmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 部门服务实现
 * <p>
 * 「部门即组织」：每个部门节点同时是一个组织，账号的 {@code dept_id} 决定其组织归属，
 * 因此本服务额外提供「组织成员数」「负责人」等组织语义的聚合信息。
 * 组织负责人支持多选（V18 起落在 {@code gmd_faq_department_leader} 关联表）。
 *
 */
@Service
@RequiredArgsConstructor
public class DepartmentServiceImpl extends ServiceImpl<DepartmentMapper, Department> implements DepartmentService {

    private final AccountMapper accountMapper;
    private final DepartmentLeaderMapper departmentLeaderMapper;
    private final com.gmd.faq.server.main.common.mapper.ResourceSpaceMapper resourceSpaceMapper;

    @Override
    public List<Department> listFlat(DepartmentDTO query) {
        String name = query == null ? null : query.getName();
        Long parentId = query == null ? null : query.getParentId();
        Boolean enabled = query == null ? null : query.getEnabled();
        return lambdaQuery()
                .like(name != null && !name.isBlank(), Department::getName, name)
                .eq(parentId != null, Department::getParentId, parentId)
                .eq(enabled != null, Department::getEnabled, enabled)
                .orderByAsc(Department::getSortOrder)
                .orderByAsc(Department::getId)
                .list();
    }

    @Override
    public List<DepartmentVO> listTree(DepartmentDTO query) {
        List<Department> flatList = listFlat(query);

        // 直属账号数 + 负责人账号名：一次查询后在内存里归并，避免 N+1
        List<Account> accounts = accountMapper.selectList(Wrappers.<Account>lambdaQuery());
        Map<Long, Long> directCountMap = accounts.stream()
                .filter(item -> item.getDeptId() != null)
                .collect(Collectors.groupingBy(Account::getDeptId, Collectors.counting()));
        Map<Long, String> accountNameMap = accounts.stream()
                .collect(Collectors.toMap(Account::getId,
                        item -> item.getNickname() == null || item.getNickname().isBlank()
                                ? item.getUsername() : item.getNickname(),
                        (a, b) -> a));

        // 组织负责人（多选）：一次查出全部关联，按部门归并
        Map<Long, List<Long>> leaderMap = listLeaderMap(flatList.stream()
                .map(Department::getId).collect(Collectors.toList()));

        List<DepartmentVO> nodes = flatList.stream().map(item -> {
            DepartmentVO vo = BeanUtils.copy(item, DepartmentVO.class);
            vo.setUserCount(directCountMap.getOrDefault(item.getId(), 0L));
            vo.setMemberCount(0L);
            fillLeaders(vo, leaderMap.getOrDefault(item.getId(), Collections.emptyList()), accountNameMap);
            vo.setChildren(new ArrayList<>());
            return vo;
        }).collect(Collectors.toList());

        List<DepartmentVO> roots = buildTree(nodes);
        // 组织成员数 = 该部门及其全部下级部门的账号数之和（自底向上累加）
        for (DepartmentVO root : roots) {
            accumulateMemberCount(root);
        }
        return roots;
    }

    @Override
    public DepartmentVO detail(Long id) {
        Department entity = getById(id);
        if (entity == null) {
            return null;
        }
        DepartmentVO vo = BeanUtils.copy(entity, DepartmentVO.class);
        Long count = accountMapper.selectCount(
                Wrappers.<Account>lambdaQuery().eq(Account::getDeptId, id));
        vo.setUserCount(count == null ? 0L : count);
        List<Long> subtree = listSelfAndDescendantIds(id);
        Long memberCount = subtree.isEmpty() ? 0L : accountMapper.selectCount(
                Wrappers.<Account>lambdaQuery().in(Account::getDeptId, subtree));
        vo.setMemberCount(memberCount == null ? 0L : memberCount);

        List<Long> leaderIds = listLeaderAccountIds(id);
        Map<Long, String> accountNameMap = leaderIds.isEmpty()
                ? Collections.emptyMap()
                : accountMapper.selectBatchIds(leaderIds).stream()
                        .collect(Collectors.toMap(Account::getId,
                                item -> item.getNickname() == null || item.getNickname().isBlank()
                                        ? item.getUsername() : item.getNickname(),
                                (a, b) -> a));
        fillLeaders(vo, leaderIds, accountNameMap);
        return vo;
    }

    /**
     * 把负责人账号ID与姓名写进 VO（ID 与姓名顺序一一对应）
     */
    private void fillLeaders(DepartmentVO vo, List<Long> leaderIds, Map<Long, String> accountNameMap) {
        vo.setLeaderAccountIds(new ArrayList<>(leaderIds));
        vo.setLeaderNames(leaderIds.stream()
                .map(accountNameMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
    }

    /**
     * 查询一批部门的负责人账号ID（按部门归并）
     * <p>
     * 一次查出全部关联后在内存里分组，避免逐个部门查库（N+1）。
     * 返回的每个部门内按关联表主键升序，保证「先设置的排在前面」。
     *
     * @param deptIds 部门ID集合，可为空
     * @return departmentId -> 负责人账号ID列表
     */
    @Override
    public Map<Long, List<Long>> listLeaderMap(Collection<Long> deptIds) {
        if (deptIds == null || deptIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<DepartmentLeader> relations = departmentLeaderMapper.selectList(
                Wrappers.<DepartmentLeader>lambdaQuery()
                        .in(DepartmentLeader::getDepartmentId, deptIds)
                        .orderByAsc(DepartmentLeader::getId));
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        for (DepartmentLeader relation : relations) {
            result.computeIfAbsent(relation.getDepartmentId(), key -> new ArrayList<>())
                    .add(relation.getAccountId());
        }
        return result;
    }

    @Override
    public List<Long> listLeaderAccountIds(Long deptId) {
        if (deptId == null) {
            return Collections.emptyList();
        }
        return listLeaderMap(List.of(deptId)).getOrDefault(deptId, Collections.emptyList());
    }

    @Override
    public List<Long> listLeaderDepartmentIds(Long accountId) {
        if (accountId == null) {
            return Collections.emptyList();
        }
        return departmentLeaderMapper.selectList(Wrappers.<DepartmentLeader>lambdaQuery()
                        .eq(DepartmentLeader::getAccountId, accountId))
                .stream()
                .map(DepartmentLeader::getDepartmentId)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeLeaderAccount(Long accountId) {
        if (accountId == null) {
            return;
        }
        departmentLeaderMapper.delete(Wrappers.<DepartmentLeader>lambdaQuery()
                .eq(DepartmentLeader::getAccountId, accountId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateLeaderAccounts(Long deptId, List<Long> accountIds) {
        if (deptId == null) {
            return;
        }
        Department department = getById(deptId);
        if (department == null) {
            throw new BusinessException("部门（组织）不存在");
        }
        List<Long> distinct = accountIds == null
                ? Collections.emptyList()
                : accountIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());

        // 校验负责人账号存在（一次性查完，避免逐个 selectById）
        if (!distinct.isEmpty()) {
            Map<Long, Account> existing = accountMapper.selectBatchIds(distinct).stream()
                    .collect(Collectors.toMap(Account::getId, item -> item, (a, b) -> a));
            List<Long> missing = distinct.stream().filter(id -> !existing.containsKey(id)).collect(Collectors.toList());
            if (!missing.isEmpty()) {
                throw new BusinessException("负责人账号不存在：" + missing);
            }
        }

        // 全量覆盖：先清后插（关系表数据量极小，比 diff 更直观且不会漏删）
        departmentLeaderMapper.delete(Wrappers.<DepartmentLeader>lambdaQuery()
                .eq(DepartmentLeader::getDepartmentId, deptId));
        Long tenantId = department.getTenantId() != null
                ? department.getTenantId()
                : TenantUtils.getCurrentTenantId();
        for (Long accountId : distinct) {
            DepartmentLeader relation = new DepartmentLeader();
            relation.setId(IdWorker.getId());
            relation.setDepartmentId(deptId);
            relation.setAccountId(accountId);
            relation.setTenantId(tenantId);
            departmentLeaderMapper.insert(relation);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Department create(Department entity) {
        if (entity.getName() == null || entity.getName().isBlank()) {
            throw new BusinessException("部门（组织）名称不能为空");
        }
        if (entity.getParentId() == null) {
            entity.setParentId(0L);
        }
        checkParent(entity.getParentId(), null);
        checkNameUnique(entity.getParentId(), entity.getName(), null);
        checkCodeUnique(entity.getCode(), null);
        if (entity.getSortOrder() == null) {
            entity.setSortOrder(0);
        }
        save(entity);
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean update(Department entity) {
        Department exist = getById(entity.getId());
        if (exist == null) {
            throw new BusinessException("部门（组织）不存在");
        }
        if (entity.getParentId() == null) {
            entity.setParentId(exist.getParentId());
        }
        if (Objects.equals(entity.getParentId(), entity.getId())) {
            throw new BusinessException("上级部门不能是自己");
        }
        // 不允许把部门挂到自己的子孙节点下，否则会形成环
        if (listSelfAndDescendantIds(entity.getId()).contains(entity.getParentId())
                && !Objects.equals(entity.getParentId(), exist.getParentId())) {
            throw new BusinessException("上级部门不能是自己的下级部门");
        }
        checkParent(entity.getParentId(), entity.getId());
        if (entity.getName() != null && !entity.getName().isBlank()) {
            checkNameUnique(entity.getParentId(), entity.getName(), entity.getId());
        }
        checkCodeUnique(entity.getCode(), entity.getId());
        return updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean remove(Long id) {
        Department exist = getById(id);
        if (exist == null) {
            throw new BusinessException("部门（组织）不存在");
        }
        Long childCount = lambdaQuery().eq(Department::getParentId, id).count();
        if (childCount != null && childCount > 0) {
            throw new BusinessException("存在下级部门（组织），不允许删除");
        }
        Long userCount = accountMapper.selectCount(
                Wrappers.<Account>lambdaQuery().eq(Account::getDeptId, id));
        if (userCount != null && userCount > 0) {
            throw new BusinessException("部门（组织）下存在用户，不允许删除");
        }
        if (resourceSpaceMapper.countDepartmentAssets(id) > 0) {
            throw new BusinessException("部门下存在资产，请先移交资源后再删除部门");
        }
        // 连带清理负责人关联，避免留下指向已删组织的悬挂记录
        departmentLeaderMapper.delete(Wrappers.<DepartmentLeader>lambdaQuery()
                .eq(DepartmentLeader::getDepartmentId, id));
        return removeById(id);
    }

    @Override
    public List<Long> listSelfAndDescendantIds(Long deptId) {
        if (deptId == null) {
            return Collections.emptyList();
        }
        List<Department> all = lambdaQuery().list();
        Map<Long, List<Long>> childrenMap = all.stream()
                .filter(item -> item.getParentId() != null)
                .collect(Collectors.groupingBy(Department::getParentId,
                        Collectors.mapping(Department::getId, Collectors.toList())));

        List<Long> result = new ArrayList<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(deptId);
        Set<Long> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            result.add(current);
            queue.addAll(childrenMap.getOrDefault(current, Collections.emptyList()));
        }
        return result;
    }

    @Override
    public List<Long> listSelfAndAncestorIds(Long deptId) {
        if (deptId == null) {
            return Collections.emptyList();
        }
        List<Department> all = lambdaQuery().list();
        Map<Long, Long> parentOf = all.stream()
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(Department::getId, item -> item.getParentId() == null ? 0L : item.getParentId(),
                        (a, b) -> a));

        List<Long> result = new ArrayList<>();
        Long current = deptId;
        // 最多上溯 MAX_DEPT_DEPTH 层，防止历史脏数据成环时死循环
        int depth = 0;
        while (current != null && current != 0L && depth++ < MAX_DEPT_DEPTH && !result.contains(current)) {
            result.add(current);
            current = parentOf.get(current);
        }
        return result;
    }

    /** 部门树上溯的最大层数（防御历史脏数据成环） */
    private static final int MAX_DEPT_DEPTH = 32;

    /**
     * 组织成员数自底向上累加：成员数 = 直属账号数 + 全部子部门成员数
     */
    private long accumulateMemberCount(DepartmentVO node) {
        long total = node.getUserCount() == null ? 0L : node.getUserCount();
        if (node.getChildren() != null) {
            for (DepartmentVO child : node.getChildren()) {
                total += accumulateMemberCount(child);
            }
        }
        node.setMemberCount(total);
        return total;
    }

    /**
     * 校验上级部门存在
     */
    private void checkParent(Long parentId, Long selfId) {
        if (parentId == null || parentId == 0L) {
            return;
        }
        if (Objects.equals(parentId, selfId)) {
            throw new BusinessException("上级部门不能是自己");
        }
        if (getById(parentId) == null) {
            throw new BusinessException("上级部门不存在");
        }
    }

    /**
     * 同级部门名称唯一
     */
    private void checkNameUnique(Long parentId, String name, Long excludeId) {
        Long count = lambdaQuery()
                .eq(Department::getParentId, parentId == null ? 0L : parentId)
                .eq(Department::getName, name)
                .ne(excludeId != null, Department::getId, excludeId)
                .count();
        if (count != null && count > 0) {
            throw new BusinessException("同级下已存在同名部门：" + name);
        }
    }

    /**
     * 组织编码全局唯一（留空不校验）
     */
    private void checkCodeUnique(String code, Long excludeId) {
        if (code == null || code.isBlank()) {
            return;
        }
        Long count = lambdaQuery()
                .eq(Department::getCode, code.trim())
                .ne(excludeId != null, Department::getId, excludeId)
                .count();
        if (count != null && count > 0) {
            throw new BusinessException("组织编码已存在：" + code);
        }
    }

    /**
     * 扁平节点列表组装成树（parent_id 不在集合内的节点视为根节点）
     */
    private List<DepartmentVO> buildTree(List<DepartmentVO> nodes) {
        Map<Long, DepartmentVO> nodeMap = nodes.stream()
                .collect(Collectors.toMap(DepartmentVO::getId, item -> item, (a, b) -> a));
        List<DepartmentVO> roots = new ArrayList<>();
        for (DepartmentVO node : nodes) {
            Long parentId = node.getParentId();
            DepartmentVO parent = parentId == null ? null : nodeMap.get(parentId);
            if (parent == null) {
                roots.add(node);
            } else {
                parent.getChildren().add(node);
            }
        }
        sortRecursively(roots);
        return roots;
    }

    private void sortRecursively(List<DepartmentVO> nodes) {
        nodes.sort(Comparator
                .comparing((DepartmentVO item) -> item.getSortOrder() == null ? 0 : item.getSortOrder())
                .thenComparing(DepartmentVO::getId));
        for (DepartmentVO node : nodes) {
            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                sortRecursively(node.getChildren());
            }
        }
    }
}
