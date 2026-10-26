package com.gmd.faq.server.main.common.config.mybatis;

import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 描述：自定义元对象字段填充
 *
 **/
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "tenantId", Long.class, TenantUtils.getCurrentTenantId());
        this.strictInsertFill(metaObject, "enabled", Boolean.class, true);
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "createdBy", Long.class, UserUtils.getId());
        this.strictInsertFill(metaObject, "updatedBy", Long.class, UserUtils.getId());
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.setFieldValByName("updatedAt", LocalDateTime.now(), metaObject);
        this.setFieldValByName("updatedBy", UserUtils.getId(), metaObject);
    }
}
