package com.gmd.faq.server.main.hook.mapper;

import com.gmd.faq.server.main.common.entity.AgentHook;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 智能体Hook关联Mapper
 *
 */
@Mapper
public interface AgentHookMapper extends BaseMapper<AgentHook> {
}
