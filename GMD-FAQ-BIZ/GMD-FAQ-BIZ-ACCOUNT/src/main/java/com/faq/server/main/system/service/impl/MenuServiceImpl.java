package com.gmd.faq.server.main.system.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gmd.faq.server.main.common.dto.MenuSaveDTO;
import com.gmd.faq.server.main.common.entity.Menu;
import com.gmd.faq.server.main.common.enums.MenuType;
import com.gmd.faq.server.main.common.exception.BusinessException;
import com.gmd.faq.server.main.common.util.BeanUtils;
import com.gmd.faq.server.main.common.vo.MenuVO;
import com.gmd.faq.server.main.system.mapper.MenuMapper;
import com.gmd.faq.server.main.system.service.MenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 菜单服务实现
 * <p>
 * 菜单为全局数据（不按租户隔离），因此这里全部走 BaseMapper，不依赖租户上下文。
 *
 */
@Service
@RequiredArgsConstructor
public class MenuServiceImpl extends ServiceImpl<MenuMapper, Menu> implements MenuService {

    @Override
    public List<Menu> listFlat() {
        return lambdaQuery()
                .orderByAsc(Menu::getSortOrder)
                .orderByAsc(Menu::getId)
                .list();
    }

    @Override
    public List<MenuVO> listTree(String keyword, String menuType) {
        List<Menu> flatList = listFlat();

        if (menuType != null && !menuType.isBlank()) {
            String type = MenuType.of(menuType).name();
            flatList = flatList.stream()
                    .filter(item -> type.equalsIgnoreCase(item.getMenuType()))
                    .collect(Collectors.toList());
        }

        List<MenuVO> nodes = flatList.stream().map(this::toVO).collect(Collectors.toList());

        if (keyword != null && !keyword.isBlank()) {
            // 关键字命中的节点，连同其全部祖先一起保留，保证树结构完整
            return filterTreeByKeyword(nodes, keyword.trim());
        }
        return buildTree(nodes);
    }

