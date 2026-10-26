package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 技能包VO
 *
 */
@Data
@EqualsAndHashCode
public class SkillPackageVO implements SerializableEnable {
    private Long id;
    private String name;
    private String alias;
    private String description;
    private String category;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
    private List<Object> used;
    private List<Long> tools;
}
