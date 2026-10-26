package com.gmd.faq.server.main.scheduler.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.entity.JobInfo;
import com.gmd.faq.server.main.common.entity.JobRecord;
import com.gmd.faq.server.main.common.enums.TenantRole;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.scheduler.core.cluster.JobMessagePublisher;
import com.gmd.faq.server.main.scheduler.core.client.QuartzClient;
import com.gmd.faq.server.main.scheduler.init.JobInit;
import com.gmd.faq.server.main.scheduler.service.QuartzInfoService;
import com.gmd.faq.server.main.scheduler.service.QuartzRecordService;
import com.gmd.faq.server.main.scheduler.vo.JobRecordVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 描述：web api
 *
 **/

@RestController
@RequestMapping("/runtime/job")
@RequiredArgsConstructor
public class JobController {
    private final QuartzInfoService quartzInfoService;
    private final JobMessagePublisher jobMessagePublisher;
    private final QuartzRecordService quartzRecordService;
    private final QuartzClient quartzClient;

    @GetMapping("list")
    public R<List<JobInfo>> list() {
        return R.data(quartzInfoService.list());
    }

    @PostMapping("/add")
    @PermNeed({"automation:job:add"})
    public R<Boolean> add(@RequestBody JobInfo jobInfo) throws ClassNotFoundException {
        quartzInfoService.addJob(jobInfo);
        // 广播消息通知其他节点
        jobMessagePublisher.publishAdd(jobInfo);
        return R.data(true);
    }

    @PostMapping("/update")
    @PermNeed({"automation:job:edit"})
    public R<Boolean> update(@RequestBody JobInfo jobInfo) throws ClassNotFoundException {
        quartzInfoService.updateJob(jobInfo);
        // 从DB获取完整对象（含createdBy），确保集群同步消息携带完整信息
        JobInfo dbJob = quartzInfoService.getById(jobInfo.getId());
        jobMessagePublisher.publishUpdate(dbJob);
        return R.data(true);
    }

    @GetMapping("/updateCron")
    @PermNeed({"automation:job:edit"})
    public R<Boolean> updateCron(@RequestParam("id") String id, @RequestParam("cron") String cron) throws ClassNotFoundException {
        quartzInfoService.updateJobCron(id, cron);
        // 广播消息通知其他节点
        jobMessagePublisher.publishUpdateCron(id, cron);
        return R.data(true);
    }

    @GetMapping("/delete")
    @PermNeed({"automation:job:remove"})
    public R<Boolean> delete(@RequestParam("id") String id) throws ClassNotFoundException {
        boolean result = quartzInfoService.deleteJob(id);
        // 广播消息通知其他节点
        jobMessagePublisher.publishDelete(id);
        return R.data(result);
    }

    @GetMapping("/start")
    @PermNeed({"automation:job:edit"})
    public R<Boolean> start(@RequestParam("id") String id) throws ClassNotFoundException {
        quartzInfoService.startJob(id);
        // 广播消息通知其他节点
        jobMessagePublisher.publishStart(id);
        return R.data(true);
    }

    @GetMapping("stop")
    @PermNeed({"automation:job:edit"})
    public R<Boolean> stop(@RequestParam("id") String id) throws ClassNotFoundException {
        quartzInfoService.stopJob(id);
        // 广播消息通知其他节点
        jobMessagePublisher.publishStop(id);
        return R.data(true);
    }

    /**
     * 根据业务ID查询定时任务
     * GET /job/getByBizId
     *
     * 注意：一个智能体已支持绑定**多个**定时任务，这里只返回最近创建的一条
     * （不返回列表是为了兼容既有调用方；需要全部任务请用 /job/page?bizId=）。
     *
     * @param bizId 业务ID（即agentId）
     * @return 定时任务信息
     */
    @GetMapping("/getByBizId")
    public R<JobInfo> getByBizId(@RequestParam("bizId") String bizId) {
        return R.data(quartzInfoService.lambdaQuery()
                .eq(JobInfo::getBizId, bizId)
                .eq(JobInfo::getType, "AGENT")
                .orderByDesc(JobInfo::getCreatedAt)
                .last("LIMIT 1")
                .one());
    }

    /**
     * 根据任务ID查询详情
     * GET /job/{id}
     */
    @GetMapping("/{id}")
    public R<JobInfo> detail(@PathVariable("id") String id) {
        return R.data(quartzInfoService.getById(id));
    }

