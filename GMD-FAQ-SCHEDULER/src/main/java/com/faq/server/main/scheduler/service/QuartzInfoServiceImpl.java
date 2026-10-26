package com.gmd.faq.server.main.scheduler.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gmd.faq.server.main.agent.service.AgentDefinitionService;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.JobInfo;
import com.gmd.faq.server.main.common.enums.TenantRole;
import com.gmd.faq.server.main.scheduler.core.client.QuartzClient;
import com.gmd.faq.server.main.scheduler.core.config.QuartzConfig;
import com.gmd.faq.server.main.scheduler.init.JobInit;
import com.gmd.faq.server.main.scheduler.mapper.JobInfoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 描述：定时任务管理服务实现
 *
 **/
@Slf4j
@Service
@RequiredArgsConstructor
public class QuartzInfoServiceImpl extends ServiceImpl<JobInfoMapper, JobInfo> implements QuartzInfoService {

    private final QuartzClient quartzClient;
    private final AgentDefinitionService agentDefinitionService;

    @Override
    public void updateStatus(JobInfo jobStatus) {
        lambdaUpdate()
                .eq(JobInfo::getId, jobStatus.getId())
                .set(JobInfo::getEnabled, jobStatus.getEnabled())
                .update();
    }

    @Override
    public void addJob(JobInfo jobInfo) {
        checkTarget(jobInfo);
        try {
            save(jobInfo);
            if (jobInfo.getEnabled()) {
                quartzClient.create(JobInit.buildConfig(jobInfo));
            }
        } catch (Exception e) {
            removeById(jobInfo.getId());
            throw new RuntimeException(e);
        }
    }

    @Override
    public void updateJob(JobInfo jobInfo) throws ClassNotFoundException {
        JobInfo job = getById(jobInfo.getId());
        if (job == null) {
            throw new RuntimeException("任务实例不存在");
        }
        checkTarget(job);
        // 用前端传入的字段更新 job，保持 createdBy 等非前端字段不变
        job.setType(jobInfo.getType());
        job.setBizId(jobInfo.getBizId());
        job.setCron(jobInfo.getCron());
        job.setJobClass(jobInfo.getJobClass());
        job.setDataMap(jobInfo.getDataMap());
        job.setEnabled(jobInfo.getEnabled());
        if (job.getEnabled()) {
            quartzClient.remove(JobInit.buildConfig(job));
            quartzClient.create(JobInit.buildConfig(job));
        } else {
            quartzClient.remove(JobInit.buildConfig(job));
        }
        updateById(job);
    }

    @Override
    public void updateJobCron(String id, String cron) throws ClassNotFoundException {
        JobInfo job = getById(id);
        if (job == null) {
            throw new RuntimeException("任务实例不存在");
        }
        checkTarget(job);
        if (cron.equals(job.getCron())) {
            return;
        }
        job.setCron(cron);
        // 先无条件摘掉旧触发器（不存在即空操作），再按启用状态决定是否重建，
        // 避免「库里已停用、Quartz 里还挂着老触发器」的残留继续按旧周期跑
        quartzClient.remove(JobInit.buildConfig(job));
        if (job.getEnabled()) {
            quartzClient.create(JobInit.buildConfig(job));
        }
        updateById(job);
    }

    @Override
    public boolean deleteJob(String id) throws ClassNotFoundException {
        JobInfo jobInfo = getById(id);
        if (jobInfo == null) {
            // 行已不在（重复删除 / 直接删库）：仍按 id 兜底摘一次可能残留在 Quartz 里的触发器，
            // 否则会出现「任务列表里没有、却还在按时执行」的幽灵任务
            removeQuietly(buildRemovalConfig(id));
            return true;
        }
        // 不能判断 enabled：状态曾经只写库不摘触发器（见 stopJob），于是一个「已禁用」的任务
        // 删除后触发器会永远留在 Quartz 里继续跑
        removeQuietly(JobInit.buildConfig(jobInfo));
        return removeById(id);
    }

