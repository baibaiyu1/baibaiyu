package com.gmd.faq.server.main.node.base.security;

import com.gmd.faq.server.main.node.base.CodeLanguage;

import java.util.Objects;

/**
 * 描述： 代码安全检查工厂
 *
 **/
public class SecurityCheckerFactory {
    private static final SecurityChecker4Java securityChecker4Java = new SecurityChecker4Java();

    public static SecurityChecker getChecker(CodeLanguage language) {
        if (Objects.requireNonNull(language) == CodeLanguage.JAVA) {
            return securityChecker4Java;
        }
        throw new IllegalArgumentException("不支持的语言");
    }
}
