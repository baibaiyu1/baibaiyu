package com.gmd.faq.server.main.engine.skill;

import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.entity.*;
import com.gmd.faq.server.main.common.enums.SkillFileType;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.engine.agent.AgentExecutionRole;
import com.gmd.faq.server.main.engine.skill.builtins.UserInteractionProtocolSkill;
import com.gmd.faq.server.main.engine.skill.builtins.VisionEnhancementProtocolSkill;
import com.gmd.faq.server.main.engine.tool.ToolkitFactory;
import com.gmd.faq.server.main.engine.workspace.skills.SearchReplaceSkill;
import com.gmd.faq.server.main.engine.workspace.skills.WorkspaceSkill;
import com.gmd.faq.server.main.skill.service.AgentSkillPackageService;
import com.gmd.faq.server.main.skill.service.SkillFileService;
import com.gmd.faq.server.main.skill.service.SkillPackageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.gmd.faq.server.main.skill.service.SkillToolService;
import com.gmd.faq.server.main.tool.service.ToolService;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.coding.ShellCommandTool;
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.core.tool.file.WriteFileTool;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 描述：skill 构造器
 *
 **/
@Component
@RequiredArgsConstructor
public class SkillBoxFactory {
    private final SkillPackageService skillPackageService;
    private final SkillFileService skillFileService;
    private final AgentSkillPackageService agentSkillPackageService;
    private final SkillToolService skillToolService;
    private final ToolService toolService;
    private final com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService resourceVisibilityService;

    /**
     * 获取SkillBox
     *
     * @param agentDefinition 智能体定义
     * @param codeExecutionConfig   代码执行配置
     * @return SkillBox
     */
    public SkillBox getSkillBox(AgentDefinition agentDefinition, CodeExecutionConfig codeExecutionConfig) {
        return getSkillBox(agentDefinition, new Toolkit(), codeExecutionConfig, AgentExecutionRole.ROOT);
    }

    public SkillBox getSkillBox(
            AgentDefinition agentDefinition,
            CodeExecutionConfig codeExecutionConfig,
            AgentExecutionRole executionRole) {
        return getSkillBox(agentDefinition, new Toolkit(), codeExecutionConfig, executionRole);
    }

    /**
     * 根据技能包ID列表构建SkillBox。
     *
     * @param skillPackageIds 技能包ID列表
     * @param toolkit         工具箱
     * @return SkillBox
     */
    public SkillBox getSkillBox(List<Long> skillPackageIds, Toolkit toolkit) {
        Toolkit currentToolkit = toolkit == null ? new Toolkit() : toolkit;
        SkillBox skillBox = new SkillBox(currentToolkit);

        // 注册智能体基础技能，不启用代码执行能力。
        skillBox.registerSkill(UserInteractionProtocolSkill.getAgentSkill());
        skillBox.registerSkill(VisionEnhancementProtocolSkill.getAgentSkill());

        if (skillPackageIds == null || skillPackageIds.isEmpty()) {
            return skillBox;
        }

        registerSkills(skillBox, skillPackageIds, currentToolkit, AgentExecutionRole.ROOT);
        return skillBox;
    }

    /**
     * 获取SkillBox
     *
     * @param agentDefinition 智能体定义
     * @param codeExecutionConfig   代码执行配置
     * @return SkillBox
     */
    public SkillBox getSkillBox(AgentDefinition agentDefinition, Toolkit toolkit, CodeExecutionConfig codeExecutionConfig) {
        return getSkillBox(agentDefinition, toolkit, codeExecutionConfig, AgentExecutionRole.ROOT);
    }

    /**
     * UIP is deliberately unavailable to delegated agents: it requires a browser round-trip
     * that can only resume the root chat session in the current runtime.
     */
    public SkillBox getSkillBox(
            AgentDefinition agentDefinition,
            Toolkit toolkit,
            CodeExecutionConfig codeExecutionConfig,
            AgentExecutionRole executionRole) {
        SkillBox skillBox = new SkillBox(toolkit);

        if (!executionRole.isSubAgent()) {
            skillBox.registerSkill(UserInteractionProtocolSkill.getAgentSkill());
            skillBox.registerSkill(VisionEnhancementProtocolSkill.getAgentSkill());
        }

        configureCodeExecution(skillBox, toolkit, codeExecutionConfig);

        // 注册技能包
        List<Long> skillPackageIds = agentSkillPackageService.getSkillPackageIds(agentDefinition.getId());
        if (skillPackageIds.isEmpty()) {
            return skillBox;
        }

        registerSkills(skillBox, skillPackageIds, toolkit, executionRole);

        return skillBox;
    }

