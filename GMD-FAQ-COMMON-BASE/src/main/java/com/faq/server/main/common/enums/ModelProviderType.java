package com.gmd.faq.server.main.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 描述：模型提供商类型
 *
 **/
@Getter
@AllArgsConstructor
public enum ModelProviderType {
    DASH_SCOPE("DashScope"),
    OPEN_AI("OpenAI"),
    ANTHROPIC("Anthropic"),
    GEMINI("Gemini"),
    OLLAMA("Ollama"),
    ORCA_ROUTER("OrcaRouter");

    private final String description;
}
