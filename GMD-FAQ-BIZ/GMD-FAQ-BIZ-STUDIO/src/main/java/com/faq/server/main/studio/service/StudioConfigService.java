package com.gmd.faq.server.main.studio.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gmd.faq.server.main.common.entity.StudioConfig;

import java.util.List;

/**
 * 描述：StudioConfigService
 *
 **/
public interface StudioConfigService extends IService<StudioConfig> {
    List<Object> usedWithAgent(List<Long> ids);
}
