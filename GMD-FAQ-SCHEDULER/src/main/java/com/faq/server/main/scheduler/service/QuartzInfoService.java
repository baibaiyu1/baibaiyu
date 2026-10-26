package com.gmd.faq.server.main.scheduler.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.gmd.faq.server.main.common.entity.JobInfo;
import com.gmd.faq.server.main.common.enums.TenantRole;

/**
 * 描述：定时任务管理服务接口
 *
 **/
public interface QuartzInfoService extends IService<JobInfo> {

    /**
     * 更新任务状态
     *
     * @param jobStatus 包含ID和status的任务对象
     */
    void updateStatus(JobInfo jobStatus);

    /**
     * 新增定时任务
     *
     * @param jobInfo 任务信息
     */
    void addJob(JobInfo jobInfo) throws ClassNotFoundException;

    /**
     * 更新定时任务
     *
     * @param jobInfo 任务信息
     */
    void updateJob(JobInfo jobInfo) throws ClassNotFoundException;

    /**
     * 更新定时任务Cron表达式
     *
     * @param id   任务ID
     * @param cron 新的Cron表达式
     */
    void updateJobCron(String id, String cron) throws ClassNotFoundException;

    /**
     * 删除定时任务
     *
     * @param id 任务ID
     * @return 是否删除成功
     */
    boolean deleteJob(String id) throws ClassNotFoundException;

    /**
     * 启动定时任务
     *
     * @param id 任务ID
     */
    void startJob(String id) throws ClassNotFoundException;

    /**
     * 停止定时任务
     *
     * @param id 任务ID
     */
    void stopJob(String id) throws ClassNotFoundException;

    /**
     * 分页查询自动化任务
     *
     * @param page     页码
     * @param size     每页数量
     * @param type     任务类型（可选）
     * @param bizId    关联业务ID（可选，智能体编排页按智能体过滤自己的定时任务）
     * @param keyword  关键字搜索（可选，匹配 dataMap 中的任务名称 / 输入内容）
     * @param enabled  启用状态（可选，null = 不限）
     * @param tenantId 租户ID
     * @param userId   当前用户ID
     * @param viewAll  是否可查看全部任务（拥有 system:admin 权限点为 true，否则仅看自己创建的）
     * @return 分页结果
     */
    IPage<JobInfo> pageJobs(int page, int size, String type, String bizId, String keyword, Boolean enabled,
                            Long tenantId, Long userId, boolean viewAll);
}
