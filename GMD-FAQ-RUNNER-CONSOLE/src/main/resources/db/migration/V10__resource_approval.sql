-- ============================================================
-- 资源发布审批：审批流配置 + 审批申请单
-- ============================================================
-- 背景：
--   平台原有「启用 / 停用」由操作者直接生效，缺少发布治理环节。
--   本次新增两张表，支撑「申请 → 审批人处理 → 通过后生效」的闭环：
--     gmd_faq_approval_flow     审批流配置（按资源类型配置是否启用审批与审批人）
--     gmd_faq_approval_request  审批申请单（一次发布申请一条记录）
--
--   审批通过后的生效动作由后端 ApprovalService 内按资源类型分发：
--     AGENT    -> gmd_faq_agent_definition.enabled = 1
--     WORKFLOW -> gmd_faq_workflow.status = 'PUBLISHED'
--   其余类型当前仅记录审批结果（不改变资源状态），后续按需扩展。
--
-- 幂等性（重要）：
--   本脚本同时面向「存量库」与「新库（由 sql/db_init.sql 初始化）」两类环境，
--   且可能因环境已有部分结构而重复执行，因此：
--     1) 建表统一使用 CREATE TABLE IF NOT EXISTS；
--     2) 本脚本不修改任何既有表结构；
--     3) 不写入任何业务数据（审批流配置按需由界面创建）。
--
-- 依赖：本脚本不读取其它表数据做回填，可在 V1 基线之后任意位置执行。
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------- 1. 审批流配置 ----------
CREATE TABLE IF NOT EXISTS `gmd_faq_approval_flow` (
  `id` bigint NOT NULL COMMENT '主键',
  `resource_type` varchar(40) NOT NULL COMMENT '资源类型：AGENT/WORKFLOW/SKILL/TOOL/KNOWLEDGE/MCP',
  `approver_ids` varchar(500) DEFAULT NULL COMMENT '审批人账号ID，逗号分隔（多人时任一通过即生效）',
  `approver_names` varchar(500) DEFAULT NULL COMMENT '审批人名称快照，逗号分隔，用于列表展示',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否启用审批：0=关闭（发布直接生效），1=开启',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_approval_flow_tenant_type` (`tenant_id`, `resource_type`)
) COMMENT='资源发布审批流配置';

-- ---------- 2. 审批申请单 ----------
CREATE TABLE IF NOT EXISTS `gmd_faq_approval_request` (
  `id` bigint NOT NULL COMMENT '主键',
  `resource_type` varchar(40) NOT NULL COMMENT '资源类型：AGENT/WORKFLOW/SKILL/TOOL/KNOWLEDGE/MCP',
  `resource_id` varchar(64) NOT NULL COMMENT '资源ID',
  `resource_name` varchar(200) DEFAULT NULL COMMENT '资源名称快照',
  `resource_version` varchar(50) DEFAULT NULL COMMENT '资源版本快照',
  `applicant_id` bigint DEFAULT NULL COMMENT '申请人账号ID',
  `applicant_name` varchar(100) DEFAULT NULL COMMENT '申请人名称快照',
  `approver_id` bigint DEFAULT NULL COMMENT '处理人账号ID（通过/拒绝时写入）',
  `approver_name` varchar(100) DEFAULT NULL COMMENT '处理人名称快照',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/APPROVED/REJECTED/CANCELED',
  `opinion` varchar(1000) DEFAULT NULL COMMENT '审批意见',
  `remark` varchar(500) DEFAULT NULL COMMENT '申请说明',
  `handled_at` datetime DEFAULT NULL COMMENT '处理时间',
  `enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '记录是否有效',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_approval_request_tenant_status` (`tenant_id`, `status`),
  KEY `idx_approval_request_resource` (`tenant_id`, `resource_type`, `resource_id`),
  KEY `idx_approval_request_applicant` (`tenant_id`, `applicant_id`, `status`),
  KEY `idx_approval_request_approver` (`tenant_id`, `approver_id`, `status`)
) COMMENT='资源发布审批申请单';

SET FOREIGN_KEY_CHECKS = 1;
