package com.gmd.faq.server.main.common.key;

/**
 * 描述：mcp http 协议 key
 *
 **/
public record McpHttpKey() {
    public static String name = "name";
    public static String url = "url";
    public static String headers = "headers";
    public static String queryParams = "queryParams";
    public static String timeout = "timeout";
    public static String pattern = "pattern";
}
