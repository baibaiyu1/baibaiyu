package com.gmd.faq.server.main.common.entity;

/**
 * 五类资源的资产归属、维护人与可见性契约
 * <p>
 * 「资源型」表（智能体 / 工具 / 技能 / 知识库 / MCP）在 V11 迁移中统一补齐了
 * {@code owner_id} 与 {@code visibility} 两列，V23 加入创建时部门，V36 区分个人、部门和平台资产。
 * 这些字段只由五类资源实体分别声明，不能放入 {@link BaseTenantEntity}。
 * 由于各资源域的表结构一致，凡是需要「创建时写归属」「操作前校验归属」的通用逻辑
 * （{@code ResourceOwnershipSupport}）都以本接口为入参，避免为 5 个实体各写一遍重载。
 * <p>
 * 注意：只有上述 5 张资源表具有这些字段，**不要把本接口实现到别的持久化实体上**，
 * 否则 MyBatis-Plus 会往不含该列的表里写 {@code owner_id} / {@code visibility}。
 *
 */
public interface OwnedResource {

    /**
     * @return 个人资产归属账号；部门和平台资产为空，必须结合 ownershipType 判断归属
     */
    Long getOwnerId();

    /**
     * @param ownerId 归属人账号ID
     */
    void setOwnerId(Long ownerId);

    /**
     * @return 可见性取值（{@code PRIVATE} / {@code TENANT}）
     */
    String getVisibility();

    /**
     * @param visibility 可见性取值
     */
    void setVisibility(String visibility);

    /** 创建时所属部门；账号调部门后不随之迁移。 */
    Long getResourceDeptId();

    void setResourceDeptId(Long resourceDeptId);

    /** PERSONAL / DEPARTMENT / PLATFORM；与创建人、可见性分别保存。 */
    String getOwnershipType();
    Long getOwningDeptId();
    Long getMaintainerId();
    void setOwnershipType(String value);
    void setOwningDeptId(Long value);
    void setMaintainerId(Long value);
}
