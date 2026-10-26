package com.gmd.faq.server.main.agent.service.impl;

import com.gmd.faq.server.main.agent.mapper.ChatMessageMapper;
import com.gmd.faq.server.main.agent.service.ChatMessageService;
import com.gmd.faq.server.main.common.entity.ChatMessage;
import com.gmd.faq.server.main.common.router.MessageTableRouter;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 聊天消息 Service 实现
 *
 */
@Service
@RequiredArgsConstructor
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements ChatMessageService {

    private final MessageTableRouter messageTableRouter;

    @Override
    public List<ChatMessage> listByIdsOrderByDepth(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return lambdaQuery()
                .in(ChatMessage::getId, ids)
                .orderByAsc(ChatMessage::getDepth)
                .list();
    }

    @Override
    public List<ChatMessage> listByIdsOrderByDepth(List<Integer> ids, String messageTable) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        // 如果未指定归档表，走主表 MyBatis-Plus 查询
        if (messageTable == null || messageTable.isBlank()) {
            return listByIdsOrderByDepth(ids);
        }
        // 已归档：走 MessageTableRouter 查询归档表
        return messageTableRouter.listByIdsOrderByDepth(ids, messageTable);
    }
}
