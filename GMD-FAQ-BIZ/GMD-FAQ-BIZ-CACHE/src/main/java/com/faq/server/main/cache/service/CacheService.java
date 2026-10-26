package com.gmd.faq.server.main.cache.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gmd.faq.server.main.common.entity.Cache;

import java.util.List;

public interface CacheService extends IService<Cache> {
    boolean deleteCache(Integer force, List<String> cacheIds);

    boolean updateCache(Cache cache);

    boolean updateEnable(String cacheId, Integer enable);

    boolean checkConnect(Cache cache);

    boolean checkSavedConnect(String cacheId);

    List<Cache> listByEnabled(Integer enabled);
}
