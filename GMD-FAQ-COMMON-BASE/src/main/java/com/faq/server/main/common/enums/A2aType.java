package com.gmd.faq.server.main.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 描述：A2aType
 *
 **/
@Getter
@AllArgsConstructor
public enum A2aType {
    WELLKNOWN("wellknown"),
    NACOS("nacos");

    private final String description;
}
