package com.gmd.faq.server.main.common.wrapper;

import com.gmd.faq.server.main.common.enums.ModelProviderType;
import com.gmd.faq.server.main.common.enums.ModelApiType;
import com.gmd.faq.server.main.common.enums.ToolChoiceStrategy;
import lombok.*;

import java.util.Map;

/**
 * 描述：模型配置包装类
 *
 **/
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelConfigWrapper {
    /**
     * 模型提供商
     */
    private ModelProviderType provider;
    /**
     * 基础路径
     */
    private String baseUrl;

    /**
     * 模型编号
     */
    private String modelCode;
    private ModelApiType apiType;
    private Boolean responsesSampling;
    private com.fasterxml.jackson.databind.JsonNode reasoning;
    private String reasoningProfile;
    private com.gmd.faq.server.main.common.model.ReasoningSettings resolvedReasoning;
    private Integer reasoningBudget;

    /**
     * 模型 API Key
     */
    private String apiKey;

    /**
     * 是否支持思考
     */
    private Boolean thinking;
    /**
     * 是否支持流式
     */
    private Boolean streaming;

    /**
     * 上下文窗口大小
     */
    private Integer contextWindow;

    /**
     * 最大输出token数
     */
    private Integer maxTokens;

    /**
     * 温度参数
     */
    private Double temperature;

    /**
     * 核采样参数
     */
    private Double topP;

    /**
     * Top-K采样
     */
    private Integer topK;

    /**
     * 重复惩罚
     */
    private Double repeatPenalty;

    /**
     * 随机种子
     */
    private Long seed;

    /**
     * 是否是多智能体协同
     */
    boolean multi;

    /**
     * 工具选择策略
     */
    private ToolChoiceStrategy toolChoiceStrategy;

    /**
     * 指定工具名称
     */
    private String specificToolName;

    /**
     * 请求头
     */
    private Map<String, String> headers;
    /**
     * 请求参数
     */
    private Map<String, String> queryParams;
    /**
     * body参数
     */
    private Map<String, Object> bodyParams;

    /**
     * 固定系统提示词
     * 确保 system 消息始终在消息列表的最前面，以兼容 SGLang 等严格部署环境
     */
    private Boolean fixedSystemMessage;

    @Override
    public String toString() {
        return "ModelConfigWrapper{" +
                "provider=" + provider +
                ", baseUrl='" + baseUrl + '\'' +
                ", modelCode='" + modelCode + '\'' +
                ", apiType=" + apiType +
                ", thinking=" + thinking +
                ", streaming=" + streaming +
                ", contextWindow=" + contextWindow +
                ", maxTokens=" + maxTokens +
                ", temperature=" + temperature +
                ", topP=" + topP +
                ", topK=" + topK +
                ", repeatPenalty=" + repeatPenalty +
                ", seed=" + seed +
                ", multi=" + multi +
                ", toolChoiceStrategy=" + toolChoiceStrategy +
                ", specificToolName='" + specificToolName + '\'' +
                ", responsesSampling=" + responsesSampling +
                ", fixedSystemMessage=" + fixedSystemMessage +
                '}';
    }
}
