package com.gmd.faq.server.main.node.base.security;

/**
 * 描述：代码安全检查接口
 *
 **/
public interface SecurityChecker {
    SecurityCheckResult checkCodeSecurity(String javaCode);
}
