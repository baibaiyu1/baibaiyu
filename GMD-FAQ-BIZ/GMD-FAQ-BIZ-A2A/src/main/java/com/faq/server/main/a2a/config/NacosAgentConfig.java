package com.gmd.faq.server.main.a2a.config;

import com.gmd.faq.server.main.common.KvMap;
import com.gmd.faq.server.main.common.util.FuncUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Properties;

/**
 * 描述：
 *
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
public class NacosAgentConfig {
    private String agentName;
    private List<KvMap> nacosProperties;

    public Properties getNacosProperties() {
        Properties properties = new Properties();

        nacosProperties.forEach(kvMap -> {
            String value = kvMap.isEvn() ? System.getenv(kvMap.getValue()) : kvMap.getValue();
            if (!FuncUtils.isEmpty(value)) {
                properties.put(kvMap.getKey(), value);
            }
        });

        return properties;
    }
}
