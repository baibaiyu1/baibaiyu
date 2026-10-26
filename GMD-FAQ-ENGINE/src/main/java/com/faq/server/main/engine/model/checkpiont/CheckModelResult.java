package com.gmd.faq.server.main.engine.model.checkpiont;

/**
 * 描述：模型检查结果
 *
 **/
public class CheckModelResult {
    public Boolean success;
    public String message;

    public CheckModelResult() {}

    public CheckModelResult(Boolean success, String message) {
        this.success = success;
        this.message = message;
    }
}
