# 仓库开发指南

本文件只记录开发约束和稳定不变量。项目入口见 [README.md](README.md)，部署见 [Docker 指南](docker/README.md)，存储见 [共享存储指南](docker/shared-storage.md)，产品行为见 `ui/src/doc/content/`。发现文档与实现不一致时，先核对代码，再更新对应文档。

修改前检查工作区差异，不覆盖已有用户改动。验证记录、机器临时路径和一次性排障过程不写入本文件；同一主题在正式文档中维护，通过链接引用。

提交信息使用简短中文说明，不加 `feat`、`fix` 等类型前缀。每个阶段可以单独提交，但不要把无关改动混入提交。

## 项目与验证

- 后端是 Java 21 / Spring Boot / Maven 多模块，源码物理路径使用 `com/faq/server/main`，包声明使用 `com.gmd.faq.server.main`。
- 前端在 `ui/`，使用 Vue 3、Vite、Pinia 和 pnpm；产品手册作为独立 doc 应用构建。
- 常用端口：console `3060`、runtime `3061`、proxy `3062`、websocket `3064`、前端 `80`。

按影响范围选择验证；测试覆盖有限，不要只以“编译通过”结案。以下命令均从仓库根目录执行：

| 改动范围 | 验证命令 |
| --- | --- |
| 全模块或公共依赖 | `mvn -DskipTests package` |
| Console | `mvn -pl GMD-FAQ-RUNNER-CONSOLE -am -DskipTests package` |
| 智能体引擎 / Runtime | `mvn -pl GMD-FAQ-ENGINE test`、`mvn -pl GMD-FAQ-RUNNER-RUNTIME test`；先构建或安装所需依赖 |
| 前端主应用 | `pnpm -C ui type-check`、`pnpm -C ui build:main` |
| 聊天、图片或产物 | 按改动执行 `pnpm -C ui test:chat-stream`、`pnpm -C ui test:tool-result-images`、`pnpm -C ui test:chat-artifacts` |
| 文档 | `node scripts/validate-docs-links.mjs`、`node scripts/validate-docs-mermaid.mjs`；修改产品手册时执行 `pnpm -C ui build:doc` |

文档检查默认扫描 `ui/src/doc/content/`；仓库或模块 README 使用 `--docs <文件或目录>` 单独检查。所有改动最后执行 `git diff --check`。

产品手册的跨页链接使用 `#/agent` 等 Hash 路由；截图只引用实际存在的文件，不保留失效占位。审批与图片操作维护在对话手册，部署参数维护在 Docker 指南，其余页面引用对应主题。

需要联调时使用 `docker compose -f docker/docker-compose-simple.yml up -d --build`，并检查容器日志。接口验证应先登录取得 token，再携带 `Authorization: Bearer ...` 调用真实接口；结论中写出实际执行的命令和失败项。

## 后端约定

1. **表名**：实体 `@TableName` 和手写 SQL 一律使用 `TableConst`，禁止写 `gmd_faq_...` 字面量。
2. **目录与 Mapper**：新 Java 文件沿用所在模块的 `com/faq/...` 目录，不要创建 `com/gmd/...` 物理目录。Mapper XML 放在 `src/main/java/**/mapper/`，必须包含 XML 声明和 MyBatis Mapper DOCTYPE。
3. **接口返回**：Controller 统一返回 `R<T>`；成功用 `R.data`，失败用 `R.fail`。详情查询先判空，再对非空对象做 Bean 拷贝或设置字段。
4. **多租户**：MyBatis-Plus 会自动注入 `tenant_id`，`JdbcTemplate` 不会。没有该列的表必须加入 `MybatisPlusConfig.IGNORE_TABLES`；临时切换租户必须 `try/finally` 清理 `TenantUtils`。避免递归 CTE，必要时查全量后在 Java 侧组装。
5. **权限**：接口使用 `@PermNeed`，业务层用 `UserUtils.hasPerm` / `UserUtils.isAdmin`；不要重复查权限库。新增权限点必须在新的迁移脚本中同步加入 `gmd_faq_menu`，前端使用 `v-permission`。跨服务配置或注册变更通过 `MessagePublisher` 广播，不能直接调用其他服务。
6. **调度器**：修改任务状态或删除任务时先操作 Quartz，再落库；删除和停止都要按任务 ID 清理触发器，不能用数据库的 `enabled` 状态提前返回。
7. **账号密码**：客户端传入的是明文密码 MD5，服务端按“账号 ID + 客户端摘要”再次摘要后入库；服务端随机密码也必须先做客户端摘要。建号或重置后的强制改密状态不能绕过。