    @Override
    public MenuVO detail(Long id) {
        Menu entity = getById(id);
        if (entity == null) {
            return null;
        }
        MenuVO vo = toVO(entity);
        // 按钮权限挂在该菜单下
        vo.setButtons(lambdaQuery()
                .eq(Menu::getParentId, id)
                .eq(Menu::getMenuType, MenuType.BUTTON.name())
                .orderByAsc(Menu::getSortOrder)
                .orderByAsc(Menu::getId)
                .list()
                .stream().map(this::toVO).collect(Collectors.toList()));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Menu create(MenuSaveDTO dto) {
        Menu entity = new Menu();
        applyDto(entity, dto);
        if (entity.getName() == null || entity.getName().isBlank()) {
            throw new BusinessException("菜单名称不能为空");
        }
        if (entity.getParentId() == null) {
            entity.setParentId(0L);
        }
        checkParent(entity.getParentId());
        checkNameUnique(entity.getParentId(), entity.getName(), null);
        if (entity.getSortOrder() == null) {
            entity.setSortOrder(0);
        }
        save(entity);
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean update(MenuSaveDTO dto) {
        if (dto.getId() == null) {
            throw new BusinessException("菜单ID不能为空");
        }
        Menu exist = getById(dto.getId());
        if (exist == null) {
            throw new BusinessException("菜单不存在");
        }
        Menu update = new Menu();
        update.setId(dto.getId());
        applyDto(update, dto);
        if (update.getParentId() == null) {
            update.setParentId(exist.getParentId());
        }
        if (Objects.equals(update.getParentId(), update.getId())) {
            throw new BusinessException("上级菜单不能是自己");
        }
        // 不允许把菜单挂到自己的子孙节点下，否则会形成环
        if (listSelfAndDescendantIds(update.getId()).contains(update.getParentId())
                && !Objects.equals(update.getParentId(), exist.getParentId())) {
            throw new BusinessException("上级菜单不能是自己的下级菜单");
        }
        checkParent(update.getParentId());
        if (update.getName() != null && !update.getName().isBlank()) {
            checkNameUnique(update.getParentId(), update.getName(), update.getId());
        }
        return updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean remove(Long id) {
        Menu exist = getById(id);
        if (exist == null) {
            throw new BusinessException("菜单不存在");
        }
        Long childCount = lambdaQuery().eq(Menu::getParentId, id).count();
        if (childCount != null && childCount > 0) {
            throw new BusinessException("存在子菜单或按钮权限，不允许删除");
        }
        return removeById(id);
    }

    /**
     * DTO → 实体（只覆盖非空字段，便于更新时做局部更新）
     */
    private void applyDto(Menu entity, MenuSaveDTO dto) {
        entity.setParentId(dto.getParentId());
        entity.setName(dto.getName() == null ? null : dto.getName().trim());
        if (MenuType.isValid(dto.getMenuType())) {
            entity.setMenuType(MenuType.of(dto.getMenuType()).name());
        }
        entity.setPath(dto.getPath());
        entity.setComponent(dto.getComponent());
        entity.setPerms(dto.getPerms());
        entity.setIcon(dto.getIcon());
        entity.setSortOrder(dto.getSortOrder());
        entity.setVisible(dto.getVisible());
        entity.setCached(dto.getCached());
        entity.setExternal(dto.getExternal());
        entity.setStatus(dto.getStatus());
        entity.setRemark(dto.getRemark() == null ? null : dto.getRemark().trim());
    }

    private void checkParent(Long parentId) {
        if (parentId == null || parentId == 0L) {
            return;
        }
        if (getById(parentId) == null) {
            throw new BusinessException("上级菜单不存在");
        }
    }

    private void checkNameUnique(Long parentId, String name, Long excludeId) {
        Long count = lambdaQuery()
                .eq(Menu::getParentId, parentId == null ? 0L : parentId)
                .eq(Menu::getName, name)
                .ne(excludeId != null, Menu::getId, excludeId)
                .count();
        if (count != null && count > 0) {
            throw new BusinessException("同级下已存在同名菜单：" + name);
        }
    }

    /**
     * 取指定菜单及其全部子孙菜单ID
     */
    private List<Long> listSelfAndDescendantIds(Long menuId) {
        List<Menu> all = lambdaQuery().list();
        Map<Long, List<Long>> childrenMap = all.stream()
                .filter(item -> item.getParentId() != null)
                .collect(Collectors.groupingBy(Menu::getParentId,
                        Collectors.mapping(Menu::getId, Collectors.toList())));
        List<Long> result = new ArrayList<>();
        collect(menuId, childrenMap, result);
        return result;
    }

    private void collect(Long current, Map<Long, List<Long>> childrenMap, List<Long> acc) {
        if (current == null || acc.contains(current)) {
            return;
        }
        acc.add(current);
        for (Long child : childrenMap.getOrDefault(current, new ArrayList<>())) {
            collect(child, childrenMap, acc);
        }
    }

    /**
     * 保留命中关键字的节点及其全部祖先
     */
    private List<MenuVO> filterTreeByKeyword(List<MenuVO> nodes, String keyword) {
        Map<Long, MenuVO> nodeMap = nodes.stream()
                .collect(Collectors.toMap(MenuVO::getId, item -> item, (a, b) -> a));
        String lower = keyword.toLowerCase();
        List<MenuVO> roots = new ArrayList<>();
        for (MenuVO node : nodes) {
            boolean hit = contains(node, lower);
            if (!hit) {
                continue;
            }
            // 找到最上层祖先作为根
            MenuVO root = node;
            while (root.getParentId() != null && nodeMap.containsKey(root.getParentId())) {
                root = nodeMap.get(root.getParentId());
            }
            if (!roots.contains(root)) {
                roots.add(root);
            }
        }
        return roots;
    }

    private boolean contains(MenuVO node, String lowerKeyword) {
        return containsText(node.getName(), lowerKeyword)
                || containsText(node.getPerms(), lowerKeyword)
                || containsText(node.getPath(), lowerKeyword);
    }

    private boolean containsText(String value, String lowerKeyword) {
        return value != null && value.toLowerCase().contains(lowerKeyword);
    }

    private MenuVO toVO(Menu entity) {
        return BeanUtils.copy(entity, MenuVO.class);
    }

    /**
     * 扁平节点列表组装成树（parent_id 不在集合内的节点视为根节点）
     */
    private List<MenuVO> buildTree(List<MenuVO> nodes) {
        Map<Long, MenuVO> nodeMap = nodes.stream()
                .collect(Collectors.toMap(MenuVO::getId, item -> item, (a, b) -> a));
        List<MenuVO> roots = new ArrayList<>();
        for (MenuVO node : nodes) {
            Long parentId = node.getParentId();
            MenuVO parent = parentId == null ? null : nodeMap.get(parentId);
            if (parent == null) {
                roots.add(node);
            } else {
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(node);
            }
        }
        sortRecursively(roots);
        return roots;
    }

    private void sortRecursively(List<MenuVO> nodes) {
        nodes.sort(Comparator
                .comparing((MenuVO item) -> item.getSortOrder() == null ? 0 : item.getSortOrder())
                .thenComparing(MenuVO::getId));
        for (MenuVO node : nodes) {
            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                sortRecursively(node.getChildren());
            }
        }
    }
}
