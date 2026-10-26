package com.gmd.faq.server.main.scheduler.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gmd.faq.server.main.common.entity.JobInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 **/
@Mapper
public interface JobInfoMapper extends BaseMapper<JobInfo> {
}
