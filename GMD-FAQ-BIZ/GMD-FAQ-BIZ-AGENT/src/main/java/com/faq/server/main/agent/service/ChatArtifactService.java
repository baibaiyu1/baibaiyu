package com.gmd.faq.server.main.agent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gmd.faq.server.main.common.entity.ChatArtifact;

import java.util.List;

/**
 * 描述：对话产物 Service
 *
 **/
public interface ChatArtifactService extends IService<ChatArtifact> {

    /**
     * 记录一个产物（同一会话内按 filePath 去重，重复产出做更新）
     *
     * @param artifact 产物
     */
    void recordArtifact(ChatArtifact artifact);

    /**
     * 查询某会话的全部产物，按写入时间升序
     *
     * @param sessionId 会话 ID
     * @return 产物列表
     */
    List<ChatArtifact> listBySession(Long sessionId);
}
