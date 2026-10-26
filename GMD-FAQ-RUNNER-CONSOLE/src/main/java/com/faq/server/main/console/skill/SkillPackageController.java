package com.gmd.faq.server.main.console.skill;

import cn.hutool.core.io.FileUtil;
import com.gmd.faq.server.main.agent.service.SessionAccessGuard;
import com.gmd.faq.server.main.common.exception.BusinessException;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.dto.SkillPackageDTO;
import com.gmd.faq.server.main.common.entity.SkillFile;
import com.gmd.faq.server.main.common.entity.SkillPackage;
import com.gmd.faq.server.main.common.enums.SkillFileType;
import com.gmd.faq.server.main.common.mp.support.MP;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.r.ResultCode;
import com.gmd.faq.server.main.common.util.BeanUtils;
import com.gmd.faq.server.main.common.util.ZipExtractUtils;
import com.gmd.faq.server.main.common.vo.SkillImportResult;
import com.gmd.faq.server.main.common.vo.SkillPackageVO;
import com.gmd.faq.server.main.skill.SkillFileSystemService;
import com.gmd.faq.server.main.skill.imports.SkillImportPathResolver;
import com.gmd.faq.server.main.skill.imports.SkillImportService;
import com.gmd.faq.server.main.skill.imports.SkillInstaller;
import com.gmd.faq.server.main.skill.imports.config.GitImportConfig;
import com.gmd.faq.server.main.skill.imports.config.LocalImportConfig;
import com.gmd.faq.server.main.skill.imports.config.UploadImportConfig;
import com.gmd.faq.server.main.skill.service.SkillFileService;
import com.gmd.faq.server.main.skill.service.SkillPackageService;
import com.gmd.faq.server.main.skill.service.SkillToolService;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 技能包Controller
 *
 */
@RestController
@RequestMapping("/skill")
@RequiredArgsConstructor
public class SkillPackageController {

    private final SkillImportService skillImportService;
    private final SkillPackageService skillPackageService;
    private final SkillToolService skillToolService;
    private final SkillFileService skillFileService;
    private final ResourceVisibilityService resourceVisibilityService;
    private final SessionAccessGuard sessionAccessGuard;

    /**
     * 分页查询
     */
    @GetMapping("/page")
    @PermNeed({"skill:package:list"})
    public R<IPage<SkillPackageVO>> page(PageParams pageParams, SkillPackageDTO query) {
        QueryWrapper<SkillPackage> wrapper = MP.getQueryWrapper(query);
        resourceVisibilityService.applyScope(wrapper, "SKILL", query.getScope(), query.getSpace());
        IPage<SkillPackage> page = skillPackageService.page(MP.getPage(pageParams), wrapper);
        IPage<SkillPackageVO> voPage = BeanUtils.copyPage(page, SkillPackageVO.class);
        // 填充每个技能关联的工具ID列表
        for (SkillPackageVO vo : voPage.getRecords()) {
            vo.setTools(skillToolService.getToolIds(vo.getId()));
        }
        return R.data(voPage);
    }

    /**
     * 详情
     */
    @GetMapping("/{id}")
    @PermNeed({"skill:package:list"})
    public R<SkillPackageVO> detail(@PathVariable("id") Long id) {
        resourceVisibilityService.assertViewable("SKILL", id);
        SkillPackageVO vo = skillPackageService.getDetail(id);
        if (vo == null) {
            return R.fail(ResultCode.NOT_FOUND, "技能包不存在");
        }
        vo.setUsed(skillPackageService.usedWithAgent(List.of(id)));
        return R.data(vo);
    }

