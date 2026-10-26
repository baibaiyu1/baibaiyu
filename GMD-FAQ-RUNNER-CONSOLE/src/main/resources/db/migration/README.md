# Flyway 数据库迁移脚本目录

本目录是唯一的增量迁移入口（`classpath:db/migration`），随 `GMD-FAQ-RUNNER-CONSOLE` 打包，Console 启动时自动执行。`sql/incremental/` 仅保留历史参考，不再手工执行。

## 约定

- 首次初始化由仓库根目录 `sql/db_init.sql` 完成（Flyway 基线版本为 V1，不在此目录维护）。
  **禁止**把 V2 及之后的结构变更回写到 `db_init.sql`，否则全新部署时对应迁移会因「表/列/索引已存在」而失败。
- 增量脚本命名：`V{版本}__{描述}.sql`，版本号从 `V2` 开始递增，如 `V2__workflow_add_column.sql`。
- 版本号必须全局唯一且不重复使用；已发布的脚本禁止修改（Flyway 会做 checksum 校验）。
- 脚本必须**幂等**，且对「存量库」与「新库（由 `db_init.sql` 初始化）」两种情况都安全：
  不能直接写 `RENAME TABLE` 或 `CREATE TABLE ... LIKE 旧表`（旧表/新表不存在时会直接报错），
  需先查 `information_schema` 再通过 `PREPARE` / `EXECUTE` 动态执行，参考 `V7__gmd_table_rename.sql`、`V8__merge_legacy_agentscope_sessions.sql`。
- 默认禁止 `DROP TABLE` 等破坏性语句，需基于当前结构编写 `ALTER` / `CREATE` / `UPDATE`；
  确因命名迁移需要清理旧表时，仅允许「判断存在后再删」，并在脚本头部写明原因。
- 存量库首次升级：`GMD-FAQ-RUNNER-CONSOLE` 启动时 Flyway 检测到非空库且无历史表，自动执行 baseline（标记 V1 已应用），随后按序执行本目录全部增量脚本。
- 新库：先执行 `sql/db_init.sql` 完成首次初始化，再启动应用即可。

## AgentScope 2.0 状态迁移

`V28__agentscope_state_version.sql` 为 AgentScope 状态表增加 `version BIGINT NOT NULL DEFAULT 1`，用于乐观锁；保留原数据和 `session_id/state_key/item_index` 主键。缺表时创建完整表，存在表时先检查列是否存在，重复执行不会再次添加。

升级先启动 Console，确认日志和 `flyway_schema_history` 中 V28 成功，再启动依赖新状态结构的 Runtime。1.x 消息、待确认工具和计划由 Runtime 首次读取时转换；V28 不重写业务消息或会话。具体顺序见 [Docker 升级步骤](../../../../../../docker/README.md#版本升级)。

## 迁移验证

`V29__agent_instructions.sql` 新建运行收件箱、补充消息表和“运行中补充要求”权限点。该权限默认分配给内置管理员及编辑角色，自定义角色需在授权页勾选。先启动 Console 完成 V29，再启动 Runtime。

`V30__agent_telemetry.sql` 新建运行观测表，保存按租户、账号、会话、运行、智能体隔离的阶段记录。用量缺失保持 NULL，不写入模型或工具正文。先由 Console 迁移，再启动新 Runtime；会话清空/删除同时清理记录。

`V31__agent_model_resilience.sql` 为智能体追加可空的 `model_resilience_config` JSON 列。已有配置和主模型不变；先启动 Console 完成 V31，再重建 Runtime。最大尝试次数、备用和摘要模型单独保存，不与普通模型参数覆盖共用开关，修改权限和模型兼容性由服务端校验。

`V32__agent_conversation_queue.sql` 新建同会话运行门闩与下一轮消息队列，复用运行中补充要求权限。记录租约、暂停和执行状态，已开始消息中断后不自动重试。先完成迁移，再启动 Runtime。

`V33__tool_direct_and_model_protocol.sql` 为工具追加 `return_direct`，为模型追加 `api_type` 和 `responses_sampling`。存量工具默认不直接返回，模型默认使用 Chat Completions；缺表时跳过，存在字段时不重建。先由 Console 完成迁移，再更新依赖新字段的服务。

`V34__mcp_description_mediumtext.sql` 将 MCP 服务及工具的 `description` 扩为 `MEDIUMTEXT`，保留现有描述，支持超过 4000 字符的说明。缺表或缺列时跳过，已为 `MEDIUMTEXT` / `LONGTEXT` 时保持原结构。先更新 Console 自动迁移，再更新前端以移除服务描述的 200 字符限制；工具描述通过连接或刷新工具目录重新同步。

`V35__lightrag_knowledge_type.sql` 在知识库 `kb_type` 的 ENUM 中追加 `LIGHTRAG`，保留已有类型和业务数据。缺表、缺列或已包含该值时跳过；VARCHAR 类型保持兼容，无需修改。先由 Console 完成迁移，再更新 Runtime 和前端；LightRAG 本身独立部署，使用方式见[知识库手册](../../../../../../ui/src/doc/content/knowledge/index.md#lightrag-知识库)。

`V36__resource_spaces.sql` 为五类资源增加资产归属类型、当前所属部门和维护人，保留创建时部门及已有使用授权。历史有归属人的资源保持个人资产，无归属人的公共池标记为平台资产；不自动归入部门。先启动 Console 完成 V36，再更新 Runtime 和前端，避免旧 Runtime 按空归属人误识别部门工具。空间切换和移交规则见 [常见问题](../../../../../../ui/src/doc/content/qa/index.md)。

修改基线或新增迁移后，在独立临时库验证以下情况：

1. 从 V1 开始按版本执行全部迁移，检查存量数据和关联未被重建或丢失。
2. 按脚本支持的缺表、缺列或旧结构场景执行，并重复执行新增脚本验证幂等。
3. 对比 `information_schema.columns` 和索引，确认类型、空值、默认值及主键一致。
4. 启动 Console 检查 Flyway 日志和历史表，再用依赖新结构的真实业务接口验证。

Runtime 中的真实 MySQL 状态测试使用 `AGENTSCOPE_TEST_MYSQL_URL`、`AGENTSCOPE_TEST_MYSQL_USER` 和 `AGENTSCOPE_TEST_MYSQL_PASSWORD` 指向独立测试库。连接信息仅用于测试环境，不写入文档或提交。

出现 checksum mismatch 时恢复已应用脚本，通过新版本追加修正；不要修改历史表中的 checksum 绕过校验。
