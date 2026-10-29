# 一键离线打包与 Linux GPU 恢复

本工具将当前单机平台及 LightRAG 的一致数据集导出为一个 `.tar` 部署包。上传 Linux 后，一条命令校验、导入镜像、恢复数据库与文件并启动服务。服务器无需 Maven、Node、Java、Python 或网络下载；需预先安装 Docker Engine、Docker Compose 2.24.4 及以上、NVIDIA 驱动和 NVIDIA Container Toolkit，系统为 Linux x86_64。硬件要求与驱动准备见 [Linux GPU 部署](linux-gpu.md)。

## 本机打包

从平台仓库根目录执行，要求 Python 3.10 及以上和可用的 Linux/amd64 Docker Engine：

```powershell
powershell -ExecutionPolicy Bypass -File docker/export-bundle.ps1
```

默认从当前平台源码及仓库内 `services/lightrag/`、`services/mineru/` 重新构建 8 个应用镜像，再将它们与当前 MySQL、pgvector、Redis 镜像一并导出。构建顺序执行，日志在输出目录的 `build.log`。模型默认读取本仓库 `models/mineru/`，目录下必须包含 `models/Layout`、`models/OCR` 等完整模型；首次准备见[内置知识服务](../services/README.md#模型准备)。

可指定路径，路径带空格时保留引号：

```powershell
powershell -ExecutionPolicy Bypass -File docker/export-bundle.ps1 `
  -Output "D:\deploy\gmd-linux-gpu" `
  -LightRagDir ".\services\lightrag" `
  -MineruModelsDir ".\models\mineru"
```

完成后生成 `output/gmd-linux-gpu-时间/` 和同名 `.tar`；使用 `-NoArchive` 可仅导出完整目录，使用 `-Gzip` 可生成 `.tar.gz`。镜像本身已经压缩，默认不再整体 gzip，以缩短打包时间。默认至少需要 35 GiB 可用磁盘，大量文档需留出更多空间。构建和导出 CUDA 镜像可能持续数分钟到更久，包大小取决于镜像、模型和知识库数据。

工具默认读取 `gmd-faq-*` 容器，并按 Compose 标签发现唯一的 LightRAG 实例；有多个实例时用 `-LightRagContainer` 指定。它通过 `docker inspect` 读取实际挂载，不依赖源码目录里的旧数据快照。发现其他正在运行的容器共用这些可写存储时拒绝备份。

打包前应让活动对话、工具任务和文档入库结束。镜像和模型先导出，然后工具暂停原本运行的应用写入者，顺序导出数据库并归档文件，最后恢复原启停状态。原本停止的数据库可为逻辑备份临时启动，结束或失败时关闭；原本停止的应用保持停止。`source-state.json` 记录原始状态及恢复结果；如强制关闭整个导出进程或主机断电，应按此记录检查源服务。

## 包内数据

| 内容 | 处理方式 |
| --- | --- |
| 平台 MySQL | 逻辑导出全部业务表、触发器、过程、事件、Flyway 历史与原主键；恢复时逐表核对行数 |
| 平台 pgvector | `pg_dump -Fc`，保留向量、表及扩展；恢复失败立即阻止应用启动 |
| Redis | RDB 快照，包含所有逻辑库；临时请求和订阅不会跨进程迁移，带过期时间的键继续按原时间过期 |
| 上传文件、会话工作区、平台技能与业务文件 | 从实际共享工作区完整归档；旧 `/app/.apboa` 作为新工作区的兼容挂载保留 |
| 工具依赖 | 迁移容器内 `/opt/tools` 的 Linux 依赖；不复制 Windows 虚拟环境 |
| LightRAG 图谱、向量、文档状态、模型绑定 | 从活动 `rag_storage` 挂载完整导出，包括命名卷和 `platform_models_<workspace>.json`；保持原工作区和租户/知识库 ID |
| LightRAG 原文、图片、提示词 | 分别导出实际 inputs、prompts 挂载，保留容器内数据路径 |
| MinerU | API 镜像和本地解析模型；历史临时任务和 output 不迁移，入库任务应先完成 |
| 配置与模型凭据 | 保留原 JWT、数据库、供应商和 LightRAG 配置；源 API Key 为空时生成新值，并定向同步到原知识库记录 |

恢复工具只改写所迁移 LightRAG 实例对应知识库的 `baseUrl` 和 `apiKey`，保留主键及所有其他配置；目标地址为 `http://lightrag:9621`。模型供应商地址保持原值，部署后须确认服务器可以访问；本机 Ollama、模型 API、语音服务、外部 MCP、对象存储等外部服务不会自动纳入包。默认只支持一个采用 JsonKV/JsonDocStatus/NetworkX/NanoVectorDB 文件存储的 LightRAG 实例，以及当前单机 root MySQL 连接方案；外部数据库、自定义数据路径、多个实例和集群需另行迁移，不会静默丢弃。

包内包含账号数据和模型密钥，应通过 SSH/SFTP 传输，并限制备份目录权限。SHA256 用于检查传输损坏，接收方应使用可信来源的部署包。

## 上传并恢复

例如上传至 `/opt/gmd/`，在 Linux 执行：

```bash
cd /opt/gmd
tar -xf gmd-linux-gpu-时间.tar
cd gmd-linux-gpu-时间
BUNDLE_HOST_IP=服务器IP bash deploy.sh
```

`BUNDLE_HOST_IP` 用于节点注册地址及最终入口提示，缺省取 `hostname -I` 的第一个地址。多网卡、有公网映射时应显式填写平台可达地址。若需更改默认 HTTP 80、MySQL 3306、Redis 6379、pgvector 5432 端口、GPU 索引或项目名，在首次执行前编辑 `settings.env`。后台端口仅监听回环，MinerU 不发布宿主机端口，网关保留 Linux host 网络模式。

## 端口与联通

单机部署使用同一个 Compose 网络，容器间按服务名访问；服务器 IP 只用于浏览器入口和节点注册地址。不要把容器内连接统一替换为服务器公网 IP，也不要把容器内的 `127.0.0.1` 当成其他容器。采用 host 网络的网关沿用宿主机网络，通过回环端口访问数据库、Console、Proxy 和 LightRAG。

| 服务 | 容器内连接地址 | 宿主机默认监听 | 云安全组/服务器防火墙 |
| --- | --- | --- | --- |
| 平台前端 Nginx | `gmd-faq-frontend:80` | `0.0.0.0:80` | 浏览器入口，放行实际 `FRONTEND_PORT` |
| Console | `gmd-faq-console:3060` | `127.0.0.1:3060` | 无需对外放行 |
| Runtime | `gmd-faq-runtime:3061` | `127.0.0.1:3061` | 无需对外放行；聊天/SSE 通过 Nginx |
| Proxy | `gmd-faq-proxy:3062` | `127.0.0.1:3062` | 无需对外放行 |
| WebSocket | `gmd-faq-websocket:3064` | `127.0.0.1:3064` | 无需对外放行；浏览器 WebSocket 通过 Nginx |
| MySQL | `gmd-faq-mysql:3306` | `127.0.0.1:3306` | 无需对外放行 |
| Redis | `gmd-faq-redis:6379` | `127.0.0.1:6379` | 无需对外放行 |
| pgvector | `gmd-faq-pgvector:5432` | `127.0.0.1:5432` | 无需对外放行 |
| LightRAG | `lightrag:9621` | `127.0.0.1:9621` | 无需对外放行；平台代理管理与检索 |
| MinerU | `mineru:8000` | 不发布宿主机端口 | 无需对外放行 |
| 网关工作流 API | 按平台上线配置 | host 网络中的实际应用端口 | 仅按实际发布的应用端口放行 |

HTTP 前端默认没有 HTTPS。若使用域名和 HTTPS，在服务器反向代理上配置证书与 443，代理到前端端口，并透传 WebSocket 的 Upgrade 和聊天 SSE；启用 HTTPS 后再放行 443。SSH/SFTP 上传所用端口另按服务器现有配置放行。云主机必须同时检查云安全组和服务器防火墙，脚本不会自动修改二者。

例如服务器已有服务占用 80、3306，在首次恢复前编辑包内 `settings.env`：

```ini
FRONTEND_PORT=8080
MYSQL_PORT=13306
```

随后浏览器访问 `http://服务器IP:8080/web/`，云安全组放行 TCP 8080。MySQL 容器之间仍使用 `gmd-faq-mysql:3306`，host 网络网关的连接端口自动改为 13306。其他固定内部端口保持编排所定义的值，脚本部署前会检查占用；不要只改单处连接地址。部署后 `settings.env` 被记录，防止意外切换到另一套项目或端口。

部署完成自动执行联通检查，也可以随时手动运行：

```bash
bash deploy.sh doctor
```

它只做读取和连接探测，不启动、重启或修复服务。输出每条 `PASS` / `FAIL`，检查容器运行与健康状态、实际服务容器的 DNS/TCP、Nginx 配置、前端 HTML、Console/Runtime 的 Nginx 鉴权响应、Runtime 到 LightRAG 的带密钥健康与认证接口，以及 LightRAG 到 MinerU 的健康接口。网关从自身容器检查宿主机回环连接和 `lightrag` hosts 映射；任一失败返回非零，部署不会报告成功。Console/Runtime 的鉴权响应只证明路由可达，不视为已登录的业务接口验收。

服务器内全部通过后，从自己的电脑打开前端地址，登录并验证聊天流、WebSocket、新 PDF 解析与旧知识库检索。容器内探测无法证明外部安全组、DNS、HTTPS 证书及浏览器网络可用，也不能代替模型供应商鉴权。模型管理里的 API 地址必须能从服务器访问；原本指向 Windows `localhost` 或 `host.docker.internal` 的模型、MCP 等外部服务，需要改成服务器可达的地址。

排查示例：

```bash
bash deploy.sh status
bash deploy.sh doctor
bash deploy.sh logs gmd-faq-runtime lightrag mineru
curl -I http://127.0.0.1:80/web/
```

若服务器本机访问成功、自己电脑访问失败，优先核对实际前端端口、云安全组、防火墙及公网映射。若 `Nginx -> Runtime` 失败，检查 Runtime 日志；后端重建后可运行 `bash deploy.sh resume`，脚本会刷新前端 upstream 并重新检查。若 `LightRAG -> MinerU` 失败，检查 MinerU 容器健康、GPU/模型挂载和 `MINERU_LOCAL_ENDPOINT`。

包内 `compose.json` 已指定确切镜像和 `pull_policy: never`，无需服务器构建。恢复前校验所有 payload 的 SHA256、镜像 ID、目标端口、Compose 配置和实际 CUDA 可用性。恢复顺序为：文件 → 中间件 → 数据库导入及行数校验 → Console/Flyway → LightRAG/MinerU 与其他服务 → 刷新前端 upstream。

**恢复必须使用空的 `data/` 和未占用的 Compose 项目名。工具不会删除或覆盖旧服务器数据。** 发现非空目录、已有项目容器或导入中断会停止。导入失败时保留现场，排查日志后重新解压到另一个目录并选用不同项目名重试；如启动应用阶段失败，可执行 `bash deploy.sh resume`，不会再次导入数据库。完成恢复后不要更改 `settings.env` 的项目或端口值，脚本会检测并拒绝意外切换。

仅恢复文件与数据库、暂缓启动应用：

```bash
bash deploy.sh restore
BUNDLE_HOST_IP=服务器IP bash deploy.sh resume
```

`restore` 不执行 GPU 检查，便于在隔离环境验证数据；正式 `deploy`、`resume`、`check` 均验证 CUDA。数据库恢复只证明数据可读，不能代替 GPU 和应用验收。

## 验收与维护

```bash
bash deploy.sh status
bash deploy.sh logs gmd-faq-console lightrag mineru
bash deploy.sh check
bash deploy.sh doctor
bash deploy.sh stop
```

访问 `http://服务器IP/web/`（端口按 `settings.env`），使用原账号登录，检查旧智能体、知识库、原文与图片，再执行旧文档检索和新 PDF 入库。现有向量的模型身份保持不变，不能在恢复时切换 Embedding 或自动清空索引。停止命令保留全部数据。

需要显式复用已经构建好的镜像时，准备 JSON 文件，将 8 个服务 `gmd-faq-console`、`gmd-faq-runtime`、`gmd-faq-proxy`、`gmd-faq-websocket`、`gmd-faq-gateway`、`gmd-faq-frontend`、`lightrag`、`mineru` 分别映射到本机镜像标签，再传入 `-ImageMap 文件路径`。这会跳过源码构建，`manifest.json` 会记录该选择；调用方应确认这些镜像包含当前适配，不能直接复用未更新的旧运行镜像。
