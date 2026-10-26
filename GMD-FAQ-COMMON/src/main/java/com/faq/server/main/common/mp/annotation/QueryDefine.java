package com.gmd.faq.server.main.common.mp.annotation;

import com.gmd.faq.server.main.common.mp.support.QueryCondition;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * 描述：查询定义
 *
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface QueryDefine {
    String value() default "描述";
    QueryCondition condition();
}
