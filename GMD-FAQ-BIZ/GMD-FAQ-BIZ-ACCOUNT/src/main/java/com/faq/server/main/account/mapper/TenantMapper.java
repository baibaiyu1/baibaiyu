package com.gmd.faq.server.main.account.mapper;

import com.gmd.faq.server.main.common.entity.Tenant;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 租户 Mapper
 *
 */
@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {
}
