package com.gmd.faq.server.main.scheduler.core.annotation;

import java.lang.annotation.*;

/**
 * 描述：创建定时
 *
 **/
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface QuartzCreate {
}
