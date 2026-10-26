package com.gmd.faq.server.main.engine.security.script;

/**
 * 描述：脚本安全检查过程中抛出的异常
 *
 */
public class ScriptSecurityException extends RuntimeException {

    public ScriptSecurityException(String message) {
        super(message);
    }

    public ScriptSecurityException(String message, Throwable cause) {
        super(message, cause);
    }

}