    /**
     * 根据业务ID删除定时任务（用于解绑）
     * GET /job/deleteByBizId
     *
     * 一个智能体可绑定多个定时任务，这里只解绑最近创建的一条；
     * 编排页按任务删除请用 /job/delete?id=。
     *
     * @param bizId 业务ID（即agentId）
     * @return 是否删除成功
     */
    @GetMapping("/deleteByBizId")
    @PermNeed({"automation:job:remove"})
    public R<Boolean> deleteByBizId(@RequestParam("bizId") String bizId) throws ClassNotFoundException {
        JobInfo jobInfo = quartzInfoService.lambdaQuery()
                .eq(JobInfo::getBizId, bizId)
                .eq(JobInfo::getType, "AGENT")
                .orderByDesc(JobInfo::getCreatedAt)
                .last("LIMIT 1")
                .one();
        if (jobInfo != null) {
            String jobId = String.valueOf(jobInfo.getId());
            // 如果任务正在运行，先停止
            if (jobInfo.getEnabled()) {
                quartzInfoService.stopJob(jobId);
            }
            boolean result = quartzInfoService.deleteJob(jobId);
            // 广播消息通知其他节点
            jobMessagePublisher.publishDelete(jobId);
            return R.data(result);
        }
        return R.data(true);
    }

    /**
     * 手动触发一次执行
     * GET /job/trigger
     */
    @GetMapping("/trigger")
    @PermNeed({"automation:job:run"})
    public R<Boolean> trigger(@RequestParam("id") String id) throws ClassNotFoundException {
        JobInfo jobInfo = quartzInfoService.getById(id);
        if (jobInfo == null) {
            throw new RuntimeException("任务实例不存在");
        }
        quartzClient.createOnce(JobInit.buildConfig(jobInfo), true);
        return R.data(true);
    }

    /**
     * 查询任务运行记录（分页）
     * GET /job/records
     *
     * @param jobId 任务ID
     * @param page  页码（默认1）
     * @param size  每页数量（默认50）
     */
    @GetMapping("/records")
    public R<IPage<JobRecordVO>> records(
            @RequestParam("jobId") Long jobId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        IPage<JobRecord> recordPage = quartzRecordService.lambdaQuery()
                .eq(JobRecord::getJobId, jobId)
                .orderByDesc(JobRecord::getCreateTime)
                .page(new Page<>(page, size));
        IPage<JobRecordVO> resultPage = new Page<>(page, size, recordPage.getTotal());
        List<JobRecordVO> voList = recordPage.getRecords().stream()
                .map(r -> JobRecordVO.builder()
                        .jobId(r.getJobId())
                        .recordId(r.getRecordId())
                        .createTime(r.getCreateTime())
                        .build())
                .collect(Collectors.toList());
        resultPage.setRecords(voList);
        return R.data(resultPage);
    }

    /**
     * 分页查询自动化任务
     * GET /job/page
     *
     * @param page     页码
     * @param size     每页数量
     * @param type     任务类型（可选）
     * @param bizId    关联业务ID（可选，智能体编排页按智能体过滤）
     * @param keyword  关键字搜索（可选）
     * @param enabled  启用状态（可选，null = 不限）
     * @return 分页结果
     */
    @GetMapping("/page")
    public R<IPage<JobInfo>> page(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "50") int size,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "bizId", required = false) String bizId,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "enabled", required = false) Boolean enabled) {
        UserDetail userDetail = UserUtils.getUserDetail();
        Long currentTenantId = TenantUtils.getCurrentTenantId();
        // 指定 bizId 时按「业务资源」维度看：智能体的定时任务是该智能体的配置，
        // 同租户内能看到该智能体的人也应能看到它的任务列表（否则列表与「共 N 条」会对不上）。
        boolean viewAll = UserUtils.isAdmin() || (bizId != null && !bizId.isEmpty());
        IPage<JobInfo> result = quartzInfoService.pageJobs(
                page, size, type, bizId, keyword, enabled,
                currentTenantId, userDetail.getId(), viewAll);
        return R.data(result);
    }

    /**
     * 启用/禁用切换
     * GET /job/toggle
     *
     * @param id 任务ID
     * @return 操作结果
     */
    @GetMapping("/toggle")
    @PermNeed({"automation:job:edit"})
    public R<Boolean> toggle(@RequestParam("id") String id) throws ClassNotFoundException {
        JobInfo jobInfo = quartzInfoService.getById(id);
        if (jobInfo == null) {
            throw new RuntimeException("任务实例不存在");
        }
        boolean newEnabled = !jobInfo.getEnabled();
        // 状态由 startJob / stopJob 内部落库：先动 Quartz 再写库，
        // 避免出现「界面已禁用、后台还在跑」或「界面已启用、重启后却不跑」的不一致
        if (newEnabled) {
            quartzInfoService.startJob(id);
            // 广播消息通知其他节点
            jobMessagePublisher.publishStart(id);
        } else {
            quartzInfoService.stopJob(id);
            // 广播消息通知其他节点
            jobMessagePublisher.publishStop(id);
        }
        return R.data(true);
    }
}