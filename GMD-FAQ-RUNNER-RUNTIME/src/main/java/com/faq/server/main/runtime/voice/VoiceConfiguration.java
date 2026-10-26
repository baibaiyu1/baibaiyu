package com.gmd.faq.server.main.runtime.voice;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** LiveKit 连接参数；模型密钥仍由既有模型配置管理。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "gmd.voice")
public class VoiceConfiguration {
    private String serverUrl = "ws://127.0.0.1:7880";
    private String apiKey = "";
    private String apiSecret = "";
    private String agentName = "gmd-faq-voice";

    public boolean available() {
        return !apiKey.isBlank() && apiSecret.length() >= 32;
    }
}