## 数据库迁移

- `sql/db_init.sql` 是 Flyway V1 基线，不是最新结构；V2 及以后只放在 console 的 [db/migration](GMD-FAQ-RUNNER-CONSOLE/src/main/resources/db/migration/README.md)。`sql/incremental/` 仅作历史参考，不作为升级入口。
- 已应用迁移禁止修改；新增迁移必须幂等，同时兼容存量库和新库。检查表或列不存在时用 `information_schema` 配合动态 SQL，不能假设对象一定存在。
- 不用 `DELETE + INSERT` 重建业务资源，避免主键关联悬挂；迁移中拼接 `BIGINT` 主键时检查溢出。
- 修改基线或迁移后，应在临时库顺序执行迁移，并对比 `information_schema.columns` 与索引结构。

## 组织、资源与智能体权限

### 组织与角色

- 部门树就是组织树，账号的 `dept_id` 是归属；组织成员包含本部门及所有子部门账号。
- 鉴权只看“角色 → 菜单权限”，`account_tenant.role` 仅作身份展示。平台角色和本人部门及祖先部门的角色生效；上级可管理下级，反向不成立。
- 管理员（拥有 `system:admin`）可看、可改全部资源。部门负责人可查看本部门及子部门的所有资源；普通成员按资源可见性、组织范围及账号/部门授权查看，维护资格按下述资产归属规则判断。
- 把账号加入部门、修改成员或设置负责人前必须经过 `MemberScopeGuard`；负责人列表中的每个账号也要单独校验管理范围。账号统一由管理员创建，取消自助注册和加入组织申请。

### 五类资源

智能体、工具、技能、知识库、MCP 是唯一实现 `OwnedResource` 的实体，只有这五张表声明资源归属、可见性和维护人字段；禁止把这些字段提到 `BaseTenantEntity`。

- 创建资源时客户端只能选择个人、部门或平台空间，服务端校验目标空间并写入 `ownership_type`、`owner_id`、`owning_dept_id` 和 `maintainer_id`，忽略请求体的归属字段。个人资源默认私有，部门资源默认部门内部可用，平台资源仅管理员创建并默认公开。
- 归属、维护和使用范围独立：个人资产由归属人维护，部门资产由指定维护人及所属部门管理范围内的负责人维护，平台资产由管理员或指定维护人维护；各操作仍需资源域权限。共享不取消维护资格。部门内部资源覆盖所属部门及下级成员；扩大共享后按部门上下级分支可用，兄弟部门需显式授权。个人私有资源仍允许管理范围内的负责人只读查看。
- `resource_dept_id` 固定资源创建时的部门，`owning_dept_id` 单独表示部门资产当前归属；账号后来换部门不会移动旧资源。空间筛选只收窄可访问列表，不切换租户、角色、账号部门或会话身份。移交保留主键、创建记录及显式授权；部门资产由负责人或管理员移交，非管理员不能转为个人资产，有部门资产时禁止删除部门。账号授权保存在 `ResourceAccessGrant`，部门授权保存在 `ResourceDepartmentGrant`；两者只授予“查看”或“查看并使用”，不授予编辑权。部门授权按当前部门树覆盖下级成员，账号授权不因换部门而失效。
- 所有改、删、启用、同步和修改可见性的 Service 方法共用 `ResourceOwnershipSupport` 管理资格；移交和可见性变更锁定当前归属后再写入，普通编辑不得更新归属字段；列表可见不等于可编辑。列表查询统一经过 `resourceVisibilityService.applyScope`。
- 普通成员运行智能体还必须拥有使用权，且智能体已启用/发布。`published`、资源可见性和 `external_share_enabled` 独立判断；游客只能通过绑定智能体的 ChatKey 使用，管理员关闭外放或轮换密钥后旧令牌应失效。
- 发布的智能体不另建“访问组”：组织内使用资源可见范围与账号/部门授权，组织外使用独立的 ChatKey 外放开关。

