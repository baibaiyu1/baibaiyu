package com.gmd.faq.server.main.common.vo;

import lombok.Data;

import java.util.List;

/**
 * 会话消息分支 VO
 * <p>
 * 描述当前对话路径上某个消息的兄弟分支情况（同一父节点下的多个候选回复）。
 * 仅当兄弟数大于 1 时才会返回。
 *
 */
@Data
public class MessageBranchVO {

    /**
     * 当前路径上的消息ID
     */
    private Integer messageId;

    /**
     * 父消息ID（兄弟分支的共同父节点）
     */
    private Integer parentId;

    /**
     * 兄弟消息ID列表（按创建顺序升序，包含当前消息自身）
     */
    private List<Integer> siblingIds;

    /**
     * 当前消息在兄弟列表中的下标（从 0 开始）
     */
    private Integer currentIndex;

    /**
     * 兄弟总数（即可选分支数）
     */
    private Integer total;
}
