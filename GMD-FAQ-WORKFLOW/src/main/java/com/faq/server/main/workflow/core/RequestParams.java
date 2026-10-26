package com.gmd.faq.server.main.workflow.core;

import com.gmd.faq.server.main.node.start.Param;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 描述：请求参数
 *
 **/
@Getter
@Setter
public class RequestParams {
    private List<Param> params;
    private byte[] body;
}
