package com.gmd.faq.server.main.longterm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gmd.faq.server.main.common.entity.LongTermMemoryConfig;

import java.util.List;

/**
 * 描述：LongTermMemoryConfigService
 *
 **/
public interface LongTermMemoryConfigService extends IService<LongTermMemoryConfig> {
    List<Object> usedWithAgent(List<Long> ids);
}
