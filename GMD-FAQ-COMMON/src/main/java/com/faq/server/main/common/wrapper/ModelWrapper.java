package com.gmd.faq.server.main.common.wrapper;

import com.gmd.faq.server.main.common.entity.ModelConfig;
import com.gmd.faq.server.main.common.entity.ModelProvider;
import lombok.*;

/**
 * 描述：模型包装类
 *
 **/
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelWrapper {
    private ModelConfig config;
    private ModelProvider provider;
}
