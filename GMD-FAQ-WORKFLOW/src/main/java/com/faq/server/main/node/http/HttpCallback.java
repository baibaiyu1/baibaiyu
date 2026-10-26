package com.gmd.faq.server.main.node.http;

/**
 * 描述：回调接口
 *
 **/
public interface HttpCallback {
    void onSuccess(HttpResponse response);
    void onFailure(HttpResponse response);
}
