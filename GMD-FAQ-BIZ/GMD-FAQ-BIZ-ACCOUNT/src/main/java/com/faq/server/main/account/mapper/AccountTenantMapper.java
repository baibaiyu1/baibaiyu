package com.gmd.faq.server.main.account.mapper;

import com.gmd.faq.server.main.common.entity.AccountTenant;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 账号-租户关联 Mapper
 *
 */
@Mapper
public interface AccountTenantMapper extends BaseMapper<AccountTenant> {
}
