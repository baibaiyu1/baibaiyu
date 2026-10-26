package com.gmd.faq.server.main.scheduler.core.aspect;

import com.gmd.faq.server.main.common.entity.JobInfo;
import com.gmd.faq.server.main.scheduler.service.QuartzInfoService;

/**
 * 描述：切面抽象类
 *
 **/
public abstract class QuartzAspect {
    private final QuartzInfoService quartzStatusService;
    public QuartzAspect(QuartzInfoService quartzStatusService) {
        this.quartzStatusService = quartzStatusService;
    }

    public void saveStatus(JobInfo jobStatus) {
        quartzStatusService.updateStatus(jobStatus);
    }
}
