package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.consts.TableConst;
import lombok.Getter;
import lombok.Setter;

/**
 * 资源收藏
 * <p>
 * 账号级数据：不改变资源本身的可见性，仅用于「我的收藏」视图。
 *
 */
@Getter
@Setter
@TableName(value = TableConst.RESOURCE_FAVORITE)
public class ResourceFavorite extends BaseTenantEntity {

    /**
     * 资源类型：AGENT/WORKFLOW/SKILL/TOOL/KNOWLEDGE/MCP
     */
    private String resourceType;

    /**
     * 资源ID
     */
    private String resourceId;

    /**
     * 收藏人账号ID
     */
    private Long accountId;
}