    /** 与旧加载路径共用绑定、文件和工具构造，只将结果交给只读仓库，不挂载旧 SkillHook。 */
    public ReadOnlySkillRepository getRepository(AgentDefinition definition, Toolkit toolkit,
            CodeExecutionConfig config, AgentExecutionRole role) {
        SkillBox snapshot = getSkillBox(definition, toolkit, config, role);
        List<AgentSkill> skills = snapshot.getAllSkillIds().stream().sorted().map(snapshot::getSkill).toList();
        return new ReadOnlySkillRepository(definition.getTenantId(), definition.getId(), skills);
    }

    /**
     * 注册技能包到SkillBox
     *
     * @param skillBox SkillBox
     * @param skillPackageIds 技能包ID列表
     * @param toolkit    工具包
     */
    private void registerSkills(SkillBox skillBox, List<Long> skillPackageIds, Toolkit toolkit, AgentExecutionRole role) {
        List<SkillPackage> skillPackages = skillPackageService.listByIds(skillPackageIds);

        skillPackages.stream()
                .filter(SkillPackage::getEnabled)
                .forEach(skillPackage -> registerSkill(skillBox, skillPackage, toolkit, role));
    }

    /**
     * 注册单个技能包
     *
     * @param skillBox SkillBox
     * @param skillPackage 技能包
     * @param toolkit    工具包
     */
    private void registerSkill(SkillBox skillBox, SkillPackage skillPackage, Toolkit toolkit, AgentExecutionRole role) {
        // 查询技能包的所有入库文件
        List<SkillFile> files = skillFileService.listBySkillId(skillPackage.getId());
        // 注册技能包中的工具
        List<Long> toolIds = skillToolService.getToolIds(skillPackage.getId());
        List<ToolConfig> toolConfigs = null;
        if (!toolIds.isEmpty()) {
            // 共享智能体沿用技能维护方配置的依赖，不要求使用者单独获授每个工具。
            // 对存量关联再次校验，避免旧的越权绑定继续进入运行工具集。
            Long accountId = "PERSONAL".equals(skillPackage.getOwnershipType()) ? skillPackage.getOwnerId()
                    : skillPackage.getMaintainerId() != null ? skillPackage.getMaintainerId() : skillPackage.getCreatedBy();
            toolIds.forEach(toolId -> resourceVisibilityService.assertUsableForAccount("TOOL", toolId, accountId));
            toolConfigs = toolService.listByIds(toolIds);
            ToolkitFactory.registerTools(toolkit, toolConfigs, role);
        }

        // 查找 SKILL.md 文件
        String skillContent = files.stream()
                .filter(f -> f.getFileType() == SkillFileType.SKILL_MD)
                .map(SkillFile::getContent)
                .findFirst()
                .orElse("");

        List<SkillFile> resourceFiles = files.stream()
                .filter(f -> f.getFileType() != SkillFileType.SKILL_MD)
                .toList();

        AgentSkill baseSkill = AgentSkill.builder()
                .name(skillPackage.getName())
                .description(skillPackage.getDescription())
                .skillContent((skillContent))
                .source(SysConst.SKILL_SOURCE)
                .build();

        AgentSkill.Builder skillBuilder = baseSkill.toBuilder()
                .skillContent(
                        addToolInfoToContent(
                                appendResourceUsageHint(baseSkill, resourceFiles),
                                toolConfigs))
                .source(SysConst.SKILL_SOURCE);

        // 添加所有资源引用（references/examples/scripts 类型的文件）
        files.stream()
                .filter(f -> f.getFileType() != SkillFileType.SKILL_MD)
                .forEach(f -> skillBuilder.addResource(f.getFilePath(), f.getContent()));

        skillBox.registration().skill(skillBuilder.build()).apply();
    }

