package com.gmd.faq.server.main.skill.service.impl;

import com.gmd.faq.server.main.common.entity.SkillFile;
import com.gmd.faq.server.main.skill.mapper.SkillFileMapper;
import com.gmd.faq.server.main.skill.service.SkillFileService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 描述：技能包文件Service实现
 *
 **/
@Service
@RequiredArgsConstructor
public class SkillFileServiceImpl extends ServiceImpl<SkillFileMapper, SkillFile> implements SkillFileService {
    private final com.gmd.faq.server.main.skill.service.SkillPackageService skillPackageService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(SkillFile file) {
        boolean saved = super.save(file);
        if (saved) skillPackageService.refreshBoundAgents(file.getSkillId());
        return saved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateById(SkillFile file) {
        SkillFile current = getById(file.getId());
        boolean updated = super.updateById(file);
        if (updated && current != null) skillPackageService.refreshBoundAgents(current.getSkillId());
        return updated;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(java.io.Serializable id) {
        SkillFile current = getById(id);
        boolean removed = super.removeById(id);
        if (removed && current != null) skillPackageService.refreshBoundAgents(current.getSkillId());
        return removed;
    }

    @Override
    public List<SkillFile> listBySkillId(Long skillId) {
        return lambdaQuery()
                .eq(SkillFile::getSkillId, skillId)
                .orderByAsc(SkillFile::getFileType)
                .orderByAsc(SkillFile::getSort)
                .orderByAsc(SkillFile::getFileName)
                .list();
    }

    @Override
    public SkillFile getBySkillIdAndPath(Long skillId, String filePath) {
        return lambdaQuery()
                .eq(SkillFile::getSkillId, skillId)
                .eq(SkillFile::getFilePath, filePath)
                .one();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateContent(Long fileId, String content) {
        SkillFile file = new SkillFile();
        file.setId(fileId);
        file.setContent(content);
        return updateById(file);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteBySkillId(Long skillId) {
        boolean removed = remove(new LambdaQueryWrapper<SkillFile>()
                .eq(SkillFile::getSkillId, skillId));
        if (removed) skillPackageService.refreshBoundAgents(skillId);
        return removed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeBySkillIdAndPath(Long skillId, String filePath) {
        boolean removed = remove(new LambdaQueryWrapper<SkillFile>()
                .eq(SkillFile::getSkillId, skillId)
                .eq(SkillFile::getFilePath, filePath));
        if (removed) skillPackageService.refreshBoundAgents(skillId);
        return removed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeBySkillIdAndPathPrefix(Long skillId, String pathPrefix) {
        boolean removed = remove(new LambdaQueryWrapper<SkillFile>()
                .eq(SkillFile::getSkillId, skillId)
                .likeRight(SkillFile::getFilePath, pathPrefix));
        if (removed) skillPackageService.refreshBoundAgents(skillId);
        return removed;
    }
}
