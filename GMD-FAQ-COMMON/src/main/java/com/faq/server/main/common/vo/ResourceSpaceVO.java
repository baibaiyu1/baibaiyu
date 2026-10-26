package com.gmd.faq.server.main.common.vo;

/** 空间只选择资源工作范围，不改变登录身份、部门或租户。 */
public record ResourceSpaceVO(String key, String name, Long departmentId, boolean canCreate) { }
