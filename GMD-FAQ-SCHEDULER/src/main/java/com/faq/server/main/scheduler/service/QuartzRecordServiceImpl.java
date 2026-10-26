package com.gmd.faq.server.main.scheduler.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gmd.faq.server.main.common.entity.JobRecord;
import com.gmd.faq.server.main.scheduler.mapper.JobRecordMapper;
import org.springframework.stereotype.Service;

/**
 **/
@Service
public class QuartzRecordServiceImpl extends ServiceImpl<JobRecordMapper, JobRecord> implements QuartzRecordService {
}
