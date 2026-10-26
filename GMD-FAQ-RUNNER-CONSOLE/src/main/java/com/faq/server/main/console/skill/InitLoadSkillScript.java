package com.gmd.faq.server.main.console.skill;

import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.entity.SkillFile;
import com.gmd.faq.server.main.common.entity.SkillPackage;
import com.gmd.faq.server.main.common.util.RedisUtils;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.skill.SkillFileSystemService;
import com.gmd.faq.server.main.skill.service.SkillFileService;
import com.gmd.faq.server.main.skill.service.SkillPackageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 描述：启动时按租户同步技能包文件到文件系统，并补录 DB 中缺失的记录
 *
 **/
@Slf4j
@Service
@RequiredArgsConstructor
public class InitLoadSkillScript implements ApplicationRunner {
    private final SkillPackageService skillPackageService;
    private final SkillFileService skillFileService;
    private final JdbcTemplate jdbcTemplate;
    private final RedisUtils redisUtils;

    @Override
    public void run(ApplicationArguments args) {
        String lockValue = UUID.randomUUID().toString();
        if (!redisUtils.tryLock(RedisChannelTopic.LOCK_SKILL_INIT, lockValue, 120, TimeUnit.SECONDS)) {
            log.info("其他节点正在执行技能包文件同步，本节点跳过");
            return;
        }
        try {
            doSync();
        } finally {
            redisUtils.unlock(RedisChannelTopic.LOCK_SKILL_INIT, lockValue);
        }
    }

    private void doSync() {
        List<TenantContext> tenants = getAllTenants();
        if (tenants.isEmpty()) {
            log.warn("未找到任何租户，跳过技能包文件同步");
            return;
        }

        for (TenantContext tenant : tenants) {
            // 设置租户上下文，使 MyBatis-Plus 拦截器和 SysConst.getSkillsDir() 正确工作
            TenantUtils.setCurrentTenant(tenant.id, tenant.code);
            try {
                syncTenantSkills();
            } finally {
                TenantUtils.clear();
            }
        }
    }

    /**
     * 同步当前租户（须在 TenantUtils 上下文已设置后调用）的技能包文件
     */
    private void syncTenantSkills() {
        List<SkillPackage> list = skillPackageService.list();
        for (SkillPackage skillPackage : list) {
            // 将 DB 中的入库文件同步到文件系统（路径自动隔离到 .baibaiyu/tenants/{code}/skills/）
            List<SkillFile> files = skillFileService.listBySkillId(skillPackage.getId());
            for (SkillFile file : files) {
                SkillFileSystemService.writeFile(skillPackage.getName(), file.getFilePath(), file.getContent());
            }

            // 扫描文件系统，补录 DB 中缺失的入库文件
            List<SkillFileSystemService.FileTreeNode> fsNodes =
                    SkillFileSystemService.scanSkillTree(skillPackage.getName());
            syncMissingFiles(skillPackage, fsNodes);

            log.info("已同步技能包 {} 的文件到本地", skillPackage.getName());
        }
    }

    /**
     * 递归扫描文件系统，将符合入库规则但 DB 中不存在的文件补录到 DB
     */
    private void syncMissingFiles(SkillPackage skillPackage, List<SkillFileSystemService.FileTreeNode> nodes) {
        for (SkillFileSystemService.FileTreeNode node : nodes) {
            if (node.isDirectory()) {
                syncMissingFiles(skillPackage, node.getChildren());
                continue;
            }

            String relPath = node.getPath().replace('\\', '/');
            if (!SkillFileSystemService.shouldPersistToDb(relPath)) {
                continue;
            }

            // 检查 DB 中是否已存在
            SkillFile existing = skillFileService.getBySkillIdAndPath(skillPackage.getId(), relPath);
            if (existing != null) {
                continue;
            }

            // 读取文件内容并写入 DB
            String content = SkillFileSystemService.readFileContent(skillPackage.getName(), relPath);
            SkillFile sf = new SkillFile();
            sf.setSkillId(skillPackage.getId());
            sf.setFileType(SkillFileSystemService.resolveFileType(relPath));
            sf.setFileName(node.getName());
            sf.setFilePath(relPath);
            sf.setContent(content != null ? content : "");
            sf.setSort(0);
            skillFileService.save(sf);

            log.info("补录技能包文件到 DB: skillName={}, path={}", skillPackage.getName(), relPath);
        }
    }

    /**
     * 查询所有启用的租户（JdbcTemplate 绕过 MyBatis-Plus 拦截器）
     */
    private List<TenantContext> getAllTenants() {
        return jdbcTemplate.query(
                "SELECT id, code FROM " + TableConst.TENANT + " WHERE enabled = 1",
                (rs, rowNum) -> new TenantContext(rs.getLong("id"), rs.getString("code")));
    }

    /** 租户上下文（id + code） */
    private record TenantContext(Long id, String code) {}
}
