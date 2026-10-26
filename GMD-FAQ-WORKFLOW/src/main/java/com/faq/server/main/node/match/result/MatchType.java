package com.gmd.faq.server.main.node.match.result;

/**
 * 描述：匹配类型枚举
 *
 **/
public enum MatchType {
    EQUALS, // 等于，支持的类型：String, Number, Boolean
    CONTAINS, // 包含，支持的类型：String, Array
}