- 无登录账号上下文的对外 MCP 工具列表与调用均只允许 `PLATFORM` 且 `TENANT` 的资源，不能再用 `owner_id IS NULL` 判断公共池。

### 会话与文件

- 会话列表默认只查当前账号；负责人使用团队范围只读，管理员才可查全部。查询服务不能信任客户端传入的 `userId`。
- 游客身份以 `chat_session.user_id` 不存在于 `gmd_faq_account` 判定，不能用 `gmd_faq_account_tenant`；后者会误把正常账号判成游客。`gmd_faq_account` 没有 `tenant_id`，子查询不得引用该列。
- 游客会话清理接口必须要求 `agent:chat:guest:manage`，并二次确认目标确实是游客；自动清理默认关闭，启用后逐租户切换上下文再删。
- 会话文件管理复用工作区文件树和预览器，不能另写一套渲染器。删除运行中的会话前由前端先调用 runtime 停止接口；后端删除接口负责权限校验和工作区清理。

## AgentScope 运行时

- 状态按 `AgentStateScope` 中的租户、账号及会话隔离；身份来自登录上下文或服务端会话，不能信任客户端 `userId`。读取 1.x 裸会话键前校验归属，新状态存在时不再回退旧状态。
- 每次运行重新应用工具权限和计划开关；拒绝规则仅限本轮。审批续跑使用服务端持久化的待确认工具参数和暂停时的开关，客户端只提交决策；`{}` 是合法的无参数工具输入。审批不依赖工具详情展示或记忆开关。
- 记忆、任务清单和工具调用展示统一由智能体设置决定，对话页和预览不提供本地覆盖开关；计划模式入口显示使用独立的 `uiConfig.showPlanMode`，隐藏入口不能取消已有方案审批或只读限制。实际模型用量展示由 `uiConfig.showModelUsage` 决定，缺省保持显示；隐藏入口不能停止服务端用量记录。
- 关闭记忆使用临时状态，不读取或覆盖已有持久历史；续跑沿用暂停时的状态槽位。清空/删除会话同时清理父、子智能体的持久、临时及旧版状态。
- 子智能体沿用父用户身份并使用独立状态；需要人工审批或浏览器表单的操作由主智能体发起。预览审批归属只在当前 runtime 进程保存一小时，不能当作跨节点持久会话。
- 计划使用 `todo_write` 和完整任务列表，保留最大子任务数限制。压缩只维护 `maxToken`、`tokenRatio`、`msgThreshold`、`lastKeep` 四项摘要参数；摘要失败或未缩短上下文时保留原历史，不恢复已停用的输出卸载/裁剪配置。
- 调整引擎扩展时同时检查 Middleware、旧 Hook 兼容层和 Runtime 中覆盖的 `io/agentscope/` 类，不能只更新 SDK 依赖。响应式/异步调用通过 `AgentContextPropagation` 传播租户和用户上下文。
- 工具图片保存为当前会话的不可变副本，使用既有工作区归属和容量校验；图片优先在正文引用处显示，未引用的图片与文件产物跟随来源工具步骤，不能固定汇总到轮次末尾。工具结果持久化产物关联，图片展示与预览复用统一组件；流式文字更新不应重复下载相同缩略图，文件通知恢复后需补查当前会话。
- 补充要求只授予本人正式会话的当前运行，必须核对服务端身份、活动 `runId` 与独立操作权限；在下一推理点按序注入。交接未确认的消息不得自动重放；清空/删除会话同时清理收件箱，停止和审批取消未应用消息。
- 已落地的 2.0 运行能力直接进入正常构建路径，不再添加部署灰度开关；模型容错按智能体配置生效。
- 普通发送、审批续跑和队列调度在读取/修改 SDK 状态前共用数据库会话门闩；队列执行前重新鉴权，不保存登录令牌。停止暂停队列，审批等待续跑；租约失效只标记中断，不重放已开始消息。
- 观测用量只来自供应商响应，缺失保持未知，流式累计快照不得逐块相加；主模型、压缩和子智能体分别记录。观测中不写提示词、工具参数、文件正文或异常消息，观测失败不能影响业务执行。
- 原生技能仓库只提供智能体绑定且启用的只读依赖，不与旧 SkillHook 同时加载，不自动上传文件；仓库身份不匹配必须拒绝。子智能体技能不能重新引入审批工具。内置 Hook 迁移要验证旧 PostActing 的实际完成边界，文件通知不得先于产物和日志处理。
- 模型容错配置独立于采样参数覆盖，只有管理员可修改；运行时再次校验模型租户、状态和兼容性。重试限于首个响应前的模型调用边界，不重跑智能体或工具；观测按实际尝试记录。摘要模型不替换主模型，失败仍保留历史。
- 原生 Plan Mode 与任务清单开关独立；首次推理前由服务端进入，模型工具清单和逐工具执行都限制未知及写工具。只读资格绑定服务端工具实例，MCP 提示或同名工具不能提升权限。方案批准绑定持久版本、内容摘要和待确认调用，不随记忆开关丢弃；子智能体不能绕过父级只读限制，程序退出不能代替审批。
- 工具装饰器统一委托元数据、权限自检、规则匹配及建议；直接返回只适用于实际成功的整批工具，不能绕过审批或方案限制。动态工具异常必须返回错误状态。
- 直接返回正文只保存一次，结束事件须在状态与聊天日志提交后发出；图片卡片保留真实工具输出，后续推理以视觉输入回放图片，不能把 SDK 工具占位当作实际结果或改写持久历史。
- 工具确认要求绑定当前智能体的实际工具实例，原生审批事件决定待确认列表；禁止通过全局工具名集合保存确认状态，防止同名工具跨智能体或租户串用规则。
- 模型协议来自模型配置，主、备用、摘要及连通检测使用同一构建入口。Responses 独立映射支持的参数，固定 `store=false`，禁止 `previous_response_id`；重试由模型容错层统一计数，不能叠加供应商内部重试。
- Engine 覆盖的 Responses 类兼容 2.0.4 缺失可选用量明细及无状态推理回放：总用量缺失保持未知，显式请求加密推理；没有加密内容的旧推理摘要只从供应商输入中略去，不修改持久历史。SDK 升级时同时验证流式、非流式和实际打包后的类加载顺序，再决定是否移除覆盖。

