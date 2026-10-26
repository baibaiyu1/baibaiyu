package com.gmd.faq.server.main.scheduler.core.cluster;

import com.gmd.faq.server.main.common.cluster.core.ChannelSubscriber;
import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import com.gmd.faq.server.main.common.entity.JobInfo;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.scheduler.core.client.QuartzClient;
import com.gmd.faq.server.main.scheduler.core.cluster.message.JobControlMessage;
import com.gmd.faq.server.main.scheduler.core.config.QuartzConfig;
import com.gmd.faq.server.main.scheduler.init.JobInit;
import com.gmd.faq.server.main.scheduler.service.QuartzInfoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.Topic;
import org.springframework.stereotype.Component;

/**
 * 描述：任务消息订阅者
 * 接收Redis发布的集群控制消息并处理
 *
 **/
@Slf4j
@Component
@RequiredArgsConstructor
public class JobMessageSubscriber implements ChannelSubscriber {

    private final NodeConfig nodeConfig;
    private final QuartzClient quartzClient;
    private final QuartzInfoService quartzInfoService;

    @Override
    public Topic getTopic() {
        return new ChannelTopic(RedisChannelTopic.JOB_CLUSTER_CONTROL);
    }

    @Override
    public void onMessage(String channel, String message) {
        try {
            // 只处理任务控制消息
            if (!channel.equals(RedisChannelTopic.JOB_CLUSTER_CONTROL)) {
                return;
            }

            JobControlMessage controlMessage = JsonUtils.parse(message, JobControlMessage.class);
            if (controlMessage == null) {
                log.warn("解析任务控制消息失败: {}", message);
                return;
            }

            // 忽略本节点发出的消息
            if (nodeConfig.getNodeId().equals(controlMessage.getSourceNodeId())) {
                log.debug("忽略本节点发出的消息 - action: {}, jobId: {}",
                        controlMessage.getAction(), controlMessage.getJobId());
                return;
            }

            log.info("接收到集群任务控制消息 - action: {}, jobId: {}, sourceNode: {}",
                    controlMessage.getAction(), controlMessage.getJobId(), controlMessage.getSourceNodeId());

            // 处理消息
            handleMessage(controlMessage);

        } catch (Exception e) {
            log.error("处理任务控制消息异常", e);
        }
    }

    /**
     * 处理控制消息
     */
    private void handleMessage(JobControlMessage message) throws ClassNotFoundException {
        switch (message.getAction()) {
            case ADD:
                handleAdd(message);
                break;
            case UPDATE:
                handleUpdate(message);
                break;
            case DELETE:
                handleDelete(message);
                break;
            case UPDATE_CRON:
                handleUpdateCron(message);
                break;
            case START:
                handleStart(message);
                break;
            case STOP:
                handleStop(message);
                break;
            default:
                log.warn("未知的操作类型: {}", message.getAction());
        }
    }

    /**
     * 处理添加任务
     */
    private void handleAdd(JobControlMessage message) throws ClassNotFoundException {
        JobInfo jobInfo = quartzInfoService.getById(message.getJobId());
        if (jobInfo == null) {
            log.warn("添加任务时任务不存在: {}", message.getJobId());
            return;
        }
        if (jobInfo.getEnabled()) {
            quartzClient.create(JobInit.buildConfig(jobInfo));
            log.info("集群同步 - 添加并启动任务: {}", message.getJobId());
        } else {
            log.info("集群同步 - 添加任务（未启用）: {}", message.getJobId());
        }
    }

    /**
     * 处理更新任务
     */
    private void handleUpdate(JobControlMessage message) throws ClassNotFoundException {
        JobInfo jobInfo = quartzInfoService.getById(message.getJobId());
        if (jobInfo == null) {
            log.warn("更新任务时任务不存在: {}", message.getJobId());
            return;
        }
        // 先移除再创建（相当于更新）
        quartzClient.remove(JobInit.buildConfig(jobInfo));
        if (jobInfo.getEnabled()) {
            quartzClient.create(JobInit.buildConfig(jobInfo));
        }
        log.info("集群同步 - 更新任务: {}", message.getJobId());
    }

    /**
     * 处理删除任务
     *
     * 删除广播是在删库之后发出的，因此**不能**依赖 getById 拿到任务：
     * 行已经没了也要按 id 把本节点上残留的触发器摘掉（否则本节点会继续执行幽灵任务）。
     */
    private void handleDelete(JobControlMessage message) throws ClassNotFoundException {
        JobInfo jobInfo = quartzInfoService.getById(message.getJobId());
        if (jobInfo != null) {
            quartzClient.remove(JobInit.buildConfig(jobInfo));
            log.info("集群同步 - 删除任务: {}", message.getJobId());
            return;
        }
        QuartzConfig removalConfig = buildRemovalConfig(message.getJobId());
        if (removalConfig != null) {
            quartzClient.remove(removalConfig);
            log.info("集群同步 - 删除任务（任务记录已不存在，按 id 兜底摘除触发器）: {}", message.getJobId());
        }
    }

    /**
     * 处理更新Cron
     */
    private void handleUpdateCron(JobControlMessage message) throws ClassNotFoundException {
        JobInfo jobInfo = quartzInfoService.getById(message.getJobId());
        if (jobInfo == null) {
            log.warn("更新Cron时任务不存在: {}", message.getJobId());
            return;
        }
        jobInfo.setCron(message.getCron());
        if (jobInfo.getEnabled()) {
            quartzClient.remove(JobInit.buildConfig(jobInfo));
            quartzClient.create(JobInit.buildConfig(jobInfo));
        }
        log.info("集群同步 - 更新任务Cron: {}", message.getJobId());
    }

    /**
     * 处理启动任务
     */
    private void handleStart(JobControlMessage message) throws ClassNotFoundException {
        JobInfo jobInfo = quartzInfoService.getById(message.getJobId());
        if (jobInfo == null) {
            log.warn("启动任务时任务不存在: {}", message.getJobId());
            return;
        }
        quartzClient.remove(JobInit.buildConfig(jobInfo));
        quartzClient.create(JobInit.buildConfig(jobInfo));
        log.info("集群同步 - 启动任务: {}", message.getJobId());
    }

    /**
     * 处理停止任务
     *
     * 同 handleDelete：库里已经是 disabled 也不能跳过（状态开关会先写库再广播），
     * 只要本节点上还挂着触发器就必须摘掉。
     */
    private void handleStop(JobControlMessage message) throws ClassNotFoundException {
        JobInfo jobInfo = quartzInfoService.getById(message.getJobId());
        if (jobInfo != null) {
            quartzClient.remove(JobInit.buildConfig(jobInfo));
            log.info("集群同步 - 停止任务: {}", message.getJobId());
            return;
        }
        QuartzConfig removalConfig = buildRemovalConfig(message.getJobId());
        if (removalConfig != null) {
            quartzClient.remove(removalConfig);
            log.info("集群同步 - 停止任务（任务记录已不存在，按 id 兜底摘除触发器）: {}", message.getJobId());
        }
    }

    /**
     * 任务行不存在时，仅按 id 拼出摘除触发器用的极简配置
     *
     * @param jobId 任务ID
     */
    private QuartzConfig buildRemovalConfig(String jobId) {
        try {
            return JobInit.buildRemovalConfig(Long.valueOf(jobId));
        } catch (NumberFormatException e) {
            // 非数字 id 不可能在 Quartz 里注册过
            return null;
        }
    }
}
