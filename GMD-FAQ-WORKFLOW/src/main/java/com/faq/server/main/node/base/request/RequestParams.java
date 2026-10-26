package com.gmd.faq.server.main.node.base.request;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 描述：请求参数
 *
 **/
@Getter
@Setter
@Builder
public class RequestParams {
    private List<ParamItem> params;
}
