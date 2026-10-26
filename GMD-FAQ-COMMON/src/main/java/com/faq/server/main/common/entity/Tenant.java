package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.consts.TableConst;
import lombok.Getter;
import lombok.Setter;

/**
 * 租户表
 *
 */
@Getter
@Setter
@TableName(TableConst.TENANT)
public class Tenant extends BaseEntity {
    private String name;
    private String code;
    private String description;
    private Integer status;
    private String contactName;
    private String contactEmail;
    private String config;
}
