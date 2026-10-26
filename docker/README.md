# GMD-FAQ Docker 部署指南

本指南维护 Compose 部署、升级顺序和环境变量。源码构建见 [项目 README](../README.md)，多节点文件存储见 [共享存储指南](shared-storage.md)，智能体使用见 [产品手册](../ui/src/doc/content/agent/index.md)。

## 架构概览

```
┌───────────────────────────────────────────────────────────────────────┐
│                          控制台节点 (Server 1)                          │
│  ┌─────────────────┐  ┌─────────────────┐  ┌───────────────────────┐ │
│  │ runner-console   │  │ runner-websocket │  │ frontend (Nginx)      │ │
│  │ :3060            │  │ :3064            │  │ :80                   │ │
│  │ 管理控制台        │  │ WebSocket 推送    │  │  /api/runtime → runtime│ │
│  └─────────────────┘  └─────────────────┘  │  /api/ws      → ws     │ │
│                                             │  /api/agent   → console│ │
│                                             │  /api/*       → console│ │
│                                             │  /runtime/agui→ runtime│ │
│                                             └───────────────────────┘ │
└───────────────────────────────────────────────────────────────────────┘
                            │                        ▲
                  MySQL/Redis│                        │ 心跳上报
                            │                        │
┌───────────────────────────────────────────────────────────────────────┐
│                          执行节点 (Server 2+)                          │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐       │
│  │ runner-runtime   │  │ runner-proxy    │  │ runner-file     │       │
│  │ :3061            │─►│ :3062           │  │ (web disabled)  │       │
│  │ AI 运行时         │  │ Shell 执行代理   │  │ 文件同步服务     │       │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘       │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ runner-gateway（host 网络）                                   │  │
│  │ 用户动态创建的 API 端口直接监听本机宿主机                      │  │
│  └───────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────┘
                            │
                  MySQL/Redis/pgvector
                            │
┌───────────────────────────────────────────────────────────────────────┐
│                          中间件服务器 (Server 3)                       │
│  ┌──────────┐  ┌──────────┐  ┌──────────────┐                        │
│  │ MySQL 8  │  │ Redis 7  │  │ pgvector pg16│                        │
│  │ :3306    │  │ :6379    │  │ :5432        │                        │
│  └──────────┘  └──────────┘  └──────────────┘                        │
└───────────────────────────────────────────────────────────────────────┘
```

> 图中 `runner-xxx` 为服务短名，对应的 Maven 模块为 `GMD-FAQ-RUNNER-XXX`（例如 `runner-console` → `GMD-FAQ-RUNNER-CONSOLE`）。

### Nginx 路由规则

前端 Nginx 容器通过 `envsubst` 在启动时替换 `nginx.conf` 中的地址变量，支持多节点动态路由：

| 路径 | 后端服务 | 说明 |
|------|---------|------|
| `/web/` | 静态文件 | 前端主应用 |
| `/api/runtime/` | GMD-FAQ-RUNNER-RUNTIME | AI 运行时 API |
| `/api/ws/` | GMD-FAQ-RUNNER-WEBSOCKET | WebSocket 推送 |
| `/api/agent/` | GMD-FAQ-RUNNER-CONSOLE | 智能体管理 API |
| `/api/` | GMD-FAQ-RUNNER-CONSOLE | 其他管理 API（兜底） |
| `/runtime/agui/` | GMD-FAQ-RUNNER-RUNTIME | AG-UI 流式协议 |
| `/web/api/*` | 同上兼容路径 | 兼容旧版前端请求 |

## 快速开始（单机体验版）

所有服务部署在同一台主机，适合体验和本机联调。端口、密码、内存及数据路径以当前主机的 `docker/.env` 为准。

```bash
cd docker

# 仅首次初始化、且 .env 不存在时复制；随后编辑端口与资源预算
cp .env.simple .env
docker compose -f docker-compose-simple.yml up -d --build
docker compose -f docker-compose-simple.yml ps
docker compose -f docker-compose-simple.yml logs --tail 100 gmd-faq-console
```

启动后访问 `http://127.0.0.1/web/`，若调整了 `FRONTEND_PORT`，使用对应端口。默认账号为 `admin / Admin@123.com`，首次登录按提示修改密码。

`start-*.sh` 会将对应模板复制到 `.env`；内容不同时先备份为 `.env.bak`，仍会覆盖定制值。已配置的环境直接使用 Compose，更新源码时带 `--build`。

### Windows / Docker Desktop 宿主机

PowerShell 可逐行执行上述 Compose 命令，无需 Bash。若宿主机已有 MySQL、Redis 或 PostgreSQL，应在 `.env` 调整中间件的宿主机映射端口；容器之间继续使用容器名和原生端口。根据 Docker Desktop 的实际内存配额调整各服务预算。

