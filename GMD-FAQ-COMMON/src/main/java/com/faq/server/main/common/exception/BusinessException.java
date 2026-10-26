package com.gmd.faq.server.main.common.exception;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 业务异常类
 *
 */
@Data
@EqualsAndHashCode
public class BusinessException extends RuntimeException {
    private Integer code;

    public BusinessException(String message) {
        super(message);
        this.code = 400;
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(Integer code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
