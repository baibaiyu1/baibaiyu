package com.gmd.faq.server.main.engine.skill;

import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.DynamicSkillMiddleware;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.util.List;
import reactor.core.publisher.Mono;

/** 使用 2.0 的按运行资源加载工具；权限失败终止加载，禁止回退为全部可见或自动上传文件。 */
@SuppressWarnings("deprecation")
public final class ReadOnlySkillMiddleware extends DynamicSkillMiddleware {
    private final ReadOnlySkillRepository repository;
    private final boolean codeExecution;

    public ReadOnlySkillMiddleware(ReadOnlySkillRepository repository, Toolkit toolkit, boolean codeExecution) {
        super(List.of(repository), toolkit);
        this.repository = repository;
        this.codeExecution = codeExecution;
        if (toolkit instanceof com.gmd.faq.server.main.engine.tool.ProjectToolkit project
                && toolkit.getTool("load_skill_through_path") != null
                && toolkit.getTool("load_skill_through_path").getClass().getName().startsWith("io.agentscope.core.skill.SkillToolFactory$"))
            project.trustReadTools("load_skill_through_path");
    }

    @Override public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        return Mono.fromSupplier(() -> {
            // SDK 默认实现会吞掉仓库/过滤异常并自动上传。这里每轮构建独立视图，不缓存跨账号状态。
            context.put(SkillBox.class, null);
            var skills = repository.getAllSkills(context);
            SkillBox box = new SkillBox(null);
            box.setAutoUploadSkill(false);
            box.getSkillPromptProvider().setCodeExecutionEnable(codeExecution);
            if (codeExecution) {
                AgentContext owner = context.get(AgentContext.class);
                box.setWorkDir(Path.of(SysConst.getWorkspacePath(), owner.getThreadId()));
            }
            skills.forEach(box::registerSkill);
            context.put(SkillBox.class, box);
            SkillFilter filter = context.get(SkillFilter.class);
            String skillPrompt = box.getSkillPrompt(filter == null ? SkillFilter.all() : filter);
            String base = prompt == null ? "" : prompt;
            return skillPrompt == null || skillPrompt.isEmpty() ? base
                    : base + (base.isEmpty() || base.endsWith("\n") ? "" : "\n") + skillPrompt;
        });
    }
}
