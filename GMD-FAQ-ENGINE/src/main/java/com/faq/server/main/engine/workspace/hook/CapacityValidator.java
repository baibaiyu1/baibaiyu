package com.gmd.faq.server.main.engine.workspace.hook;

import com.gmd.faq.server.main.common.util.FolderUtils;

/**
 * 描述：存储容量限制
 *
 **/
public class CapacityValidator {
    /**
     * 验证存储容量
     */
    public static boolean validateCapacity(String relativePath, double maxMB) {
        long sizeByRelativePath = FolderUtils.getSizeByRelativePath(relativePath);
        return sizeByRelativePath < mbToBytes(maxMB);
    }

    /**
     * MB to bytes
     */
    private static long mbToBytes(double mb) {
        return (long) (mb * 1024 * 1024);
    }
}
