package com.gmd.faq.server.main.prompt.mapper;

import com.gmd.faq.server.main.common.entity.SystemPromptTemplate;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统提示词模板Mapper
 *
 */
@Mapper
public interface SystemPromptTemplateMapper extends BaseMapper<SystemPromptTemplate> {
}
