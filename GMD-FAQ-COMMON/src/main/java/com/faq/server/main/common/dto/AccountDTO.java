package com.gmd.faq.server.main.common.dto;

import com.gmd.faq.server.main.common.mp.annotation.QueryDefine;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.mp.support.QueryCondition;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 账号查询DTO
 *
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AccountDTO extends PageParams {

    @QueryDefine(value = "昵称", condition = QueryCondition.LIKE)
    private String nickname;

    @QueryDefine(value = "邮箱", condition = QueryCondition.LIKE)
    private String email;

    @QueryDefine(value = "用户名", condition = QueryCondition.LIKE)
    private String username;

    @QueryDefine(value = "是否可用", condition = QueryCondition.EQ)
    private Boolean enabled;
}
