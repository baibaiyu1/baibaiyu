package com.gmd.faq.server.main.approval.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gmd.faq.server.main.common.dto.ApprovalFlowSaveDTO;
import com.gmd.faq.server.main.common.dto.ApprovalRequestQueryDTO;
import com.gmd.faq.server.main.common.dto.ApprovalSubmitDTO;
import com.gmd.faq.server.main.common.entity.ApprovalFlow;
import com.gmd.faq.server.main.common.enums.ApprovalResourceType;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.vo.ApprovalRequestVO;

import java.util.List;
import java.util.Map;

/**
 * 资源发布审批 Service
 * <p>
 * 承载「申请 → 审批人处理 → 通过后生效」闭环：
 * 未启用审批的资源类型提交后直接生效；启用后进入待审批队列。
 *
 */
public interface ApprovalService {

    /**
     * 审批流配置列表（缺失的资源类型补默认未启用项，便于前端一次渲染）
     *
     * @return 配置列表
     */
    List<ApprovalFlow> listFlows();

    /**
     * 保存审批流配置（按「租户 + 资源类型」 upsert）
     *
     * @param dto 配置
     * @return 是否成功
     */
    Boolean saveFlow(ApprovalFlowSaveDTO dto);

    /**
     * 提交发布申请
     * <p>
     * 返回结构：{@code autoApproved} 为 true 表示该资源类型未启用审批、已直接生效；
     * {@code request} 为生成的申请单（直接生效时为 null）。
     *
     * @param dto 申请内容
     * @return 提交结果
     */
    Map<String, Object> submit(ApprovalSubmitDTO dto);

    /**
     * 审批申请单分页查询
     *
     * @param pageParams 分页参数
     * @param query      查询条件（scope：pending=待我审批 / mine=我发起的 / all=全部）
     * @return 分页结果
     */
    IPage<ApprovalRequestVO> pageRequests(PageParams pageParams, ApprovalRequestQueryDTO query);

    /**
     * 通过审批并生效
     *
     * @param id      申请单ID
     * @param opinion 审批意见
     * @return 是否成功
     */
    Boolean approve(Long id, String opinion);

    /**
     * 拒绝审批
     *
     * @param id      申请单ID
     * @param opinion 审批意见
     * @return 是否成功
     */
    Boolean reject(Long id, String opinion);

    /**
     * 撤回申请（仅申请人本人、且处于待审批状态）
     *
     * @param id 申请单ID
     * @return 是否成功
     */
    Boolean cancel(Long id);

    /** A changed resource must be submitted again before it can be approved. */
    void cancelPendingForResource(ApprovalResourceType type, Long resourceId, String opinion);

    /**
     * 待我审批数量（用于导航角标）
     *
     * @return 数量
     */
    Long pendingCount();
}
