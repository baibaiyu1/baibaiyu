package com.gmd.faq.server.main.common;

import lombok.Data;

/**
 * 描述：KvMap
 *
 **/
@Data
public class KvMap {
    public String key;
    public String value;
    // 如果是evn是true,则从evn中获取value,value就是evn的key
    public boolean evn;
}