常用维护命令：

```bash
cd docker

docker compose -f docker-compose-simple.yml logs -f gmd-faq-console   # 跟踪 console 启动日志
docker compose -f docker-compose-simple.yml restart gmd-faq-frontend  # 重建 console/runtime/gateway 后必须执行
docker compose -f docker-compose-simple.yml down            # 停止并删除容器（保留 ./data 数据卷目录）
```

| 访问入口 | 地址与配置 |
|----------|------|
| 前端 | `http://127.0.0.1/web/`（账号 `admin / Admin@123.com`） |
| 后端 API | `http://127.0.0.1/api/`；console `:3060`、runtime `:3061`、proxy `:3062`、websocket `:3064` 也已映射到宿主机 |
| MySQL / Redis / PG | 宿主机端口以 `.env` 的映射为准；容器互访仍用 `3306` / `6379` / `5432` |
| 网关数据面 | 由网关应用自行监听动态端口，`network_mode: host` 下直接占用宿主机端口 |

> - **改了代码必须带 `--build`**：`up -d` 只在镜像不存在时构建，镜像已存在会直接复用旧镜像。
> - `.env` 不存在时先复制模板，再调整端口与内存；不要反复复制覆盖。
> - **网关（`gmd-faq-gateway`）依赖 `network_mode: host`**，Windows / macOS 的 Docker Desktop 不作为其支持环境，该容器不保证正常启动；验证网关请以 Linux 为准。
> - 前端出现 **502** 时，先执行上面的 `restart gmd-faq-frontend`：Nginx 只在容器启动时解析一次 upstream 主机名。

**包含的服务**（9 个容器）：

- MySQL 8.0 + Redis 7 + pgvector (pg16)
- GMD-FAQ-RUNNER-CONSOLE（管理控制台）
- GMD-FAQ-RUNNER-RUNTIME（AI 运行时）
- GMD-FAQ-RUNNER-PROXY（Shell 执行代理）
- GMD-FAQ-RUNNER-GATEWAY（网关数据面，host 网络，动态暴露工作流 API 端口）
- GMD-FAQ-RUNNER-WEBSOCKET（WebSocket 推送服务）
- frontend（Nginx 前端）

**不包含** GMD-FAQ-RUNNER-FILE（单机模式无需文件同步）

### 外挂 LightRAG 知识库

LightRAG 独立部署，平台通过 Runtime 请求其 `/query/data` 检索接口，通过 Console 连接文档管理与图谱读取 API。平台知识库中填写基础服务地址与 `LIGHTRAG_API_KEY`（以 `X-API-Key` 发送）；服务须支持返回实体、关系、片段和引用的 `/query/data` 协议。

原生文档处理和图谱浏览组件的源码保存在平台 `ui/lightrag-management/`，由 `pnpm -C ui build:main` 与主应用一起构建，现有前端 Dockerfile 会将页面发布到 `/web/lightrag-management/index.html`。知识库管理页通过本地 iframe 隔离 React 与 Vue 的样式，HTML、脚本和样式均由平台提供。LightRAG 只需提供后端 API，可关闭其 WebUI，无需构建或部署额外的 `platform.html`。源码来源和更新方式见[管理页集成说明](../ui/lightrag-management/README.md)。

- 同一容器网络内，可使用 `http://lightrag:9621` 等实际服务名。LightRAG 不包含在平台的 Compose 服务列表中，需另行部署并连接网络。
- Console 与 Runtime 都必须能够访问该基础地址。管理页通过 `/api/knowledge/config/{id}/lightrag/` 访问 Console，沿用平台登录 Cookie，每次请求重新校验知识库管理权限；无需为浏览器暴露 LightRAG 端口或设置 CORS。Console 仅连接 API，不代理远端页面和静态资源；只使用已保存的远端密钥，不转发平台登录令牌、Cookie 或工作区切换头。进入管理页先检查 `/auth/verify` 和 `/health`，配套 LightRAG 后端需支持这两个接口。
- Runtime 容器访问 Windows/macOS 宿主机服务，可使用 `http://host.docker.internal:9621`；LightRAG 的监听地址、端口发布和防火墙必须允许 Runtime 访问。仅发布到宿主机 `127.0.0.1` 的端口通常无法由其他容器访问。Linux 需按部署环境配置可达地址或 host-gateway 映射。
- 一个平台配置对应一个 LightRAG 实例启动时的工作区；多个隔离知识库使用不同实例地址。平台不会通过请求头切换工作区，同一工作区内的文档不能再用平台配置权限拆分。
- 配置单次请求超时时间和重试次数时，同时检查反向代理超时；默认单次 180 秒、不重试。检索关键词提取、嵌入及重排所用的模型在 LightRAG 配置。
- 文档管理代理不自动重试写请求。上传沿用 Console 的 multipart 限制（默认单文件 30 MB、单请求 100 MB）及前端 Nginx 的 `client_max_body_size`；需要更大文档时同步调整两者。图谱写入、检索对话、系统设置和其他 LightRAG API 不通过管理代理开放。
- 知识库图片需要 LightRAG 的 `/query/data` 支持 `include_images=true` 并返回 `data.images`（Base64、MIME、caption 和来源）；平台与 LightRAG 均须升级。图片读取只使用本次命中文档的既有 sidecar，不新增检索或图片分析请求；旧版本仅返回文字和引用。代理响应体限制需容纳图片 Base64，LightRAG 默认图片原始总量上限 8 MiB。

