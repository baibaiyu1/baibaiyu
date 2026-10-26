package com.gmd.faq.server.main.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gmd.faq.server.main.common.entity.RoleAccount;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色-账号关联 Mapper
 *
 */
@Mapper
public interface RoleAccountMapper extends BaseMapper<RoleAccount> {
}
