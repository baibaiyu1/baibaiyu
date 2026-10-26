package com.gmd.faq.server.main.resource.storage.config;

import lombok.Getter;
import lombok.Setter;

/**
 * 描述：本地存储（LOCAL）
 *
 **/
@Setter
@Getter
public class LocalStorageConfig {
    private String localDir = "/home";
}
