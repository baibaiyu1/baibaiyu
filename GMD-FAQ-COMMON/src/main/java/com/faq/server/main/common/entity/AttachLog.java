package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.config.SerializableEnable;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.mp.annotation.QueryDefine;
import com.gmd.faq.server.main.common.mp.support.QueryCondition;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 附件操作日志表
 *
 */
@Getter
@Setter
@TableName(TableConst.ATTACH_LOG)
public class AttachLog implements SerializableEnable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    /**
     * 文件id
     */
    @QueryDefine(condition = QueryCondition.EQ)
    private Long fileId;

    /**
     * 附件原名
     */
    @QueryDefine(condition = QueryCondition.LIKE)
    private String originalName;

    /**
     * 附件拓展名
     */
    @QueryDefine(condition = QueryCondition.LIKE)
    private String extension;

    /**
     * 附件大小
     */
    private Long attachSize;

    /**
     * 操作人
     */
    private Long optUser;

    /**
     * 操作人名称
     */
    private String optUserName;

    /**
     * 操作时间
     */
    private LocalDateTime optTime;

    /**
     * 操作IP
     */
    private String optIp;

    /**
     * 操作类型
     */
    @QueryDefine(condition = QueryCondition.EQ)
    private String optType;
}