升级先更新 Console，完成 `V35__lightrag_knowledge_type.sql`，再更新 Runtime 和前端；源码更新使用 `docker compose -f docker/docker-compose-simple.yml up -d --build`（仓库根目录执行），重建后重启前端以刷新 upstream。配置与检索行为见[知识库手册](../ui/src/doc/content/knowledge/index.md#lightrag-知识库)。

## 生产部署（多服务器）

推荐至少使用 3 台服务器，按角色分为：中间件服务器、控制台节点、执行节点。

### 步骤 1：部署中间件（Server 3）

```bash
cd docker

# 仅该服务器首次初始化时复制
cp .env.middleware .env
# 编辑 .env 修改数据库密码等配置
docker compose -f docker-compose-middleware.yml up -d
```

### 步骤 2：部署控制台节点（Server 1）

```bash
cd docker

# 仅该服务器首次初始化时复制
cp .env.console .env
# 编辑 .env：
#   MYSQL_HOST / REDIS_HOST → 中间件服务器 IP
#   RUNTIME_HOST → 执行节点服务器 IP
docker compose -f docker-compose-console.yml up -d --build
```

### 步骤 3：部署执行节点（Server 2+）

```bash
cd docker

# 仅该服务器首次初始化时复制
cp .env.execute .env
# 编辑 .env：
#   MYSQL_HOST / REDIS_HOST / PG_HOST → 中间件服务器 IP
#   CONSOLE_HOST → 控制台服务器 IP
#   GATEWAY_JAVA_HEAP_PERCENTAGE / GATEWAY_MEM_LIMIT / GATEWAY_CPU_LIMIT → 网关 runner 资源配置
#   GMD_FAQ_NODE_ID → 本节点唯一标识
#   GMD_FAQ_HOST_NAME / GMD_FAQ_HOST_IP → 本机主机名与可达 IP
docker compose -f docker-compose-execute.yml up -d --build
```

直接使用 Compose 时须在 `.env` 填写节点标识；同一主机的 runtime、proxy、gateway 和 file 使用同一 `GMD_FAQ_NODE_ID`，不同主机不能重复。共享存储使用默认 profile；本地存储设置 `GMD_FAQ_STORAGE_MODE=local`，并执行：

```bash
docker compose --profile local-storage -f docker-compose-execute.yml up -d --build
```

## 版本升级

更新前备份 MySQL 和 `DATA_PATH`，安排活动运行结束后再替换服务。AgentScope Java 1.x 升级到 2.0.4 时，先启动 console 完成全部 Flyway 迁移（当前至 V36），再更新 runtime 与前端。以下命令从仓库根目录执行，沿用已有 `docker/.env`：

```bash
docker compose -f docker/docker-compose-simple.yml up -d --no-deps --build gmd-faq-console
docker compose -f docker/docker-compose-simple.yml logs --tail 100 gmd-faq-console
# 确认 Flyway V36 成功后继续
docker compose -f docker/docker-compose-simple.yml up -d --no-deps --build gmd-faq-runtime gmd-faq-frontend
docker compose -f docker/docker-compose-simple.yml restart gmd-faq-frontend
docker compose -f docker/docker-compose-simple.yml logs --tail 100 gmd-faq-runtime
```

多节点部署按相同顺序更新 console 和各执行节点；gateway 中的工作流智能体执行也依赖 Engine，需同步重建。后端容器重建后重启前端，以刷新 Nginx upstream 地址。

运行中补充要求、实际用量和模型配置依次需要 V29、V30、V31。升级 Console 完成迁移后重建 Runtime 和前端即可使用，不再配置能力环境变量。角色需要“运行中补充要求”权限；用量查询需要“查看智能体”权限。操作见[对话手册](../ui/src/doc/content/chat/index.md)。多节点仍须将补充、停止和状态请求路由到活动 Runtime，持久状态不代表活动任务可迁移。

技能使用只读仓库加载智能体绑定且启用的技能；文件通知使用中间件，在图片、产物及聊天日志处理后广播。动态 Hook 和 Studio 保留兼容接口。管理员在智能体表单配置模型容错与独立摘要模型后立即按新配置构建运行实例，无需额外部署开关。重试只发生在首个模型响应前，不重放工具；详细语义见[智能体手册](../ui/src/doc/content/agent/index.md#模型容错与摘要模型)。

同会话消息排队需要 V32。数据库门闩保证普通发送、审批续跑和后台队列串行；每次调度重新校验账号、组织和资源权限，服务端不保存登录令牌。运行租约每 10 秒续期，45 秒失联后标记中断并暂停队列，已开始任务不自动重试。队列保持在会话请求路由的 Runtime；重启后通过会话的“继续未执行消息”重新认领，运行 SSE 仍不能跨节点回放。后台排队每节点最多同时占用 4 个会话。

方案模式直接在自定义智能体的输入框点击“先出方案”。方案通过既有工作区服务保存，批准记录保存在按身份隔离的 SDK 状态表中，不需要额外迁移或环境变量。升级时重建 Runtime、gateway 和前端；批准前限制写操作，方案版本或文件变化会使旧批准失效，关闭记忆也会保留方案控制状态。

工具直接返回与 OpenAI Responses 模型协议需要 V33。先更新 Console 完成迁移，再重建 Runtime、gateway 和前端。存量工具默认关闭直接返回，存量模型保持 Chat Completions；分别在工具、模型表单中选择，不需要重启服务。Responses 复用现有模型请求超时配置，测试连接限时 15 秒，历史由项目管理。

MCP 长描述需要 V34，将服务与工具的描述列扩为 `MEDIUMTEXT`。更新 Console 完成迁移后重建前端，即可填写超过 4000 字符的服务描述；此前因工具描述过长而连接失败的 MCP，可重新连接或刷新工具目录，完整描述会重新同步。

资源空间需要 V36，为智能体、工具、技能、知识库和 MCP 增加资产归属、所属部门和维护人字段。历史个人资产保留个人归属，原公共池转为平台资产。先更新 Console 完成迁移，再重建 Runtime 和前端；侧栏顶部可筛选个人、部门与平台空间，切换空间不会改变登录身份或租户。

- **数据库**：V28 为状态表增加 `BIGINT NOT NULL DEFAULT 1` 的 `version` 列；缺表时创建完整表，重复执行安全。迁移约定见 [数据库迁移指南](../GMD-FAQ-RUNNER-CONSOLE/src/main/resources/db/migration/README.md)。
- **旧会话**：首次读取时校验归属，再转换为按租户、账号和会话隔离的状态；保留消息 ID、时间、工具调用及计划。新状态存在后不再读取旧裸键。
- **暂停恢复**：真实会话的待审批状态可从 MySQL 恢复，续跑沿用暂停时的状态槽位、参数和计划开关。预览会话的审批归属只在当前 runtime 进程保存一小时，重启或切换实例后需重新开始。
- **运行边界**：MySQL 状态恢复不等于运行中的 Agent、SSE 连接或外部工具自动迁移。节点更换前应排空活动请求；多节点路由和缓存边界见下节。

计划、记忆及子智能体的操作变化见 [智能体手册](../ui/src/doc/content/agent/index.md)，审批、图片和产物见 [对话手册](../ui/src/doc/content/chat/index.md)。构建、回归和真实接口验证入口统一维护在 [README 验证章节](../README.md#验证)，SDK 变更可查 [官方 v2.0.4 发布记录](https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.0.4)。

## 多节点部署与存储模式

### 单机模式

使用 `docker-compose-simple.yml`，启动 console、runtime、proxy、gateway、websocket、frontend 和中间件。所有服务在同一台主机，`GMD-FAQ-RUNNER-FILE` 不影响单机 workspace 使用。

### 多节点共享存储模式（推荐）

控制台主机启动 `GMD-FAQ-RUNNER-CONSOLE`、`GMD-FAQ-RUNNER-WEBSOCKET` 和 frontend；每个执行主机启动 `GMD-FAQ-RUNNER-RUNTIME`、`GMD-FAQ-RUNNER-PROXY`、`GMD-FAQ-RUNNER-GATEWAY`，不启动 `GMD-FAQ-RUNNER-FILE`。所有主机将同一个 NFSv4 导出目录配置为 `DATA_PATH`，并设置 `GMD_FAQ_STORAGE_MODE=shared`。详细安装、权限和故障处理见 [shared-storage.md](shared-storage.md)。

多节点负载均衡由部署者维护：在控制台主机的 `docker/nginx/nginx.conf` 中手工增加 runtime upstream 节点，并使用以下一致性 Hash：

```nginx
map $http_x_gmd_faq_thread_id $runtime_hash_key {
    default $http_x_gmd_faq_thread_id;
    ""      $remote_addr;
}
upstream gmd_faq_runtime {
    hash $runtime_hash_key consistent;
    server 10.10.0.11:3061;
    server 10.10.0.12:3061;
}
```

所有前端请求和外部 AG-UI/API 调用都必须携带 `X-GMD-FAQ-Thread-Id`，值必须与请求体中的 `threadId` 一致。新增或移除 upstream 后需要校验并 reload Nginx；变更期间少量会话可能重新映射，正在执行的 SSE 连接不会迁移。

### 多节点本地存储模式

每个执行主机启动 `GMD-FAQ-RUNNER-RUNTIME`、`GMD-FAQ-RUNNER-PROXY`、`GMD-FAQ-RUNNER-GATEWAY` 和 `GMD-FAQ-RUNNER-FILE`，设置 `GMD_FAQ_STORAGE_MODE=local`，并使用不同的本地 `DATA_PATH`。runtime 在 Agent 流真正结束后按 workspace 进行 30 秒尾部防抖，顺序生成 ZIP 上传 console；console 完成原子落盘后通过 Redis 广播版本，其他节点按版本下载覆盖。新节点启动时会主动向 console 补齐快照。

本地模式不等价于实时共享文件系统：节点崩溃前尚未进入队列或尚未上传的变化可能丢失，且 Agent 执行过程中的内存状态仍是节点本地状态。因此仍需配置共享 MySQL/Redis、手工维护 Nginx upstream，并确保所有请求携带 `X-GMD-FAQ-Thread-Id`。同一套数据不要同时启用 NFS 和本地同步。

### Agent 缓存一致性边界

Agent 的上下文对象和 SSE/RunTracker 状态位于 runtime JVM 内存，无法在节点之间直接迁移。当前采用“threadId 一致性 Hash + Redis 版本校准”：Agent 成功持久化后递增 Redis 版本并广播，其他节点收到通知后淘汰本地 Agent；请求进入时还会读取 Redis 版本，因此 Redis Pub/Sub 丢消息不会永久使用旧缓存。该方案可以避免后续请求读取过期上下文，但不能保证节点切换瞬间已经在执行的两个请求自动合并，也不能在节点崩溃时无损续跑正在执行的 Agent。要做到严格线性一致，需要引入集中式 Agent 执行服务或跨节点锁并迁移运行态，成本和故障面显著增加，当前部署方案明确不承诺这两项能力。

### 节点运维顺序

新增执行节点：准备中间件连通性和 `DATA_PATH`，启动 execute compose，确认心跳卡片出现后再把 runtime 地址加入 Nginx upstream。下线节点：先从 upstream 删除或停用该节点，等待活动会话结束，再执行对应 Compose 的 `down`；本地模式同时带 `--profile local-storage`。节点恢复后先确认 NFS 挂载或本地快照同步完成，再恢复流量。

### 执行节点存储开关

`docker/.env.execute` 默认是 `GMD_FAQ_STORAGE_MODE=shared`。`shared` 不创建 `GMD-FAQ-RUNNER-FILE`；`local` 还须使用 `--profile local-storage`。启动后用对应 Compose 的 `ps` 检查实际服务集合。

**水平扩展**：每个执行节点运行 `GMD-FAQ-RUNNER-RUNTIME`、`GMD-FAQ-RUNNER-PROXY` 和 `GMD-FAQ-RUNNER-GATEWAY`；只有 `GMD_FAQ_STORAGE_MODE=local` 时才额外运行 `GMD-FAQ-RUNNER-FILE`。每个节点使用唯一的 `GMD_FAQ_NODE_ID`。多个执行节点上的网关应用分别监听各自宿主机端口，前端可在“服务监控”的“网关服务”标签中查看每个网关节点。

### 网关 API 端口访问说明

`GMD-FAQ-RUNNER-GATEWAY` 使用 Linux Docker Engine 的 `network_mode: host`。用户将网关应用上线后，Vert.x 会直接在执行节点宿主机上监听应用端口，不需要重新创建容器或增加 Compose `ports` 配置；应用下线、删除或重置时，关闭 `HttpServer` 后宿主机端口同步释放。

访问地址为：

```text
http://<执行节点宿主机 IP>:<网关应用端口>/<API 路径>
```

请注意：

- 生产环境应使用 Linux Docker Engine；Docker Desktop 的 host 网络行为取决于其虚拟机实现，不作为生产支持环境。
- 每个执行节点的防火墙必须放行实际使用的网关端口。
- 平台固定端口 `3060`、`3061`、`3062`、`3064` 以及单机中间件端口 `3306`、`5432`、`6379` 已保留，不能创建为网关应用端口。
- 网关 runner 保留 `workflow -> engine` 的智能体执行依赖，API 调用工作流中的 Agent 节点时仍由 `WorkflowAgentNodeExecutor` 执行。
- 网关 runner 不提供 AGUI 接口；AGUI 仍由 `GMD-FAQ-RUNNER-RUNTIME` 提供。

### 多节点 HOST 配置要点

多节点部署时，各节点之间通过宿主机 IP 通信，需要在 `.env` 文件中正确配置以下 HOST 参数：

| 部署节点 | 需配置的 HOST 变量 | 指向 | 用途 |
|----------|------------------|------|------|
| 控制台节点 | `MYSQL_HOST` / `REDIS_HOST` | 中间件服务器 IP | 数据库和缓存访问 |
| 控制台节点 | `RUNTIME_HOST` | 执行节点服务器 IP | Nginx upstream 代理到 runtime |
| 控制台节点 | `WEBSOCKET_HOST` | 本机容器名 `gmd-faq-websocket` | Nginx 代理 WebSocket（同节点） |
| 执行节点 | `MYSQL_HOST` / `REDIS_HOST` / `PG_HOST` | 中间件服务器 IP | 数据库、缓存、向量库访问 |
| 执行节点 | `CONSOLE_HOST` | 控制台服务器 IP | 心跳上报 |
| 执行节点 | `WEBSOCKET_HOST` | 控制台服务器 IP | runtime 连接 WebSocket 服务 |
| 执行节点 | `GMD_FAQ_NODE_ID` / `GMD_FAQ_HOST_IP` | 唯一标识 / 本机可达 IP | 节点标识 |

## 环境变量参考

### 通用变量

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `DOCKER_REGISTRY` | Docker 镜像私有仓库前缀 | 空（使用 Docker Hub） |
| `JWT_SECRET` | JWT 签名密钥 | 模板为占位值，部署前生成至少 32 字节的随机密钥并在各服务保持一致 |
| `MYSQL_HOST` | MySQL 地址 | `gmd-faq-mysql` |
| `MYSQL_PORT` | MySQL 端口 | `3306` |
| `MYSQL_DATABASE` | 数据库名 | `gmd_faq_server` |
| `MYSQL_USER` | 数据库用户 | `root` |
| `MYSQL_PASSWORD` | 数据库密码 | `root` |
| `REDIS_HOST` | Redis 地址 | `gmd-faq-redis` |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `REDIS_PASSWORD` | Redis 密码 | `redis` |
| `REDIS_DATABASE` | Redis 数据库编号 | `7` |
| `VECTOR_STORE_TYPE` | 向量存储类型（runtime/gateway 使用） | `pgvector` |
| `PG_HOST` | pgvector 地址 | `gmd-faq-pgvector` |
| `PG_PORT` | pgvector 端口 | `5432` |
| `PG_DATABASE` | pgvector 数据库名 | `gmd_faq_vector` |
| `PG_USER` | pgvector 用户 | `postgres` |
| `PG_PASSWORD` | pgvector 密码 | `postgres` |
| `WEBSOCKET_ENABLED` | 是否启用 WebSocket 推送 | `true` |
| `WEBSOCKET_HOST` | WebSocket 服务地址 | `gmd-faq-websocket` |
| `WEBSOCKET_PORT` | WebSocket 服务端口 | `3064` |
| `WORKSPACE_CAPACITY_MB` | 工作空间容量上限（MB） | `30` |
| `TOOL_IMAGE_ALLOWED_HOSTS` | 工具图片下载允许访问的内网主机，逗号分隔精确主机名；默认仅允许公网地址，下载不附带用户凭据 | 空 |
| `GMD_FAQ_STORAGE_MODE` | 执行节点存储模式（shared 关闭 GMD-FAQ-RUNNER-FILE，local 启用 GMD-FAQ-RUNNER-FILE） | `shared` |

### 控制台节点变量

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `CONSOLE_MEM_LIMIT` | console 容器内存限制 | `2g` |
| `CONSOLE_CPU_LIMIT` | console 容器 CPU 限制 | `4` |
| `CONSOLE_JAVA_HEAP_PERCENTAGE` | console JVM 堆占容器内存百分比 | `75.0` |
| `GUEST_SESSION_RETENTION_DAYS` | 游客会话（分享链接产生）自动清理的保留天数；**`0` 表示关闭**（默认）。删除会连带删除其工作空间文件且不可恢复，需要回收时在 `.env` 里设为正数（如 `30`） | `0` |
| `GUEST_SESSION_CLEAN_MAX` | 游客会话单次清理上限（配合上一项使用），达到上限时下次执行继续 | `500` |
| `FRONTEND_PORT` | 前端 Nginx 端口 | `80` |
| `RUNTIME_HOST` | runtime 地址（Nginx upstream 代理） | `gmd-faq-runtime` |
| `RUNTIME_PORT` | runtime 端口（Nginx upstream 代理） | `3061` |
| `VITE_APP_BASE_API` | 前端构建时 API 基础路径 | `/web` |
| `VITE_APP_CONTEXT_PATH` | 前端构建时应用上下文路径 | `/web` |
| `WEBSOCKET_MEM_LIMIT` | websocket 容器内存限制 | `1g` |
| `WEBSOCKET_CPU_LIMIT` | websocket 容器 CPU 限制 | `2` |
| `WEBSOCKET_JAVA_HEAP_PERCENTAGE` | websocket JVM 堆占容器内存百分比 | `50.0` |

### 执行节点变量

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `CONSOLE_HOST` | 控制台服务器地址（心跳上报） | - |
| `RUNTIME_MEM_LIMIT` | runtime 容器内存限制 | `4g` |
| `RUNTIME_CPU_LIMIT` | runtime 容器 CPU 限制 | `4` |
| `RUNTIME_JAVA_HEAP_PERCENTAGE` | runtime JVM 堆占容器内存百分比 | `75.0` |
| `MODEL_CHAT_READ_TIMEOUT_SECONDS` | 聊天模型响应的静默读取超时（秒）；长推理可按需增加 | `600` |
| `TOOL_EXECUTION_TIMEOUT_SECONDS` | 智能体工具执行超时（秒）；默认高于 Shell 命令的 300 秒超时 | `360` |
| `SHELLPROXY_MEM_LIMIT` | proxy 容器内存限制 | `1g` |
| `SHELLPROXY_CPU_LIMIT` | proxy 容器 CPU 限制 | `2` |
| `SHELLPROXY_PIDS_LIMIT` | proxy 容器最大进程数 | `200` |
| `SHELLPROXY_DEFAULT_TIMEOUT` | Shell 命令默认超时（秒） | `300` |
| `SHELLPROXY_MAX_TIMEOUT` | Shell 命令最大超时（秒） | `3600` |
| `SHELLPROXY_MAX_OUTPUT_SIZE` | Shell 命令最大输出字节数 | `52428800` |
| `SHELLPROXY_JAVA_HEAP_PERCENTAGE` | proxy JVM 堆占容器内存百分比 | `50.0` |
| `GATEWAY_MEM_LIMIT` | gateway 容器内存限制 | `4g` |
| `GATEWAY_CPU_LIMIT` | gateway 容器 CPU 限制 | `4` |
| `GATEWAY_JAVA_HEAP_PERCENTAGE` | gateway JVM 堆占容器内存百分比 | `75.0` |
| `FILE_MEM_LIMIT` | file 容器内存限制 | `512m` |
| `FILE_CPU_LIMIT` | file 容器 CPU 限制 | `0.5` |
| `FILE_JAVA_HEAP_PERCENTAGE` | file JVM 堆占容器内存百分比 | `60.0` |

> 资源默认值按单机编排列出；执行节点编排及 `.env.execute` 中 runtime 默认为 `8g` / `8`。各角色模板和编排回退值可能不同，实际生效值以当前主机 `.env` 与 `docker compose config` 为准。

### 心跳标识变量

直接使用 Compose 时由 `.env` 提供；启动脚本可自动检测，但会覆盖 `.env`。

| 变量名 | 说明 | 配置要求 |
|--------|------|-------------|
| `GMD_FAQ_NODE_ID` | 节点唯一标识 | 同机一致、跨机唯一 |
| `GMD_FAQ_HOST_NAME` | 节点主机名 | 宿主机 hostname |
| `GMD_FAQ_HOST_IP` | 节点 IP 地址 | 其他服务可达的宿主机 IP |

## 本地语音服务

当前版本暂时隐藏对话页的语音入口，即使完成下述服务配置也不会显示；待体验完善后再恢复开放。

Vue 正式对话页可接入自托管 LiveKit。配套项目默认放在主仓库同级的 `D:\project\AI\GMD-FAQ-VOICE`，包含 LiveKit Compose、本地 SenseVoice/MeloTTS/Silero worker、D 盘安装缓存和启动脚本。模型不在主项目的 Java 镜像中运行。

```powershell
cd D:\project\AI\GMD-FAQ-VOICE
.\scripts\install.ps1
.\scripts\start.ps1
.\scripts\integrate.ps1
```

安装脚本使用项目内的 `.venv`、`models`、`.cache` 和 `logs`，安装 Python 依赖与下载模型均落到 D 盘。Docker 镜像跟随 Docker Desktop 数据目录，本机部署前应确认该目录也在 D 盘。

主项目 runtime 通过以下环境变量接入，未提供密钥时隐藏语音入口：

| 参数 | 说明 |
| --- | --- |
| `GMD_VOICE_SERVER_URL` | 浏览器可达的 LiveKit WebSocket 地址，本机为 `ws://127.0.0.1:7880` |
| `GMD_VOICE_API_KEY` | 服务端签发 LiveKit 房间令牌的 Key |
| `GMD_VOICE_API_SECRET` | 至少 32 字节的 LiveKit Secret，保存在服务端配置 |
| `GMD_VOICE_AGENT_NAME` | 目标 worker 名称，默认为 `gmd-faq-voice` |

`configure.ps1` 在配套项目生成随机凭据；`configure-integration.ps1` 只把四项 `GMD_VOICE_*` 配置写入主仓库已有的 `docker/.env`，保留其他业务配置。单机和执行节点的 Compose 已声明这些参数，因此后续常规重建会保留语音配置。`integrate.ps1` 自动同步配置、依次重建 runtime 和 frontend，并重启前端以刷新 upstream 地址。执行节点远程部署时需自行配置浏览器可达的地址与 TLS。

默认只允许本机访问：LiveKit 信令 `7880/TCP`、媒体 `7881/TCP` 与 `7882/UDP` 绑定 `127.0.0.1`；worker 健康端口为 `7889`。初始限制为一通电话、每个本地模型四个 CPU 线程，建议另外预留 1.5～2 GB 内存。CPU 吞吐量、模型冷启动、已有业务负载和所选对话模型均会影响延迟。

LiveKit 容器与本机 Python worker 需要同时运行。电脑重启后，在配套语音项目重新执行 `scripts/start.ps1`；脚本会检查当前进程、本次 LiveKit 启动后的注册记录与健康接口，重复执行不会重复启动。worker 已启动后会持续重试连接，Docker 暂时停止再恢复时可自动重新注册。页面连接房间后仍会等待 worker 进入可用状态，45 秒未就绪则提示启动服务并释放麦克风。

识别、合成和插话检测不依赖付费 API；对话模型仍跟随现有模型配置，语音推理以 `VOICE` 类型记录实际用量。浏览器以当前登录身份桥接业务接口，worker 不保存用户登录令牌或模型密钥。语音只向具备 `agent:chat:instruct` 权限的本人正式会话开放，语音提交复用现有队列、工具权限与审批。操作说明见[对话手册](../ui/src/doc/content/chat/index.md)。

## 文件结构

```
docker/
├── console/                    # GMD-FAQ-RUNNER-CONSOLE Dockerfile
├── runtime/                    # GMD-FAQ-RUNNER-RUNTIME Dockerfile
├── gateway/                    # GMD-FAQ-RUNNER-GATEWAY Dockerfile（host 网络，动态暴露 API 端口）
├── proxy/                      # GMD-FAQ-RUNNER-PROXY Dockerfile
├── file/                       # GMD-FAQ-RUNNER-FILE Dockerfile
├── websocket/                  # GMD-FAQ-RUNNER-WEBSOCKET Dockerfile
├── frontend/                   # 前端 Dockerfile
├── nginx/                      # Nginx 配置
├── maven/                      # Maven 私有仓库配置
├── npm/                        # NPM 私有仓库配置
├── docker-compose-simple.yml   # 单机体验版
├── docker-compose-console.yml  # 生产-控制台节点
├── docker-compose-execute.yml  # 生产-执行节点
├── docker-compose-middleware.yml # 生产-中间件
├── start-simple.sh             # 单机管理脚本（build/rebuild/start/stop/restart/down/status）
├── start-console.sh            # 控制台节点管理脚本
├── start-execute.sh            # 执行节点管理脚本
├── start-middleware.sh         # 中间件管理脚本
├── .env.simple                 # 单机环境变量
├── .env.console                # 控制台环境变量
├── .env.execute                # 执行节点环境变量
└── .env.middleware             # 中间件环境变量
```
