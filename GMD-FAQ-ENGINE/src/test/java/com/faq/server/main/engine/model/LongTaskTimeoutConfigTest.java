package com.gmd.faq.server.main.engine.model;

import com.gmd.faq.server.main.engine.tool.CustomToolkitConfig;
import io.agentscope.core.model.transport.OkHttpTransport;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LongTaskTimeoutConfigTest {

    @Test
    void chatReadTimeoutHasLongDefaultAndAcceptsConfigurationOverride() {
        ChatModelTransportConfig config = new ChatModelTransportConfig();
        assertReadTimeout(config, Duration.ofSeconds(600));

        MockEnvironment environment = new MockEnvironment()
                .withProperty("gmd.faq.model.chat-read-timeout-seconds", "900");
        Binder.get(environment).bind("gmd.faq.model", Bindable.ofInstance(config));
        assertReadTimeout(config, Duration.ofSeconds(900));
        assertThrows(IllegalArgumentException.class, () -> config.setChatReadTimeoutSeconds(0));
    }

    @Test
    void toolkitTimeoutOutlastsDefaultShellCommandAndAcceptsOverride() {
        CustomToolkitConfig config = new CustomToolkitConfig();
        assertEquals(Duration.ofSeconds(360), config.toExecutionConfig().getTimeout());

        MockEnvironment environment = new MockEnvironment()
                .withProperty("gmd.faq.tool.execution-timeout-seconds", "720");
        Binder.get(environment).bind("gmd.faq.tool", Bindable.ofInstance(config));
        assertEquals(Duration.ofSeconds(720), config.toExecutionConfig().getTimeout());
    }

    private void assertReadTimeout(ChatModelTransportConfig config, Duration expected) {
        OkHttpTransport transport = (OkHttpTransport) config.createChatTransport();
        try {
            assertEquals(expected, transport.getConfig().getReadTimeout());
        } finally {
            transport.close();
        }
    }
}
