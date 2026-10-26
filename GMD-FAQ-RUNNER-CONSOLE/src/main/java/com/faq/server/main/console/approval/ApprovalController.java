package com.gmd.faq.server.main.console.approval;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gmd.faq.server.main.approval.service.ApprovalService;
import com.gmd.faq.server.main.common.dto.ApprovalFlowSaveDTO;
import com.gmd.faq.server.main.common.dto.ApprovalHandleDTO;
import com.gmd.faq.server.main.common.dto.ApprovalRequestQueryDTO;
import com.gmd.faq.server.main.common.dto.ApprovalSubmitDTO;
import com.gmd.faq.server.main.common.entity.ApprovalFlow;
import com.gmd.faq.server.main.common.enums.ApprovalResourceType;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.vo.ApprovalRequestVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 资源发布审批 Controller
 * <p>
 * 对应「审查管理 - 审查智能体 / 审查工作流」：审批流配置、审批工作台、发布申请提交。
 * 未启用审批的资源类型，提交申请后直接生效。
 *
 */
@RestController
@RequestMapping("/approval")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;

    /**
     * 可审批的资源类型（供前端下拉与页签渲染）
     */
    @GetMapping("/resource-types")
    public R<List<Map<String, String>>> resourceTypes() {
        List<Map<String, String>> options = new ArrayList<>();
        for (ApprovalResourceType item : ApprovalResourceType.values()) {
            Map<String, String> option = new LinkedHashMap<>();
            option.put("value", item.name());
            option.put("label", item.getDescription());
            options.add(option);
        }
        return R.data(options);
    }

    /**
     * 审批流配置列表（未配置的类型返回默认关闭项）
     */
    @GetMapping("/flow/list")
    public R<List<ApprovalFlow>> listFlows() {
        return R.data(approvalService.listFlows());
    }

    /**
     * 保存审批流配置
     */
    @PermNeed({"approval:flow:config"})
    @PutMapping("/flow")
    public R<Boolean> saveFlow(@RequestBody ApprovalFlowSaveDTO dto) {
        try {
            return R.data(approvalService.saveFlow(dto), "审批配置已保存");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    /**
     * 提交发布申请（未启用审批时直接生效）
     */
    @PostMapping("/request")
    public R<Map<String, Object>> submit(@RequestBody ApprovalSubmitDTO dto) {
        try {
            return R.data(approvalService.submit(dto));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    /**
     * 审批申请单分页查询
     *
     * @param query scope：pending=待我审批 / mine=我发起的 / all=全部
     */
    @PermNeed({"approval:request:list"})
    @GetMapping("/request/page")
    public R<IPage<ApprovalRequestVO>> page(PageParams pageParams, ApprovalRequestQueryDTO query) {
        return R.data(approvalService.pageRequests(pageParams, query));
    }

    /**
     * 待我审批数量（导航角标）
     */
    @GetMapping("/request/pending-count")
    public R<Long> pendingCount() {
        return R.data(approvalService.pendingCount());
    }

    /**
     * 通过审批（通过后资源随之发布生效）
     * 权限：{@code approval:request:approve}
     */
    @PermNeed({"approval:request:approve"})
    @PostMapping("/request/{id}/approve")
    public R<Boolean> approve(@PathVariable("id") Long id, @RequestBody(required = false) ApprovalHandleDTO dto) {
        try {
            return R.data(approvalService.approve(id, dto == null ? null : dto.getOpinion()), "已通过并发布");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    /**
     * 拒绝审批
     * 权限：{@code approval:request:approve}
     */
    @PermNeed({"approval:request:approve"})
    @PostMapping("/request/{id}/reject")
    public R<Boolean> reject(@PathVariable("id") Long id, @RequestBody(required = false) ApprovalHandleDTO dto) {
        try {
            return R.data(approvalService.reject(id, dto == null ? null : dto.getOpinion()), "已拒绝");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    /**
     * 撤回申请（申请人本人）
     */
    @PostMapping("/request/{id}/cancel")
    public R<Boolean> cancel(@PathVariable("id") Long id) {
        try {
            return R.data(approvalService.cancel(id), "已撤回");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }
}