## 前端高风险约定

- 系统设置相关类型只放 `ui/src/types/system.ts`，不要从 `ui/src/types/index.ts` 再导出，避免与遗留 `Role` 枚举冲突。
- Agent Studio 复用既有 `AgentForm*` 字段和校验，不在编排页复制；加载完成后保存基线，用值比较判断脏状态，不要用深度 `watch` 直接置脏。
- `AgentResourcePicker` 的草稿选择集用 `watch(..., { immediate: true })` 初始化；异步候选加载完成要通知父级重算概览。
- 开场白和推荐提问统一来自 `uiConfig.openingMessage` / `openingQuestions`；推荐提问只在空会话的 `Welcome` 标题介绍区渲染，发送消息后不再展示，不能放回输入框。正式对话和工作台预览遵循同一规则。
- 资源卡片的改删和可见性入口使用 `isResourceManageable`；资源归属、维护能力和 `canTransfer` 由资源可见性接口补读。
- 前端部署在 `/web/` 的 hash 路由下，退出登录使用 `router.replace(RoutePaths.LOGIN)`，不要跳到根路径 `/login`。

## 本机 Docker 与排障

- 本机端口映射和内存设置以 `docker/.env` 为准；不要用会覆盖本地配置的 `start-*.sh`。源码变更后必须带 `--build`。
- 重建 console、runtime 或 gateway 后重启 `gmd-faq-frontend`，否则 Nginx 可能缓存旧 upstream IP。Windows/macOS Docker Desktop 无法验证 gateway 的 host 网络模式。
- `Unknown column ... tenant_id`：检查租户拦截器、白名单和 CTE；`Unknown column owner_id`：确认字段没有上提到基类；`Invalid bound statement` 或 `SAXParseException`：检查 Mapper XML 路径、声明和 DOCTYPE；Flyway checksum mismatch：恢复已发布脚本并新增迁移。

## 边界

- 不恢复已删除的自助注册、加入组织申请、多组织切换或独立“全部账户”页面。
- 不新增界面勾不到、代码也不校验的权限点；不修改已发布迁移，不把 V2+ 结构回写 V1 基线。
- 用户可见行为变更要同步对应产品文档；本文件只保留开发约束和稳定不变量。
