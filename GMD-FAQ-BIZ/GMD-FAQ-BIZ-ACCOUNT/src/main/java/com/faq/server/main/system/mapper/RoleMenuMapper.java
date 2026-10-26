package com.gmd.faq.server.main.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gmd.faq.server.main.common.entity.RoleMenu;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色-菜单权限关联 Mapper
 *
 */
@Mapper
public interface RoleMenuMapper extends BaseMapper<RoleMenu> {
}
