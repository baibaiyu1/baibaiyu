package com.gmd.faq.server.main.tool.mapper;

import com.gmd.faq.server.main.common.entity.ToolConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工具Mapper
 *
 */
@Mapper
public interface ToolMapper extends BaseMapper<ToolConfig> {
}