    /**
     * 新增
     */
    @PostMapping
    @PermNeed({"skill:package:add"})
    public R<Long> save(@RequestBody SkillPackageVO vo) {
        SkillPackage entity = BeanUtils.copy(vo, SkillPackage.class);
        skillPackageService.save(entity);
        // 保存技能与工具的关联
        if (vo.getTools() != null && !vo.getTools().isEmpty()) {
            skillToolService.saveSkillTool(entity.getId(), vo.getTools());
        }
        // 创建技能包目录并写入默认 SKILL.md（带 YAML 头）
        SkillFileSystemService.buildSkillDir(entity.getName());
        String initialContent = SkillFileSystemService.buildSkillMdContent(entity.getName(), entity.getDescription());
        SkillFileSystemService.writeFile(entity.getName(), "SKILL.md", initialContent);
        // 写入 SKILL.md 文件记录
        SkillFile skillFile = new SkillFile();
        skillFile.setSkillId(entity.getId());
        skillFile.setFileType(SkillFileType.SKILL_MD);
        skillFile.setFileName("SKILL.md");
        skillFile.setFilePath("SKILL.md");
        skillFile.setContent(initialContent);
        skillFile.setSort(0);
        skillFileService.save(skillFile);
        return R.data(entity.getId());
    }

    /**
     * 修改
     */
    @PutMapping
    @PermNeed({"skill:package:edit"})
    public R<Boolean> update(@RequestBody SkillPackageVO vo) {
        SkillPackage entity = BeanUtils.copy(vo, SkillPackage.class);
        boolean b = skillPackageService.doUpdate(entity);
        // 更新技能与工具的关联
        skillToolService.saveSkillTool(entity.getId(), vo.getTools());
        return R.data(b);
    }

    /**
     * 仅修改技能别名（不触发关联智能体重新注册）
     */
    @PutMapping("/{id}/alias")
    @PermNeed({"skill:package:edit"})
    public R<Boolean> updateAlias(@PathVariable("id") Long id, @RequestParam("alias") String alias) {
        return R.data(skillPackageService.updateAlias(id, alias));
    }

    /**
     * 删除
     */
    @DeleteMapping
    @PermNeed({"skill:package:remove"})
    public R<Boolean> delete(@RequestBody List<Long> ids) {
        List<SkillPackage> skillPackages = skillPackageService.listByIds(ids);
        skillPackages.forEach(skill -> skillPackageService.requireManageable(skill.getId()));
        for (SkillPackage skillPackage : skillPackages) {
            // 卸载技能包目录
            SkillInstaller.uninstall(skillPackage.getName());
            // 删除文件系统目录
            SkillFileSystemService.removeSkillDir(skillPackage.getName());
            // 删除 DB 文件记录
            skillFileService.deleteBySkillId(skillPackage.getId());
        }
        return R.data(skillPackageService.deleteByIds(ids));
    }

    /**
     * 保存技能与工具的关联关系
     *
     * @param id      技能ID
     * @param toolIds 工具ID列表
     * @return 是否成功
     */
    @PutMapping("/{id}/tools")
    @PermNeed({"skill:package:edit"})
    public R<Boolean> saveTools(@PathVariable("id") Long id, @RequestBody List<Long> toolIds) {
        return R.data(skillPackageService.updateTools(id, toolIds));
    }

    /**
     * 被哪些Agent使用
     */
    @PostMapping("used-with-agent")
    @PermNeed({"skill:package:list"})
    public R<List<Object>> usedWithAgent(@RequestBody List<Long> ids) {
        ids.forEach(id -> resourceVisibilityService.assertViewable("SKILL", id));
        return R.data(skillPackageService.usedWithAgent(ids));
    }

    /**
     * 获取所有分类
     */
    @GetMapping("/get/categories")
    public R<List<String>> listCategories() {
        return R.data(skillPackageService.listCategories());
    }

    /**
     * 从git导入
     */
    @PostMapping("/import/git")
    @PermNeed({"skill:package:import"})
    public R<SkillImportResult> importFromGit(@RequestBody GitImportConfig config) {
        return R.data(skillImportService.importFromGit(config));
    }

