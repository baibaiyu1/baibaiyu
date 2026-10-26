package com.gmd.faq.server.main.common.vo;

import lombok.Data;

import java.util.List;

/**
 * OpenAPI 解析预览 VO
 *
 */
@Data
public class OpenApiPreviewVO {

    /**
     * 文档标题
     */
    private String title;

    /**
     * 文档版本
     */
    private String version;

    /**
     * 文档声明的 OpenAPI / Swagger 版本
     */
    private String specVersion;

    /**
     * 文档中的服务地址列表
     */
    private List<String> servers;

    /**
     * 实际使用的基地址
     */
    private String baseUrl;

    /**
     * 解析出的接口操作列表
     */
    private List<OpenApiOperationVO> operations;
}
