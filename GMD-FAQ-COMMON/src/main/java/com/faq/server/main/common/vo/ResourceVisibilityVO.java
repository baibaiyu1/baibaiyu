package com.gmd.faq.server.main.common.vo;

import lombok.Data;

/**
 * 资源可见性视图对象
 * <p>
 * 卡片列表加载后由 {@code GET /resource/visibility} 按 ID 批量补读归属信息及
 * 当前账号的使用、维护、共享和移交资格。后端对实际操作再次鉴权。
 *
 */
@Data
public class ResourceVisibilityVO {

    /**
     * PRIVATE 按资产归属限制访问；TENANT 扩大到所属部门分支或平台。
     */
    private String visibility;

    /**
     * 个人资产归属账号；部门与平台资产为空。
     */
    private Long ownerId;

    /** 当前账号是否可以使用该资源（负责人的私有资源只读）。 */
    private Boolean canUse;

    /** 当前账号是否具备资源域编辑权限及资产维护资格。 */
    private Boolean canManage;

    /** 当前账号能否切换可见性，共享不取消维护资格。 */
    private Boolean canChangeVisibility;
    private String ownershipType;
    private Long owningDeptId;
    private Long maintainerId;
    private String spaceName;
    private Boolean canTransfer;
}