    /**
     * 从本地导入
     */
    @PostMapping("/import/local")
    @PermNeed({"skill:package:import"})
    public R<SkillImportResult> importFromLocal(@RequestBody LocalImportConfig config) {
        return R.data(skillImportService.importFromLocal(config));
    }

    /**
     * 从会话工作空间导入（「AI 对话创建」：由智能体在会话工作空间产出 SKILL.md 与脚本后导入）
     * <p>
     * 复用本地导入的同一入库与校验路径，仅由服务端按 sessionId 解析工作空间目录：
     * console 与 runtime 共享 .baibaiyu 数据卷，因此可直接按路径读取，无需浏览器中转文件。
     * 导入后工作空间文件保留，便于用户核对与二次编辑。
     *
     * @param sessionId 会话ID（工作空间按会话隔离）
     * @param category  技能分类
     * @param cover     是否覆盖同名技能
     */
    @PostMapping("/import/workspace")
    @PermNeed({"skill:package:import"})
    public R<SkillImportResult> importFromWorkspace(@RequestParam("sessionId") String sessionId,
                                                    @RequestParam("category") String category,
                                                    @RequestParam(value = "cover", defaultValue = "false") boolean cover) {
        if (sessionId == null || !sessionId.matches("\\d+")) {
            return R.fail("会话ID不合法");
        }
        sessionAccessGuard.requireWrite(Long.valueOf(sessionId));
        Path workspaceDir = Paths.get(SysConst.getWorkspacePath(), sessionId).toAbsolutePath().normalize();
        if (!Files.isDirectory(workspaceDir)) {
            return R.fail("该会话还没有工作空间文件，请先在对话中生成技能文件");
        }
        boolean hasFile;
        try (var stream = Files.list(workspaceDir)) {
            hasFile = stream.findAny().isPresent();
        } catch (IOException e) {
            return R.fail("读取会话工作空间失败：" + e.getMessage());
        }
        if (!hasFile) {
            return R.fail("该会话的工作空间为空，请先在对话中生成技能文件");
        }

        LocalImportConfig config = new LocalImportConfig();
        config.setPath(workspaceDir.toString());
        config.setCategory(category);
        config.setCover(cover);
        return R.data(skillImportService.importFromLocal(config));
    }

    /**
     * 从压缩包导入
     *
     * @param file     技能包压缩包（zip 格式，解压后需包含 skills/ 目录）
     * @param category 技能分类
     * @param cover    是否覆盖已存在的同名技能
     */
    @PostMapping("/import/upload")
    @PermNeed({"skill:package:import"})
    public R<SkillImportResult> importFromUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("category") String category,
            @RequestParam("cover") boolean cover) throws IOException {

        // 确保 .baibaiyu/temp 目录存在
        Path tempBase = Paths.get(SysConst.ROOT_DIR_NAME, "temp");
        Files.createDirectories(tempBase);

        // 生成唯一 UUID 作为本次解压目录名
        String uuid = UUID.randomUUID().toString();
        Path extractDir = tempBase.resolve(uuid);
        Files.createDirectories(extractDir);

        Path tempZip = tempBase.resolve(uuid + ".zip");
        try {
            ZipExtractUtils.extractZipSafely(file.getInputStream(), extractDir, tempZip);
        } catch (IOException e) {
            FileUtil.del(extractDir.toFile());
            throw new BusinessException("压缩包解压失败，请确认文件为有效 zip 格式: " + e.getMessage());
        }

        // 解析 skills 根目录（兼容压缩包多套一层目录的结构）
        Path skillsDir;
        try {
            skillsDir = SkillImportPathResolver.resolveUploadedSkillsDir(extractDir);
        } catch (RuntimeException e) {
            FileUtil.del(extractDir.toFile());
            throw e;
        }

        UploadImportConfig config = UploadImportConfig.builder()
                .category(category)
                .cover(cover)
                .templatePath(skillsDir.toString())
                .extractDirPath(extractDir.toString())
                .build();

        return R.data(skillImportService.importFromUpload(config));
    }
}
