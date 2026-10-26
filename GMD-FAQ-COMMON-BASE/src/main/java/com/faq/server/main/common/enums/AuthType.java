package com.gmd.faq.server.main.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 认证类型
 *
 */
@Getter
@AllArgsConstructor
public enum AuthType {
    CONFIG("直接配置"),
    ENV("环境变量");

    private final String description;
}
