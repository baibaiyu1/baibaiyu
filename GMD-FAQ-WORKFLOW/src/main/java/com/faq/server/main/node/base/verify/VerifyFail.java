package com.gmd.faq.server.main.node.base.verify;


import lombok.Getter;
import lombok.Setter;

/**
 * 描述：检验错误
 *
 **/
@Getter
@Setter
public class VerifyFail {
    /**
     * 错误信息
     **/
    private String message;
    /**
     * 错误字段
     **/
    private String field;

    public VerifyFail(String field, String message) {
        this.field = field;
        this.message = message;
    }
}
