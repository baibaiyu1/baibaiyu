package com.gmd.faq.server.main.engine.skill;

import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.repository.RuntimeContextSkillRepository;
import java.util.List;
import java.util.Objects;

/** 仅包含智能体已绑定且启用的依赖；绑定使用权继续由资源服务校验，不开放通用技能检索。 */
public final class ReadOnlySkillRepository implements RuntimeContextSkillRepository {
    private final Long tenantId;
    private final Long agentId;
    private final List<AgentSkill> skills;

    public ReadOnlySkillRepository(Long tenantId, Long agentId, List<AgentSkill> skills) {
        this.tenantId = Objects.requireNonNull(tenantId);
        this.agentId = Objects.requireNonNull(agentId);
        this.skills = List.copyOf(skills);
    }

    @Override public List<AgentSkill> getAllSkills(RuntimeContext context) {
        AgentContext owner = context == null ? null : context.get(AgentContext.class);
        return scoped(owner);
    }

    @Override public List<AgentSkill> getAllSkills() {
        return scoped(AgentContext.getIfExists().orElse(null));
    }

    private List<AgentSkill> scoped(AgentContext owner) {
        if (owner == null || !tenantId.equals(owner.getTenantId()) || owner.getAgentDefinition() == null
                || !agentId.equals(owner.getAgentDefinition().getId())) {
            throw new IllegalStateException("技能仓库与当前智能体上下文不匹配");
        }
        return skills;
    }

    @Override public AgentSkill getSkill(String name) {
        return getAllSkills().stream().filter(skill -> skill.getName().equals(name)).findFirst().orElse(null);
    }
    @Override public List<String> getAllSkillNames() { return getAllSkills().stream().map(AgentSkill::getName).toList(); }
    @Override public boolean skillExists(String name) { return getSkill(name) != null; }
    @Override public String getSource() { return SysConst.SKILL_SOURCE; }
    @Override public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("database", "agent:" + agentId, false);
    }
    @Override public boolean isWriteable() { return false; }
    @Override public boolean save(List<AgentSkill> skills, boolean overwrite) { throw readOnly(); }
    @Override public boolean delete(String name) { throw readOnly(); }
    @Override public void setWriteable(boolean writeable) { if (writeable) throw readOnly(); }
    private UnsupportedOperationException readOnly() { return new UnsupportedOperationException("运行时技能仓库只读"); }
}
