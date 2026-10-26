package com.gmd.faq.server.main.scheduler.core.enable;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 描述：是否启用标记
 *
 **/
@Component
@ConditionalOnProperty(name="gmd.faq.quartz.enabled", havingValue = "true", matchIfMissing = true)
public class QuartzEnabled {
}
