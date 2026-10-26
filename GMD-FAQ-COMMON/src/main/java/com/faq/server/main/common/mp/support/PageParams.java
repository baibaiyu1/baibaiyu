package com.gmd.faq.server.main.common.mp.support;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 描述：分页参数
 *
 */
@Setter
@Getter
public class PageParams implements SerializableEnable {
    private Integer page;
    private Integer size;

    private List<String> asc;
    private List<String> desc;
}
