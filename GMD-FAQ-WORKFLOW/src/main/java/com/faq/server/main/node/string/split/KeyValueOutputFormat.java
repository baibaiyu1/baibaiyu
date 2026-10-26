package com.gmd.faq.server.main.node.string.split;

/**
 * 描述：键值输出格式
 *
 **/
public enum KeyValueOutputFormat {
    COLON_SEPARATED,    // 返回的格式 key: value
    EQUALS_SEPARATED,   // 返回的格式 key=value
    JSON_OBJECT,        // 返回的格式 {"key": "value"}
    MAP_ENTRY,          // 返回的格式 key -> value
    CUSTOM              // 自定义格式, 要求格式 "%s===>%s"
}
