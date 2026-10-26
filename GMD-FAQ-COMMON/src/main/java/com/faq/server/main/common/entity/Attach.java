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
 * 附件表
 *
 */
@Getter
@Setter
@TableName(TableConst.ATTACH)
public class Attach implements SerializableEnable {

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
     * 附件地址
     */
    @QueryDefine(condition = QueryCondition.LIKE)
    private String link;

    /**
     * 附件域名
     */
    @QueryDefine(condition = QueryCondition.LIKE)
    private String domain;

    /**
     * 附件名称
     */
    @QueryDefine(condition = QueryCondition.LIKE)
    private String name;

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
     * 存储路径
     */
    private String path;

    /**
     * 创建人
     */
    private Long createBy;

    /**
     * 创建时间
     */
    private LocalDateTime createAt;

    /**
     * 修改人
     */
    private Long updateBy;

    /**
     * 修改时间
     */
    private LocalDateTime updateAt;

    /**
     * 存储协议
     */
    private String protocol;

    /**
     * 状态
     */
    private Integer status;
}
