package com.gmd.faq.server.main.dashboard.dataset.guard;

/**
 * 描述：数据集安全校验异常
 *
 **/
public class DatasetSecurityException extends RuntimeException {
    public DatasetSecurityException(String message) {
        super(message);
    }
}
