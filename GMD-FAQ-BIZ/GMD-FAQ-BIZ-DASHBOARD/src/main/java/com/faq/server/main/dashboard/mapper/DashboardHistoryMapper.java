package com.gmd.faq.server.main.dashboard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gmd.faq.server.main.common.entity.DashboardHistory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 描述：Dashboard 个人历史版本 Mapper
 *
 **/
@Mapper
public interface DashboardHistoryMapper extends BaseMapper<DashboardHistory> {
}
