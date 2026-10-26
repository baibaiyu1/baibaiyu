package com.gmd.faq.server.main.dashboard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gmd.faq.server.main.common.entity.DashboardUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 描述：Dashboard 个人覆盖 Mapper
 *
 **/
@Mapper
public interface DashboardUserMapper extends BaseMapper<DashboardUser> {
}
