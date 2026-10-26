package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.consts.TableConst;
import lombok.Getter;
import lombok.Setter;

/** 对部门及其下级部门的当前成员授予资源查看权，以及可选的使用权。 */
@Getter
@Setter
@TableName(TableConst.RESOURCE_DEPARTMENT_GRANT)
public class ResourceDepartmentGrant extends BaseTenantEntity {

    private String resourceType;

    private Long resourceId;

    private Long departmentId;

    private Boolean allowUse;
}
