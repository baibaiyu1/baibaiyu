package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.subagent.SubAgentTraceEventType;
import com.gmd.faq.server.main.common.config.SerializableEnable;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SubAgentTraceEventVO implements SerializableEnable {
    private String eventId;
    private String invocationId;
    private Long sequence;
    private SubAgentTraceEventType eventType;
    private Map<String, Object> payload;
    private LocalDateTime occurredAt;
}
