package com.gmd.faq.server.main.tool.mapper;

import com.gmd.faq.server.main.common.entity.SkillTool;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 技能工具关联Mapper
 *
 */
@Mapper
public interface ISkillToolMapper extends BaseMapper<SkillTool> {
}
