package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.config.SerializableEnable;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.mp.annotation.QueryDefine;
import com.gmd.faq.server.main.common.mp.support.QueryCondition;
import lombok.Data;

/**
 * 描述：参数表
 *
 **/
@Data
@TableName(TableConst.PARAMS)
public class Params implements SerializableEnable {
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;

    @QueryDefine(condition = QueryCondition.LIKE)
    private String paramName;

    @QueryDefine(condition = QueryCondition.LIKE)
    private String paramKey;

    @QueryDefine(condition = QueryCondition.LIKE)
    private String paramValue;
}
