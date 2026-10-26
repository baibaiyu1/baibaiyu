package com.gmd.faq.server.main.common.key;

/**
 * 描述：mcp stdIO 协议 key
 *
 **/
public record McpStdIOKey() {
    public static String name = "name";
    public static String command = "command";
    public static String args ="args";
    public static String env ="env";
    public static String pattern="pattern";
}
