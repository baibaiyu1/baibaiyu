package com.gmd.faq.server.main.params.core;

import org.springframework.beans.factory.InitializingBean;

/**
 * 描述：
 *
 **/
public interface ParamsCore extends InitializingBean {
    String checkAndFormatValue(String value);
    String getDefaultValue();

    void register(ParamsAdapter adapter);

    @Override
    default void afterPropertiesSet() {
        register(new ParamsAdapter(null));
    }
}
