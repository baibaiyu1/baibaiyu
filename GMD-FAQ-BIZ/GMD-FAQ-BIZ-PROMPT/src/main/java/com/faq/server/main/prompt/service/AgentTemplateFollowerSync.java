package com.gmd.faq.server.main.prompt.service;

import java.util.List;

/** Synchronizes template content changes and detaches agents when a template is deleted. */
public interface AgentTemplateFollowerSync {
    void syncFollowers(Long templateId, Long tenantId, String content);

    void detachDeletedTemplates(List<Long> templateIds, Long tenantId);
}
