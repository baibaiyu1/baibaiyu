package com.gmd.faq.server.main.resource.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gmd.faq.server.main.common.entity.StorageProtocol;
import com.gmd.faq.server.main.resource.storage.core.FileStorageService;

/**
 * 描述：文件存储协议配置
 *
 **/
public interface StorageProtocolService extends IService<StorageProtocol> {
    boolean saveV2(StorageProtocol body);
    boolean updateV2(StorageProtocol body);

    /**
     * 根据ID设置启用
     * @param id id
     */
    boolean validSuccess(Long id);

    /**
     * 获取存储服务
     */
    FileStorageService getStorageService();


}
