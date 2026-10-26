package com.gmd.faq.server.main.engine.model;

import com.gmd.faq.server.main.common.enums.ModelProviderType;
import com.gmd.faq.server.main.common.wrapper.ModelConfigWrapper;
import io.agentscope.core.model.Model;

/**
 * 描述：聊天模型
 *
 **/
public interface IChatModel {
    Model getModel(ModelConfigWrapper modelConfig);
    Model getSimpleModel(ModelConfigWrapper modelConfig);
    ModelProviderType getProvider();
    int order();
}
