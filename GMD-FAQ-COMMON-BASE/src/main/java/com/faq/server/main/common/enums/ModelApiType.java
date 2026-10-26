package com.gmd.faq.server.main.common.enums;

/** OpenAI 兼容供应商的请求协议，未配置的存量模型继续使用 Chat Completions。 */
public enum ModelApiType {
    CHAT_COMPLETIONS,
    RESPONSES
}
