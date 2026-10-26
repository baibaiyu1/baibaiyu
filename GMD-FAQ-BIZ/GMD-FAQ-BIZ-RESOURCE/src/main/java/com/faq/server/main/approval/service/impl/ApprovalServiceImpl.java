package com.gmd.faq.server.main.approval.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gmd.faq.server.main.approval.mapper.ApprovalFlowMapper;
import com.gmd.faq.server.main.approval.mapper.ApprovalRequestMapper;
import com.gmd.faq.server.main.approval.mapper.ResourcePublishMapper;
import com.gmd.faq.server.main.approval.service.ApprovalService;
import com.gmd.faq.server.main.common.cluster.core.MessagePublisher;
import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.dto.ApprovalFlowSaveDTO;
import com.gmd.faq.server.main.common.dto.ApprovalRequestQueryDTO;
import com.gmd.faq.server.main.common.dto.ApprovalSubmitDTO;
import com.gmd.faq.server.main.common.entity.ApprovalFlow;
import com.gmd.faq.server.main.common.entity.ApprovalRequest;
import com.gmd.faq.server.main.common.enums.ApprovalResourceType;
import com.gmd.faq.server.main.common.enums.ApprovalStatus;
import com.gmd.faq.server.main.common.mp.support.MP;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.util.BeanUtils;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.common.util.ResourceDeptSupport;
import com.gmd.faq.server.main.resource.visibility.mapper.ResourceGovernanceMapper;
import com.gmd.faq.server.main.common.vo.ApprovalRequestVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 资源发布审批 Service 实现
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalServiceImpl implements ApprovalService {

    private final ApprovalFlowMapper approvalFlowMapper;
    private final ApprovalRequestMapper approvalRequestMapper;
    private final ResourcePublishMapper resourcePublishMapper;
    private final ResourceGovernanceMapper resourceGovernanceMapper;
    private final MessagePublisher messagePublisher;

    @Override
    public List<ApprovalFlow> listFlows() {
        List<ApprovalFlow> exists = approvalFlowMapper.selectList(Wrappers.<ApprovalFlow>lambdaQuery());
        Map<String, ApprovalFlow> existMap = exists.stream()
                .collect(Collectors.toMap(ApprovalFlow::getResourceType, item -> item, (a, b) -> a));

        // 为未配置过的资源类型补默认项（不落库），前端可一次渲染全部类型
        List<ApprovalFlow> result = new ArrayList<>();
        for (ApprovalResourceType type : ApprovalResourceType.values()) {
            ApprovalFlow flow = existMap.get(type.name());
            if (flow != null) {
                result.add(flow);
                continue;
            }
            ApprovalFlow empty = new ApprovalFlow();
            empty.setResourceType(type.name());
            empty.setEnabled(false);
            result.add(empty);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveFlow(ApprovalFlowSaveDTO dto) {
        ApprovalResourceType type = ApprovalResourceType.parse(dto.getResourceType());
        if (type == null) {
            throw new IllegalArgumentException("资源类型不合法");
        }

        String approverIds = joinIds(dto.getApproverIds());
        String approverNames = dto.getApproverNames() == null ? null : String.join(",", dto.getApproverNames());
        Boolean enabled = Boolean.TRUE.equals(dto.getEnabled());
        if (enabled && (approverIds == null || approverIds.isBlank())) {
            throw new IllegalArgumentException("启用审批时必须至少指定一名审批人");
        }

        ApprovalFlow exists = approvalFlowMapper.selectOne(Wrappers.<ApprovalFlow>lambdaQuery()
                .eq(ApprovalFlow::getResourceType, type.name()));

        if (exists == null) {
            ApprovalFlow flow = new ApprovalFlow();
            flow.setResourceType(type.name());
            flow.setEnabled(enabled);
            flow.setApproverIds(approverIds);
            flow.setApproverNames(approverNames);
            flow.setRemark(dto.getRemark());
            approvalFlowMapper.insert(flow);
            return true;
        }

        exists.setEnabled(enabled);
        exists.setApproverIds(approverIds);
        exists.setApproverNames(approverNames);
        exists.setRemark(dto.getRemark());
        approvalFlowMapper.updateById(exists);

        // 关闭审批时，清理该类型下滞留的待审批申请，避免无法再处理
        if (!enabled) {
            cancelPendingOfType(type.name(), "审批已关闭，申请自动取消");
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(ApprovalSubmitDTO dto) {
        ApprovalResourceType type = ApprovalResourceType.parse(dto.getResourceType());
        if (type == null) {
            throw new IllegalArgumentException("资源类型不合法");
        }
        if (dto.getResourceId() == null || dto.getResourceId().isBlank()) {
            throw new IllegalArgumentException("资源ID不能为空");
        }
        Long resourceId;
        try {
            resourceId = Long.valueOf(dto.getResourceId());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("资源ID非法");
        }
        assertPublishable(type, resourceId);

        ApprovalFlow flow = approvalFlowMapper.selectOne(Wrappers.<ApprovalFlow>lambdaQuery()
                .eq(ApprovalFlow::getResourceType, type.name()));

        Map<String, Object> result = new LinkedHashMap<>();
        Long accountId = UserUtils.getId();
        String account = UserUtils.getAccount();

        // 未配置或未启用审批：直接生效，保留一次申请记录便于追溯
        if (flow == null || !Boolean.TRUE.equals(flow.getEnabled())) {
            applyEffect(type, dto.getResourceId());
            ApprovalRequest auto = new ApprovalRequest();
            auto.setResourceType(type.name());
            auto.setResourceId(dto.getResourceId());
            auto.setResourceName(dto.getResourceName());
            auto.setResourceVersion(dto.getResourceVersion());
            auto.setApplicantId(accountId);
            auto.setApplicantName(account);
            auto.setStatus(ApprovalStatus.APPROVED.name());
            auto.setOpinion("未启用审批，提交后直接生效");
            auto.setRemark(dto.getRemark());
            auto.setHandledAt(LocalDateTime.now());
            approvalRequestMapper.insert(auto);

            result.put("autoApproved", true);
            result.put("message", "未启用审批，已直接发布");
            result.put("request", null);
            return result;
        }

        Long pending = approvalRequestMapper.selectCount(Wrappers.<ApprovalRequest>lambdaQuery()
                .eq(ApprovalRequest::getResourceType, type.name())
                .eq(ApprovalRequest::getResourceId, dto.getResourceId())
                .eq(ApprovalRequest::getStatus, ApprovalStatus.PENDING.name()));
        if (pending != null && pending > 0) {
            throw new IllegalStateException("该资源已有待审批的发布申请，请先处理后再提交");
        }

        ApprovalRequest request = new ApprovalRequest();
        request.setResourceType(type.name());
        request.setResourceId(dto.getResourceId());
        request.setResourceName(dto.getResourceName());
        request.setResourceVersion(dto.getResourceVersion());
        request.setApplicantId(accountId);
        request.setApplicantName(account);
        request.setStatus(ApprovalStatus.PENDING.name());
        request.setRemark(dto.getRemark());
        approvalRequestMapper.insert(request);

        result.put("autoApproved", false);
        result.put("message", "已提交发布审批，等待审批人处理");
        boolean admin = UserUtils.isAdmin();
        result.put("request", toVO(request, admin, accountId, listApprovableTypes(accountId, admin)));
        return result;
    }

    @Override
    public IPage<ApprovalRequestVO> pageRequests(PageParams pageParams, ApprovalRequestQueryDTO query) {
        Long accountId = UserUtils.getId();
        boolean admin = UserUtils.isAdmin();
        Set<String> approvableTypes = listApprovableTypes(accountId, admin);

        var wrapper = Wrappers.<ApprovalRequest>lambdaQuery();

        String scope = query.getScope() == null ? "mine" : query.getScope();
        switch (scope) {
            case "pending" -> {
                wrapper.eq(ApprovalRequest::getStatus, ApprovalStatus.PENDING.name());
                if (!admin) {
                    if (approvableTypes.isEmpty()) {
                        // 无任何可审批类型：返回空页，避免误查全量
                        wrapper.eq(ApprovalRequest::getApplicantId, -1L);
                    } else {
                        wrapper.in(ApprovalRequest::getResourceType, approvableTypes);
                    }
                }
            }
            case "all" -> {
                if (!admin) {
                    if (approvableTypes.isEmpty()) {
                        wrapper.eq(ApprovalRequest::getApplicantId, accountId);
                    } else {
                        wrapper.and(w -> w.eq(ApprovalRequest::getApplicantId, accountId)
                                .or().in(ApprovalRequest::getResourceType, approvableTypes));
                    }
                }
            }
            default -> wrapper.eq(ApprovalRequest::getApplicantId, accountId);
        }

        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            wrapper.eq(ApprovalRequest::getStatus, query.getStatus());
        }
        if (query.getResourceType() != null && !query.getResourceType().isBlank()) {
            wrapper.eq(ApprovalRequest::getResourceType, query.getResourceType());
        }
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            String keyword = query.getKeyword().trim();
            wrapper.and(w -> w.like(ApprovalRequest::getResourceName, keyword)
                    .or().like(ApprovalRequest::getApplicantName, keyword));
        }
        wrapper.orderByDesc(ApprovalRequest::getCreatedAt);

        IPage<ApprovalRequest> page = approvalRequestMapper.selectPage(MP.getPage(pageParams), wrapper);
        IPage<ApprovalRequestVO> voPage = BeanUtils.copyPage(page, ApprovalRequestVO.class);
        List<ApprovalRequestVO> records = page.getRecords().stream()
                .map(entity -> toVO(entity, admin, accountId, approvableTypes))
                .collect(Collectors.toList());
        voPage.setRecords(records);
        return voPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean approve(Long id, String opinion) {
        ApprovalRequest request = requireHandleable(id);
        request.setStatus(ApprovalStatus.APPROVED.name());
        request.setOpinion(opinion);
        request.setApproverId(UserUtils.getId());
        request.setApproverName(UserUtils.getAccount());
        request.setHandledAt(LocalDateTime.now());
        approvalRequestMapper.updateById(request);

        ApprovalResourceType type = ApprovalResourceType.parse(request.getResourceType());
        if (type == null) {
            throw new IllegalStateException("资源类型不合法，无法发布");
        }
        applyEffect(type, request.getResourceId());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean reject(Long id, String opinion) {
        ApprovalRequest request = requireHandleable(id);
        request.setStatus(ApprovalStatus.REJECTED.name());
        request.setOpinion(opinion);
        request.setApproverId(UserUtils.getId());
        request.setApproverName(UserUtils.getAccount());
        request.setHandledAt(LocalDateTime.now());
        approvalRequestMapper.updateById(request);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean cancel(Long id) {
        ApprovalRequest request = approvalRequestMapper.selectById(id);
        if (request == null) {
            throw new IllegalStateException("审批申请不存在");
        }
        if (!ApprovalStatus.PENDING.name().equals(request.getStatus())) {
            throw new IllegalStateException("仅待审批的申请可以撤回");
        }
        if (!UserUtils.getId().equals(request.getApplicantId())) {
            throw new IllegalStateException("只能撤回自己发起的申请");
        }
        request.setStatus(ApprovalStatus.CANCELED.name());
        request.setOpinion("申请人已撤回");
        request.setHandledAt(LocalDateTime.now());
        approvalRequestMapper.updateById(request);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelPendingForResource(ApprovalResourceType type, Long resourceId, String opinion) {
        List<ApprovalRequest> pendings = approvalRequestMapper.selectList(Wrappers.<ApprovalRequest>lambdaQuery()
                .eq(ApprovalRequest::getResourceType, type.name())
                .eq(ApprovalRequest::getResourceId, String.valueOf(resourceId))
                .eq(ApprovalRequest::getStatus, ApprovalStatus.PENDING.name()));
        for (ApprovalRequest item : pendings) {
            item.setStatus(ApprovalStatus.CANCELED.name());
            item.setOpinion(opinion);
            item.setHandledAt(LocalDateTime.now());
            approvalRequestMapper.updateById(item);
        }
    }

    @Override
    public Long pendingCount() {
        Set<String> approvableTypes = listApprovableTypes(UserUtils.getId(), UserUtils.isAdmin());
        var wrapper = Wrappers.<ApprovalRequest>lambdaQuery()
                .eq(ApprovalRequest::getStatus, ApprovalStatus.PENDING.name());
        if (!UserUtils.isAdmin()) {
            if (approvableTypes.isEmpty()) {
                return 0L;
            }
            wrapper.in(ApprovalRequest::getResourceType, approvableTypes);
        }
        return approvalRequestMapper.selectCount(wrapper);
    }

    /**
     * 校验申请单存在、处于待审批状态，且当前用户有权处理
     */
    private ApprovalRequest requireHandleable(Long id) {
        ApprovalRequest request = approvalRequestMapper.selectById(id);
        if (request == null) {
            throw new IllegalStateException("审批申请不存在");
        }
        if (!ApprovalStatus.PENDING.name().equals(request.getStatus())) {
            throw new IllegalStateException("该申请已处理，无法重复操作");
        }
        Set<String> approvableTypes = listApprovableTypes(UserUtils.getId(), UserUtils.isAdmin());
        if (!approvableTypes.contains(request.getResourceType())) {
            throw new IllegalStateException("您不是该资源类型的审批人，无权处理");
        }
        return request;
    }

    /**
     * 当前用户可审批的资源类型集合（管理员视为全部类型）
     */
    private Set<String> listApprovableTypes(Long accountId, boolean admin) {
        if (admin) {
            return Arrays.stream(ApprovalResourceType.values())
                    .map(Enum::name)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        List<ApprovalFlow> flows = approvalFlowMapper.selectList(Wrappers.<ApprovalFlow>lambdaQuery()
                .eq(ApprovalFlow::getEnabled, true));
        return flows.stream()
                .filter(flow -> containsAccount(flow.getApproverIds(), accountId))
                .map(ApprovalFlow::getResourceType)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 取消某资源类型下所有待审批申请（审批被关闭时调用）
     */
    private void cancelPendingOfType(String resourceType, String opinion) {
        List<ApprovalRequest> pendings = approvalRequestMapper.selectList(Wrappers.<ApprovalRequest>lambdaQuery()
                .eq(ApprovalRequest::getResourceType, resourceType)
                .eq(ApprovalRequest::getStatus, ApprovalStatus.PENDING.name()));
        for (ApprovalRequest item : pendings) {
            item.setStatus(ApprovalStatus.CANCELED.name());
            item.setOpinion(opinion);
            item.setHandledAt(LocalDateTime.now());
            approvalRequestMapper.updateById(item);
        }
    }

    /**
     * 审批通过后的资源生效动作
     */
    private void applyEffect(ApprovalResourceType type, String resourceId) {
        Long id;
        try {
            id = Long.valueOf(resourceId);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("资源ID非法，无法发布：" + resourceId);
        }
        switch (type) {
            case AGENT -> {
                if (resourcePublishMapper.publishAgent(id) == 0) {
                    throw new IllegalStateException("智能体不存在");
                }
                messagePublisher.publishAfterCommit(RedisChannelTopic.AGENT_REREGISTER_CHANNEL, String.valueOf(id));
            }
            case WORKFLOW -> resourcePublishMapper.publishWorkflow(id);
            default -> log.info("资源类型 {} 暂未定义发布生效动作，仅记录审批结果（资源ID={}）", type.name(), resourceId);
        }
    }

    private void assertPublishable(ApprovalResourceType type, Long id) {
        List<Map<String, Object>> rows = switch (type) {
            case AGENT -> resourceGovernanceMapper.selectOwnerId(TableConst.AGENT, id);
            case TOOL -> resourceGovernanceMapper.selectOwnerId(TableConst.TOOL, id);
            case SKILL -> resourceGovernanceMapper.selectOwnerId(TableConst.SKILL, id);
            case KNOWLEDGE -> resourceGovernanceMapper.selectOwnerId(TableConst.KNOWLEDGE, id);
            case MCP -> resourceGovernanceMapper.selectOwnerId(TableConst.MCP, id);
            case WORKFLOW -> resourcePublishMapper.selectWorkflowCreator(id);
        };
        if (rows.isEmpty()) {
            throw new IllegalStateException("资源不存在");
        }
        if (type != ApprovalResourceType.WORKFLOW) {
            com.gmd.faq.server.main.common.entity.AgentDefinition resource = new com.gmd.faq.server.main.common.entity.AgentDefinition();
            Map<String, Object> row = rows.getFirst();
            resource.setOwnerId(ResourceDeptSupport.toLong(row.get("owner_id")));
            resource.setResourceDeptId(ResourceDeptSupport.toLong(row.get("resource_dept_id")));
            resource.setOwnershipType((String) row.get("ownership_type"));
            resource.setOwningDeptId(ResourceDeptSupport.toLong(row.get("owning_dept_id")));
            resource.setMaintainerId(ResourceDeptSupport.toLong(row.get("maintainer_id")));
            com.gmd.faq.server.main.common.util.ResourceOwnershipSupport.assertManageable(resource, "申请发布");
            return;
        }
        Long ownerId = ResourceDeptSupport.toLong(rows.getFirst().get(
                type == ApprovalResourceType.WORKFLOW ? "created_by" : "owner_id"));
        if (!UserUtils.isAdmin() && (ownerId == null || !ownerId.equals(UserUtils.getId()))) {
            throw new IllegalStateException("只有资源归属人或管理员可以申请发布");
        }
    }

    /**
     * 逗号分隔的审批人ID串是否包含指定账号
     */
    private boolean containsAccount(String ids, Long accountId) {
        if (ids == null || ids.isBlank() || accountId == null) {
            return false;
        }
        for (String item : ids.split(",")) {
            if (item.trim().equals(String.valueOf(accountId))) {
                return true;
            }
        }
        return false;
    }

    private String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private ApprovalRequestVO toVO(ApprovalRequest entity, boolean admin, Long accountId, Set<String> approvableTypes) {
        ApprovalRequestVO vo = BeanUtils.copy(entity, ApprovalRequestVO.class);
        if (vo == null) {
            return null;
        }
        ApprovalResourceType type = ApprovalResourceType.parse(entity.getResourceType());
        vo.setResourceTypeDesc(type == null ? entity.getResourceType() : type.getDescription());
        ApprovalStatus status = ApprovalStatus.parse(entity.getStatus());
        vo.setStatusDesc(status == null ? entity.getStatus() : status.getDescription());
        vo.setMine(accountId != null && accountId.equals(entity.getApplicantId()));
        vo.setHandleable(ApprovalStatus.PENDING.name().equals(entity.getStatus())
                && approvableTypes.contains(entity.getResourceType()));
        return vo;
    }
}