    /**
     * 添加资源使用提示
     *
     * @param baseSkill     基础技能
     * @param resourceFiles 资源文件列表
     * @return 添加资源使用提示后的技能内容
     */
    private String appendResourceUsageHint(AgentSkill baseSkill, List<SkillFile> resourceFiles) {
        if (resourceFiles == null || resourceFiles.isEmpty()) {
            return baseSkill.getSkillContent();
        }

        List<String> resourcePaths = resourceFiles.stream()
                .filter(f -> f.getFileType() != SkillFileType.SKILL_MD)
                .map(SkillFile::getFilePath)
                .filter(path -> path != null && !path.isBlank())
                .map(path -> path.replace('\\', '/'))
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        if (resourcePaths.isEmpty()) {
            return baseSkill.getSkillContent();
        }

        String skillContent = baseSkill.getSkillContent();
        String resourceList = resourcePaths.stream()
                .map(path -> "- `" + path + "`")
                .collect(Collectors.joining("\n"));

        String hint = """
            
            ================ Skill Resources Explanation ==================
            
            When this skill refers to files in this directory, examples/, references/, or scripts/,
            treat them as skill resources, not workspace files. Load them with:
            
            `load_skill_through_path(skillId="%s", path="<resource-path>")`
            
            Available resource paths:
            %s""".formatted(baseSkill.getSkillId(), resourceList);

        return (skillContent == null ? "" : skillContent) + hint;
    }

    /**
     * 添加工具信息到技能内容
     *
     * @param content       技能内容
     * @param toolConfigs   工具配置列表
     * @return 添加工具信息后的技能内容
     */
    private String addToolInfoToContent(String content, List<ToolConfig> toolConfigs) {
        if (toolConfigs == null || toolConfigs.isEmpty()) {
            return content;
        }

        String toolInfo = toolConfigs.stream()
                .map(tool -> "toolName：" + tool.getToolId() + "\ntoolDesc: " + tool.getDescription())
                .collect(Collectors.joining("\n---\n"));

        return content + "\n\n================ Available Tools (You can use the following tools) ==============\n" + toolInfo;
    }

    /**
     * 配置代码执行环境
     *
     * @param skillBox SkillBox
     * @param config   代码执行配置
     */
    private void configureCodeExecution(SkillBox skillBox, Toolkit toolkit, CodeExecutionConfig config) {
        if (skillBox == null || config == null || !Boolean.TRUE.equals(config.getEnabled())) {
            return;
        }

        // 配置工作空间专属skill
        skillBox.registerSkill(WorkspaceSkill.getAgentSkill());

        // 设置自动上传
        skillBox.setAutoUploadSkill(false);

        // 配置代码执行环境
        Path workDir = Path.of(SysConst.getWorkspacePath(), AgentContext.get().getThreadId());
        skillBox.setWorkDir(workDir);
        skillBox.getSkillPromptProvider().setCodeExecutionEnable(true);

        // 配置Shell命令工具
        if (Boolean.TRUE.equals(config.getEnableShell())) {
            Set<String> allowedCommands = parseAllowedCommands(config.getCommand());
            toolkit.registerTool(new ShellCommandTool(workDir.toString(), allowedCommands, null));
        }

        // 配置文件读写工具
        if (Boolean.TRUE.equals(config.getEnableRead())) {
            toolkit.registerTool(new ReadFileTool(workDir.toString()));
            if (toolkit instanceof com.gmd.faq.server.main.engine.tool.ProjectToolkit project)
                project.trustReadTools("view_text_file", "list_directory");
        }
        if (Boolean.TRUE.equals(config.getEnableWrite())) {
            toolkit.registerTool(new WriteFileTool(workDir.toString()));
            // 配置工作空间专属skill
            skillBox.registerSkill(SearchReplaceSkill.getAgentSkill());
        }

    }

    /**
     * 解析允许执行的命令集合
     *
     * @param commandJson 命令JSON节点
     * @return 允许执行的命令集合
     */
    private Set<String> parseAllowedCommands(JsonNode commandJson) {
        Set<String> commands = new HashSet<>();
        if (commandJson == null || commandJson.isEmpty()) {
            return commands;
        }
        if (commandJson.isArray()) {
            commandJson.forEach(node -> commands.add(node.asText()));
        }
        return commands;
    }
}
