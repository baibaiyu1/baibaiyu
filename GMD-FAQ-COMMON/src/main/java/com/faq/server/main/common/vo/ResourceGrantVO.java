package com.gmd.faq.server.main.common.vo;

import lombok.Data;

/** 单个账号或部门的资源访问授权。 */
@Data
public class ResourceGrantVO {

    private Long accountId;

    private Long departmentId;

    private String departmentName;

    private Boolean allowUse;

    private String nickname;

    private String username;
}
