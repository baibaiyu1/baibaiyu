package com.gmd.faq.server.main.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gmd.faq.server.main.common.dto.MenuSaveDTO;
import com.gmd.faq.server.main.common.entity.Menu;
import com.gmd.faq.server.main.common.vo.MenuVO;

import java.util.List;

/**
 * 菜单 Service
 *
 */
public interface MenuService extends IService<Menu> {

    /**
     * 查询菜单树（可带关键字过滤）
     *
     * @param keyword 菜单名称关键字，可空
     * @param menuType 菜单类型过滤（DIRECTORY/MENU/BUTTON），可空
     * @return 菜单树
     */
    List<MenuVO> listTree(String keyword, String menuType);

    /**
     * 查询菜单平铺列表
     *
     * @return 菜单列表
     */
    List<Menu> listFlat();

    /**
     * 菜单详情（含按钮权限列表）
     *
     * @param id 菜单ID
     * @return 菜单详情，不存在返回 null
     */
    MenuVO detail(Long id);

    /**
     * 新增菜单
     *
     * @param dto 菜单保存请求
     * @return 保存后的菜单
     */
    Menu create(MenuSaveDTO dto);

    /**
     * 修改菜单
     *
     * @param dto 菜单保存请求
     * @return 是否成功
     */
    boolean update(MenuSaveDTO dto);

    /**
     * 删除菜单（存在子菜单时拒绝）
     *
     * @param id 菜单ID
     * @return 是否成功
     */
    boolean remove(Long id);
}
