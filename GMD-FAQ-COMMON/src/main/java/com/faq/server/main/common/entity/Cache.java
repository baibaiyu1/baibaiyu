package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.enums.HealthStatus;
import com.gmd.faq.server.main.common.enums.cache.CacheType;
import com.gmd.faq.server.main.common.mp.annotation.QueryDefine;
import com.gmd.faq.server.main.common.mp.support.QueryCondition;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 描述：缓存表
 *
 **/
@Getter
@Setter
@TableName(TableConst.CACHE)
public class Cache extends BaseTenantEntity {
    /**
     * 缓存名称
     */
    @QueryDefine(condition = QueryCondition.LIKE)
    private String name;
    /**
     * 缓存描述
     */
    private String remark;
    /**
     * 缓存类型
     */
    @QueryDefine(condition = QueryCondition.EQ)
    private CacheType type;
    /**
     * 缓存地址
     */
    @QueryDefine(condition = QueryCondition.LIKE)
    private String ip;
    /**
     * 缓存端口
     */
    @QueryDefine(condition = QueryCondition.EQ)
    private Integer port;
    /**
     * 数据库
     */
    @QueryDefine(condition = QueryCondition.EQ)
    private Integer db;
    /**
     * 数据库配置
     */
    private String config;
    /**
     * 用户名
     */
    @QueryDefine(condition = QueryCondition.LIKE)
    private String username;
    /**
     * 密码
     */
    @QueryDefine(condition = QueryCondition.LIKE)
    private String password;

    /**
     * 健康状态
     */
    @QueryDefine(condition = QueryCondition.EQ)
    private HealthStatus healthStatus;

    /**
     * 最后一次健康检查时间
     */
    private LocalDateTime lastHealthCheck;

    /**
     * 最后一次健康检查消息
     */
    private String lastCheckMessage;
}
