package com.gmd.faq.server.main.common.vo;

import lombok.Data;

/** 可接受资源授权的当前租户账号。 */
@Data
public class ResourceGrantAccountVO {

    private Long id;

    private String nickname;

    private String username;

    private String deptName;
}
