package com.gmd.faq.server.main.approval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gmd.faq.server.main.common.entity.ApprovalRequest;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审批申请单 Mapper
 *
 */
@Mapper
public interface ApprovalRequestMapper extends BaseMapper<ApprovalRequest> {
}
