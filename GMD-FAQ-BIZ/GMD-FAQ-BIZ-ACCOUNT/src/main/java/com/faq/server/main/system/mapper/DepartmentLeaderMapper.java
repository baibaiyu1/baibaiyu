package com.gmd.faq.server.main.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gmd.faq.server.main.common.entity.DepartmentLeader;
import org.apache.ibatis.annotations.Mapper;

/**
 * 部门（组织）负责人 Mapper（多选，见 V18 迁移）
 *
 */
@Mapper
public interface DepartmentLeaderMapper extends BaseMapper<DepartmentLeader> {
}
