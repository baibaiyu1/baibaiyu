package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode
public class SkillsHubVO implements SerializableEnable {
    private String category;
    private String slug;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String version;
    private String name;
    private String downloads;
    private String homepage;
    private String iconUrl;
    private String requiresApiKey;

}
