package com.gmd.faq.server.main.common.r;

import java.io.Serializable;

/**
 * 描述：响应code接口
 *
 */
public interface IResultCode extends Serializable {
    String getMessage();

    int getCode();
}
