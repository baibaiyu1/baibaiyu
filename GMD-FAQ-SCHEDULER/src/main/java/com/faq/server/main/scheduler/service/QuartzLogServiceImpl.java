package com.gmd.faq.server.main.scheduler.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gmd.faq.server.main.common.entity.JobLog;
import com.gmd.faq.server.main.scheduler.mapper.JobLogMapper;
import org.springframework.stereotype.Service;

/**
 **/
@Service
public class QuartzLogServiceImpl extends ServiceImpl<JobLogMapper, JobLog> implements QuartzLogService {
}
