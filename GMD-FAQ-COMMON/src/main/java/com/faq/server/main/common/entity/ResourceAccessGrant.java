package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.consts.TableConst;
import lombok.Getter;
import lombok.Setter;

/** 对指定账号授予资源查看权，以及可选的使用权。 */
@Getter
@Setter
@TableName(TableConst.RESOURCE_ACCESS_GRANT)
public class ResourceAccessGrant extends BaseTenantEntity {

    private String resourceType;

    private Long resourceId;

    private Long accountId;

    private Boolean allowUse;
}