    @Override
    public void startJob(String id) throws ClassNotFoundException {
        JobInfo jobInfo = getById(id);
        if (jobInfo == null) {
            throw new RuntimeException("任务实例不存在");
        }
        checkTarget(jobInfo);
        QuartzConfig config = JobInit.buildConfig(jobInfo);
        quartzClient.remove(config);
        quartzClient.create(config);
        // Quartz 操作成功之后再落库，避免「库里已启用、Quartz 里没注册」（重启后任务凭空消失）
        jobInfo.setEnabled(true);
        updateStatus(jobInfo);
    }

    @Override
    public void stopJob(String id) throws ClassNotFoundException {
        JobInfo jobInfo = getById(id);
        if (jobInfo == null) {
            throw new RuntimeException("任务实例不存在");
        }
        // 不能因为 DB 里 enabled=false 就 return：状态开关是先写库再调这里，
        // 旧实现因此在库里「已禁用」、Quartz 里仍在按时执行（直到进程重启才消失）
        quartzClient.remove(JobInit.buildConfig(jobInfo));
        jobInfo.setEnabled(false);
        updateStatus(jobInfo);
    }

    /**
     * 仅用任务 id 拼出「摘除触发器」用的配置（行已不在时兜底用）
     *
     * @param id 任务ID
     */
    private QuartzConfig buildRemovalConfig(String id) {
        try {
            return JobInit.buildRemovalConfig(Long.valueOf(id));
        } catch (NumberFormatException e) {
            // 非数字 id 不可能在 Quartz 里注册过
            return null;
        }
    }

    /**
     * 摘除 Quartz 触发器（失败只告警，不阻断任务记录的删除）
     *
     * @param config 任务配置（null 表示无需处理）
     */
    private void removeQuietly(QuartzConfig config) {
        if (config == null) {
            return;
        }
        try {
            quartzClient.remove(config);
        } catch (Exception e) {
            log.error("摘除 Quartz 触发器失败，任务记录仍会被删除 - identity: {}", config.getIdentity(), e);
        }
    }

    @Override
    public IPage<JobInfo> pageJobs(int page, int size, String type, String bizId, String keyword, Boolean enabled,
                                   Long tenantId, Long userId, boolean viewAll) {
        LambdaQueryWrapper<JobInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(JobInfo::getTenantId, tenantId);

        // 权限过滤（单轨权限）：没有平台管理员权限点的账号只能看自己创建的任务
        if (!viewAll) {
            wrapper.eq(JobInfo::getCreatedBy, userId);
        }

        // 类型过滤
        if (type != null && !type.isEmpty()) {
            wrapper.eq(JobInfo::getType, type);
        }

        // 关联业务过滤（智能体编排页只看当前智能体的定时任务）
        if (bizId != null && !bizId.isEmpty()) {
            wrapper.eq(JobInfo::getBizId, bizId);
        }

        // 启用状态过滤
        if (enabled != null) {
            wrapper.eq(JobInfo::getEnabled, enabled);
        }

        // 关键字搜索（搜索 dataMap 中的目标信息）
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.like(JobInfo::getDataMap, keyword);
        }

        wrapper.orderByDesc(JobInfo::getCreatedAt);
        return page(new Page<>(page, size), wrapper);
    }

    /**
     * 校验目标有效性（仅 AGENT 类型需校验智能体是否已禁用）
     */
    private void checkTarget(JobInfo jobInfo) {
        if (!"AGENT".equals(jobInfo.getType())) {
            return;
        }
        String agentId = jobInfo.getBizId();
        if (agentId == null) {
            return;
        }
        AgentDefinition agentDefinition = agentDefinitionService.getById(agentId);
        if (agentDefinition != null && !agentDefinition.getEnabled()) {
            throw new RuntimeException("智能体无效，不可设置定时");
        }
    }
}
