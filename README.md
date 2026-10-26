# 顶火智能体平台

源码仓库：[baibaiyu1/baibaiyu](https://github.com/baibaiyu1/baibaiyu)。

面向企业内网的多租户 AI 智能体平台。提供智能体定义与运行、MCP 工具接入、知识库与 RAG、可视化工作流、定时任务、API 服务网关与数据看板，覆盖从配置到发布的完整链路。

- **后端** — Java 21 · Spring Boot 3.4.9 · AgentScope 2.0.4 · MyBatis-Plus 3.5.7，Maven 多模块
- **前端** — Vue 3.5 · Ant Design Vue 4 · Vite 7 · Pinia · Vue Flow · ECharts
- **存储** — MySQL 8（`gmd_faq_server`）· Redis 7（db 7）· pgvector (PG 16)
- **部署** — Docker Compose，6 个 Runner 服务 + 前端 Nginx

> 后端模块沿用 `GMD-FAQ-*` 前缀。

---

## 快速开始

前置条件：Docker Engine 20+ 与 Docker Compose v2+。

```bash
cd docker
cp .env.simple .env                    # 首次部署
docker compose -f docker-compose-simple.yml up -d --build
docker compose -f docker-compose-simple.yml ps
```

首次启动前，替换 `docker/.env` 中的 `JWT_SECRET` 占位值。可执行 `node -e "console.log(require('node:crypto').randomBytes(32).toString('base64'))"` 生成随机密钥，Console、Runtime、Gateway 和 WebSocket 使用相同的值。

启动后访问 `http://127.0.0.1/web/`（端口以 `.env` 中的 `FRONTEND_PORT` 为准），默认账号 `admin / Admin@123.com`。单机版共 9 个容器：3 个中间件、5 个后端服务和前端 Nginx。

管理脚本 `docker/start-simple.sh <build|rebuild|start|stop|restart|down|status>` 是上述命令的封装，但它启动前会用 `.env.simple` **覆盖** `.env`。若你已在 `.env` 中做过端口重映射或内存预算调整，请直接使用上面的 compose 命令，或在 Windows / Docker Desktop 上按 [docker/README.md](docker/README.md#windows--docker-desktop-宿主机) 的说明执行。

常用维护命令：

```bash
docker compose -f docker-compose-simple.yml logs -f gmd-faq-console   # 排查后端异常
docker compose -f docker-compose-simple.yml restart gmd-faq-frontend  # 重建 console / runtime / gateway 后必须执行
docker compose -f docker-compose-simple.yml down                      # 停止并删除容器
```

> 重建后端容器后一定要重启 `gmd-faq-frontend`：Nginx 仅在容器启动时解析一次 upstream 主机名，否则前端返回 502。

---

## 本地开发

### 环境要求

| 依赖 | 版本 | 用途 |
|------|------|------|
| JDK | 21+ | 后端编译与运行 |
| Maven | 3.8+ | 后端构建 |
| MySQL | 8.0+ | 主数据库 |
| Redis | 7+ | 缓存、分布式锁、消息广播 |
| PostgreSQL + pgvector | 16 | 向量库（`VECTOR_STORE_TYPE=pgvector` 时） |
| Node.js | 20.19+ / 22.12+ | 前端构建 |
| pnpm | 9+ | 前端包管理 |

### 1. 初始化数据库

```bash
mysql -u root -p -e "CREATE DATABASE gmd_faq_server DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
mysql -u root -p gmd_faq_server < sql/db_init.sql
```

`sql/db_init.sql` 只是 Flyway 的 V1 基线（首次建库用）。后续所有表结构变更由 console 启动时的 Flyway 增量脚本接管（`GMD-FAQ-RUNNER-CONSOLE/src/main/resources/db/migration/V2__*.sql` 起），无需手工执行 SQL。**已发布的迁移脚本不可修改**（checksum 校验），新增变更请加新版本号文件。

### 2. 配置后端

各 Runner 模块的本地配置为 `application-dev.yml`，仓库内提供了 `application-dev.sample.yml` 模板：

```bash
cp GMD-FAQ-RUNNER-CONSOLE/src/main/resources/application-dev.sample.yml \
   GMD-FAQ-RUNNER-CONSOLE/src/main/resources/application-dev.yml
```

按同样方式处理需要启动的模块，并填入本地 MySQL、Redis 连接信息。

### 3. 启动后端服务

| 顺序 | 模块 | 启动类 | 端口 | 说明 |
|------|------|--------|------|------|
| 1 | `GMD-FAQ-RUNNER-CONSOLE` | `ConsoleApplication` | 3060 | 必需，管理控制台与控制面 |
| 2 | `GMD-FAQ-RUNNER-RUNTIME` | `RuntimeApplication` | 3061 | 必需，AI 运行时 / AG-UI 端点 |
| 3 | `GMD-FAQ-RUNNER-PROXY` | `ProxyApplication` | 3062 | 需要 Shell 沙箱执行时 |
| 4 | `GMD-FAQ-RUNNER-WEBSOCKET` | `WebsocketApplication` | 3064 | 需要实时消息推送时 |
| 5 | `GMD-FAQ-RUNNER-GATEWAY` | `GatewayApplication` | 动态端口 | 需要 API 服务网关时（依赖宿主机网络） |

`GMD-FAQ-RUNNER-FILE` 只在多节点本地存储模式中同步技能和会话工作区快照，本地开发和共享存储模式无需启动。

### 4. 启动前端

```bash
cd ui
pnpm install
pnpm dev
```

开发服务器为 `http://localhost:3030`，Vite 已配置代理：`/api` → 3060、`/api/runtime/` → 3061、`/api/ws/` → 3064。

```bash
pnpm type-check   # vue-tsc
pnpm lint         # oxlint + eslint
pnpm build:main   # 主应用产物 dist-main
```

---

## 系统架构

服务按职责拆分，Runtime 一侧可横向扩容：

| 模块 | 职责 | 扩容 |
|------|------|------|
| Console | 管理控制台、网关控制面、心跳中心 | 单实例 |
| Runtime | AI 推理运行时、AG-UI 协议端点 | 弹性扩容 |
| Proxy | Shell 命令沙箱执行 | 随 Runtime |
| File | 技能与会话工作区快照同步（多节点本地存储模式） | 随 Runtime |
| Gateway | API 服务网关数据面，把工作流暴露为 HTTP API | 随 Runtime，需宿主机网络 |
| WebSocket | 实时消息推送、Redis Pub/Sub 集群 | 单实例 |

请求入口为前端 Nginx（80），按路径分发到 Console 与 Runtime；各服务经 Redis Pub/Sub 广播配置变更，通过心跳上报维持节点注册表。单机版中间件与后端同机部署，生产建议拆分为中间件、控制台、执行节点三类服务器，详见 [docker/README.md](docker/README.md)。

---

## 核心能力

| 能力 | 说明 |
|------|------|
| 智能体引擎 | ReAct 循环、`todo_write` 任务清单、工具审批、子智能体协作；状态按租户、账号和会话隔离 |
| 模型接入 | OpenAI · DashScope · Anthropic · Gemini · Ollama · OrcaRouter，支持 Agent 级参数覆盖 |
| MCP 集成 | HTTP / SSE / STDIO 三协议，注册期只绑定 Schema，首次调用建连，连续失败自动降级 |
| 工具与技能 | 内置工具 · Groovy 动态工具 · Agent-as-Tool · 技能包（VEP / APIP 内置协议） |
| 知识库与 RAG | 百炼 / Dify / RagFlow / 本地 RAG；向量库支持 PgVector / Milvus / Elasticsearch / Qdrant / Weaviate，改 `VECTOR_STORE_TYPE` 切换 |
| 记忆 | 按租户、账号与会话保存上下文，支持历史摘要压缩；长期记忆 Mem0 / ReMe / Bailian，异步写入不阻塞主流程 |
| 可视化工作流 | 30+ 节点类型，Vue Flow 画布、常量/变量/节点输出/Groovy 表达式四种数据来源、节点级执行轨迹 |
| 定时任务 | 智能体与工作流双目标，Cron 可视化配置、手动触发、执行记录；Quartz + Redis 分布式锁保证集群单次执行 |
| API 服务网关 | 应用独占监听端口，工作流一键发布为 HTTP API，动态路由、统一鉴权、IP 白名单、限流与访问日志 |
| 数据看板 | 拖拽式看板设计器，20+ 面板类型，SQL / HTTP 双通道数据集；数据集 SQL 由平台强制注入租户条件 |
| 安全与隔离 | Shell 走独立 Proxy 容器（`cap_drop` + `read_only` + `pids_limit`）；Python / Node.js / Shell / HTML 脚本安全扫描 |
| 多租户 | MyBatis-Plus 租户拦截器自动注入隔离条件；角色 → 菜单权限点单轨鉴权，数据范围按部门收敛 |

部分能力的图文说明随前端发布，登录后在产品内置文档（`ui/src/doc/content/`）中查看。

---

## 项目结构

```
├── GMD-FAQ-COMMON-BASE/      # 枚举、常量、工具类、加密
├── GMD-FAQ-COMMON/           # Entity / DTO / VO / Wrapper
├── GMD-FAQ-BIZ/              # 业务模块（23 个，按领域扁平拆分：agent / mcp / skill / tool /
│                             #   workflow / gateway / dashboard / knowledge / model / account ...）
├── GMD-FAQ-ENGINE/           # 引擎层：agent、agui、model、tool、skill、mcp、memory、rag、
│                             #   hook、security、workspace、mpatch ...
├── GMD-FAQ-WORKFLOW/         # 工作流引擎：30+ 节点实现与运行时
├── GMD-FAQ-GATEWAY/          # 网关数据面：Vert.x 动态路由、鉴权、白名单、限流
├── GMD-FAQ-SCHEDULER/        # 调度层：Quartz + 分布式锁
├── GMD-FAQ-HEARTBEAT/        # 心跳监控与节点注册表
├── GMD-FAQ-RUNNER-CONSOLE/   # 应用：管理控制台（含 db/migration 迁移脚本）
├── GMD-FAQ-RUNNER-RUNTIME/   # 应用：AI 运行时 + AG-UI 端点
├── GMD-FAQ-RUNNER-PROXY/     # 应用：Shell 沙箱
├── GMD-FAQ-RUNNER-FILE/      # 应用：多节点本地存储快照同步
├── GMD-FAQ-RUNNER-GATEWAY/   # 应用：网关数据面
├── GMD-FAQ-RUNNER-WEBSOCKET/ # 应用：WebSocket 推送
├── ui/                       # 前端：Vue 3 管理界面与产品内置文档
├── docker/                   # 部署：Compose 编排、镜像构建、Nginx
└── sql/                      # 数据库初始化脚本（V1 基线）
```

新增代码请遵循 [AGENTS.md](AGENTS.md) 中的约定：表名统一走 `TableConst`，Java 源码目录沿用 `src/main/java/com/faq/...`（包名为 `com.gmd.faq.server.main...`），Mapper XML 放在 `mapper/` 下且必须带 XML 声明与 DOCTYPE。

---

## 生产部署

`docker/` 下提供按角色拆分的编排文件，各服务器首次从对应 `.env.*` 模板准备自己的 `.env`，配置中间件地址、节点标识和存储路径后启动：

```bash
cd docker
# 在对应角色服务器执行
docker compose -f docker-compose-middleware.yml up -d
docker compose -f docker-compose-console.yml up -d --build
docker compose -f docker-compose-execute.yml up -d --build
```

- Runtime 建议至少 2 实例；MySQL、Redis 与向量库建议使用托管服务。
- 网关数据面依赖 `network_mode: host` 直接监听动态端口，仅支持 Linux 宿主机。
- 多节点共享存储与文件同步方案见 [共享存储指南](docker/shared-storage.md)，节点标识、local-storage profile 和完整变量见 [Docker 指南](docker/README.md)。

## 版本升级

当前使用 AgentScope Java 2.0.4。升级先启动 console 完成全部 Flyway 迁移（当前至 V36），再更新 runtime 及前端；具体命令与恢复边界见 [部署升级步骤](docker/README.md#版本升级)。新部署的工作区、技能与文档解析临时文件统一保存在 `.baibaiyu/` 目录。旧会话首次读取时自动转换，保留消息、待审批工具和计划；任务清单使用 `todo_write`，记忆压缩仅保留四项摘要配置。

运行中补充要求、下一轮队列、计划模式、审批续跑及实际用量见 [对话手册](ui/src/doc/content/chat/index.md)，重试、备用和摘要模型见 [智能体手册](ui/src/doc/content/agent/index.md)，直接返回和 Responses 见 [工具手册](ui/src/doc/content/tool/index.md) 与 [模型手册](ui/src/doc/content/model/index.md)。业务由 Console 管理、Runtime 执行；SDK 的外部执行后端、渠道和云沙箱需另行配置对应服务，具体配置见 [Docker 指南](docker/README.md)。

## 验证

按改动选择验证，完整约束见 [AGENTS.md](AGENTS.md)。以下命令在仓库根目录执行：

```bash
mvn -DskipTests package
mvn -pl GMD-FAQ-RUNNER-CONSOLE -am -DskipTests package
mvn -pl GMD-FAQ-ENGINE test
mvn -pl GMD-FAQ-RUNNER-RUNTIME test
pnpm -C ui type-check
pnpm -C ui build:main
```

Engine / Runtime 测试需要先构建或安装相关依赖。聊天流、工具图片、文件产物分别使用 `pnpm -C ui test:chat-stream`、`pnpm -C ui test:tool-result-images`、`pnpm -C ui test:chat-artifacts`。数据库变更须在临时库验证迁移；接口联调先登录，再携带 `Authorization: Bearer ...`。

```bash
node scripts/validate-docs-links.mjs
node scripts/validate-docs-mermaid.mjs
pnpm -C ui build:doc
git diff --check
```

文档脚本默认检查产品手册，检查其他文档时加 `--docs <文件或目录>`。

---

## 参与开发

1. 从目标分支创建工作分支；Codex 创建的分支默认使用 `codex/` 前缀。
2. 修改前检查已有差异，提交前按影响范围完成上面的验证。后端注释沿用现有 Javadoc 风格，前端遵循仓库 ESLint 配置。
3. 提交信息使用简短中文说明，不加 `feat`、`fix` 等类型前缀；无关改动分开提交。
4. 合并请求说明具体问题、最终行为和实际验证结果。用户可见行为同步产品手册，部署和存储细节分别维护在对应专题文档。

## 源码交付

需要 Python 3.9+ 和 Git。在仓库根目录执行：

```bash
python scripts/export-source.py
```

脚本将当前工作区（包括未提交的修改和未被忽略的新源码）导出为 `output/baibaiyu-source-<时间>.zip`。导出包包含构建文件、部署模板、数据库迁移和许可证，排除 `.git`、本地 `.env`、`application-dev.yml`、依赖缓存、数据目录与构建产物。也可用 `--output <新文件路径>` 指定 ZIP 路径；脚本不覆盖已有文件。

网站品牌集中维护在 `ui/src/config/brand.ts`，共用组件使用 `App` 前缀。工作区目录使用 `.baibaiyu/`，技能来源标识为 `baibaiyu`，Weaviate 默认类名前缀为 `BaibaiyuRag`；已发布数据库迁移保留原文以维持校验一致性。主应用、文档应用和后端 JAR 构建会附带相应许可文件，源码交付时也须保留这些文件。

---

## 相关文档

| 文档 | 内容 |
|------|------|
| [AGENTS.md](AGENTS.md) | 开发约束、权限与状态不变量、验证要求 |
| [docker/README.md](docker/README.md) | 部署架构、单机 / 多节点编排、环境变量参考 |
| [docker/shared-storage.md](docker/shared-storage.md) | 多节点 NFSv4、权限与文件同步边界 |
| [数据库迁移指南](GMD-FAQ-RUNNER-CONSOLE/src/main/resources/db/migration/README.md) | V1 基线、增量迁移与临时库验证 |
| [构建手册](ui/src/doc/content/build-doc/index.md) | 构建产物、服务分工与部署入口 |
| [智能体手册](ui/src/doc/content/agent/index.md) / [对话手册](ui/src/doc/content/chat/index.md) | 配置、发布、记忆、审批、图片和文件 |
| `ui/src/doc/content/` | 其余产品手册，随前端 doc 应用发布 |
