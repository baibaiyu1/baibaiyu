package com.gmd.faq.server.main.sensitive.mapper;

import com.gmd.faq.server.main.common.entity.SensitiveWordConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 敏感词配置Mapper
 *
 */
@Mapper
public interface SensitiveWordConfigMapper extends BaseMapper<SensitiveWordConfig> {
}
