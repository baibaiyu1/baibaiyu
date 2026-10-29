# Linux 单机 GPU 部署

本方案把平台、LightRAG API 和 MinerU GPU API 部署到同一台 Linux 服务器。平台提供唯一的前端；两个 Python 后端都已纳入本仓库 `services/`。LightRAG 使用仓库内适配源码构建，不包含独立 WebUI；MinerU 固定为 3.4.5 的 pipeline 后端，不安装 Gradio、vLLM 或其他可选引擎。

该入口复用单机编排，适合首次部署、受控验证及单机运行。多服务器、共享存储与正式环境的容量规划见 [Docker 指南](README.md)及[共享存储指南](shared-storage.md)。LightRAG 默认延续现有文件存储，按单实例工作区部署；文件存储仅用于小规模测试和验证，需要生产规模或多个写入进程时应另行规划数据库后端及数据迁移，不能直接增加副本。

## 服务器准备

- Linux x86_64，例如 Ubuntu 22.04 / 24.04；本配置的默认 PyTorch 镜像不覆盖 ARM 服务器。
- Docker Engine 和 Docker Compose **2.24.4 或更新版本**；覆盖文件使用 `!override` 替换端口映射。
- NVIDIA 驱动与 NVIDIA Container Toolkit。默认 MinerU 基础镜像为 `pytorch/pytorch:2.8.0-cuda12.8-cudnn9-runtime`；建议使用支持 CUDA 12.8 的 570 系列或更新驱动。不同 GPU 架构、驱动和 CUDA 的支持范围需以实际检测为准。
- 单机同时构建和运行时，建议从 8 核、32 GB 内存、8 GB 显存和 80 GB 可用磁盘开始，按文档量与并发实测调整。模型文件、依赖、构建缓存和数据备份都占磁盘；容器内存上限不等于预留内存。

安装 Docker 与 Toolkit 分别参考 [Docker Engine 安装说明](https://docs.docker.com/engine/install/)和 [NVIDIA Container Toolkit 安装说明](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html)。安装后验证：

```bash
docker version
docker compose version
nvidia-smi
docker run --rm --gpus all nvidia/cuda:12.8.1-base-ubuntu22.04 nvidia-smi
```

宿主机不需要单独安装 Java、Maven、Node 或 Python。首次在线构建需要能下载容器镜像及 Maven、npm、PyPI 等依赖。离线环境应先在相同架构的联网构建机生成镜像，再导入服务器；只上传源码不能完成离线构建。

## 上传内容

可通过 SFTP、WinSCP、FinalShell 或代码仓库传输。只需上传本仓库及独立模型目录，默认结构如下：

```text
/opt/gmd/GMD-FAQ-SERVER-MAIN/
├── services/
│   ├── lightrag/             # Python 源码、锁文件、Dockerfile.api
│   └── mineru/               # MinerU 3.4.5 Python 源码及打包配置
├── docker/
└── models/mineru/            # 不进 Git，单独上传
    └── models/               # Layout、MFR、OCR、TabCls、TabRec 等模型
```

| 内容 | 是否上传 |
| --- | --- |
| 平台源码、`docker/`、`sql/`、Maven/前端构建文件和许可证 | 上传 |
| 仓库内 `services/lightrag/` 和 `services/mineru/` | 随仓库上传；不能直接换成上游镜像 |
| MinerU 模型快照 | 上传；也可放在独立磁盘后修改 `MINERU_MODELS_DIR` |
| Windows `.venv`、`node_modules`、`target`、开发缓存和日志 | 不上传 |
| 独立 LightRAG WebUI、MinerU Gradio 页面 | 未导入本仓库，无需上传 |
| 本机 `docker/.env`、LightRAG `.env`、Windows `mineru.json` | 不直接当服务器配置；本方案生成独立配置 |
| 已有数据库、原文、图片和活动知识库数据 | 单独备份迁移，见后文 |

平台的 `ui/lightrag-management/` 必须保留，平台文档管理和图谱浏览使用该页面。首次在本机准备 `models/mineru/` 可运行 `python scripts/prepare_knowledge_models.py --source <已有模型快照目录>`，详见[知识服务说明](../services/README.md#模型准备)。Linux 容器从本仓库源码安装解析后端及其依赖，并内置使用 `/models` 的配置，无需上传 Windows 启动脚本和绝对路径配置。

## 初始化配置

从本仓库根目录执行：

```bash
cd /opt/gmd/GMD-FAQ-SERVER-MAIN
bash docker/linux-gpu.sh init
nano docker/.env.linux-gpu
nano docker/lightrag.env.linux-gpu
```

`init` 只创建缺失文件，权限按 `0600` 生成；数据库、Redis、JWT 与 LightRAG API Key 使用独立随机凭据，不在终端打印。重复执行会保留已有配置，也不会覆盖本机或原有部署的 `docker/.env`。如果复用已有数据库目录，必须保留该数据库的原始凭据，不能用新生成的密码直接接管。

重点核对：

| 配置 | 用途 |
| --- | --- |
| `LIGHTRAG_SOURCE_DIR` | 仓库内适配源码目录，默认 `../services/lightrag` |
| `MINERU_MODELS_DIR` | 默认 `../models/mineru`；目录下应包含 `models/Layout`、`models/OCR` 等；挂载只读，路径不存在即报错 |
| `MINERU_GPU_ID` | `nvidia-smi` 中要使用的 GPU 索引，默认 `0`；容器内映射为设备 `0` |
| `DATA_PATH` / `LOG_PATH` | 数据与日志位置；默认独立放在 `docker/data/linux-gpu`、`docker/logs/linux-gpu` |
| `GMD_FAQ_HOST_IP` / `GMD_FAQ_HOST_NAME` | 其他服务可达的服务器 IP 与主机名；自动检测后仍需核对 |
| `LIGHTRAG_WORKSPACE` | 工作区；迁移现有数据时必须与原值相同 |
| `*_MEM_LIMIT` / `*_CPU_LIMIT` | 容器资源限制；需给宿主机和中间件留出内存 |
| `MINERU_BASE_IMAGE` | 需要调整 CUDA 时选择包含匹配 torch/torchvision 的基础镜像，并重新验证实际 GPU 解析 |

配置中的相对主机路径均相对于 `docker/`，也可以使用 Linux 绝对路径。`lightrag.env.linux-gpu` 只保留未被平台选择的模型角色及存储兜底配置，Compose 会将其注入进程；不挂载 Windows `.env`。若迁移已有数据，先带入原存储配置，不能在迁移时顺手切换后端、工作区或向量模型。

## 构建和启动

```bash
bash docker/linux-gpu.sh check
bash docker/linux-gpu.sh up
bash docker/linux-gpu.sh ps
bash docker/linux-gpu.sh logs mineru lightrag gmd-faq-console
```

`up` 先构建并启动 Console，等待 Spring 初始化及 Flyway 完成后，再执行全部服务的 `up -d --build`，最后重启前端刷新 Nginx upstream。需要只构建镜像时使用 `bash docker/linux-gpu.sh build`。首次构建耗时取决于网络与服务器性能；默认限制 Compose 并发，避免多个 Maven 构建同时耗尽内存。升级前先安排活动任务结束，并备份数据。

MinerU 启动前检查 CUDA 可用性及本地模型目录；失败时输出驱动/Toolkit 或模型挂载错误，不会自动切为 CPU。LightRAG 等待 MinerU 健康后启动。健康检查只证明 API 可达，仍需完成平台模型同步和一次真实 PDF 入库验收。

前端入口为 `http://服务器IP/web/`，端口以 `FRONTEND_PORT` 为准。首次登录按平台提示修改初始密码。MySQL、Redis、pgvector、后端及 LightRAG 的固定映射端口绑定宿主机回环地址；MinerU 不发布宿主机端口。网关使用 host 网络，工作流动态 API 端口仍按 [Docker 指南](README.md)管理；对外 HTTPS 和域名由服务器反向代理配置。

Console、Runtime 与 LightRAG、MinerU 共用 Docker 网络：

```text
浏览器 → 平台前端 → Console / Runtime → http://lightrag:9621
                                             ↓
                                     http://mineru:8000 → NVIDIA GPU
```

网关使用 host 网络，通过容器 hosts 中的 `lightrag → 127.0.0.1` 和回环端口 `9621` 访问同一服务，因此平台保存的知识库地址也适用于网关执行。不要把容器间地址填成 `127.0.0.1`，它指向各自进程所在的网络环境。

## 平台接入与验收

1. 登录平台，在模型管理中配置服务端可达的文本、图片理解、Embedding 和可选 Rerank 模型。
2. 创建 LightRAG 知识库，基础地址填写 `http://lightrag:9621`，API Key 使用 `docker/.env.linux-gpu` 中的 `LIGHTRAG_API_KEY`。
3. 保存并同步所选模型。首次启动模板中的模型仅用于配置兜底，未填写真实供应商凭据前不要进行入库或检索；应先完成文本及向量等所需模型的同步。
4. 平台提示重启时执行 `bash docker/linux-gpu.sh restart-lightrag`，随后再次同步确认无待重启配置。已有向量切换模型或首次接管已有向量时，按仓库内[模型同步说明](../services/lightrag/docs/PlatformModels.md)先停止并离线重建，再启动；不能直接重启绕过重建要求。
5. 从平台文档管理上传一份小 PDF，检查解析完成、图谱实体、检索引用及图片。结合 `logs mineru lightrag` 确认解析实际使用 GPU；页面能打开或容器健康不足以证明完整链路可用。

每个独立知识库使用不同的 LightRAG 实例地址和持久化工作区。本配置提供一个实例；不能在平台建多个配置后指向同一实例来实现数据隔离。

## 迁移现有数据

希望将镜像、数据库、平台文件、LightRAG 活动卷和 MinerU 模型一起打包时，使用[一键离线打包与恢复](deployment-bundle.md)。该工具在本机构建和导出，服务器导入后直接运行；下述内容供手动迁移参考。

新服务器从空库开始时无需复制本机业务数据。若要保留已有账号、智能体、知识库与文档，需安排停写窗口并迁移一致的数据集：

- 平台：MySQL 逻辑备份、平台 pgvector 逻辑备份和 `DATA_PATH` 内的工作区、上传文件及其他业务文件。数据库使用对应工具导出/恢复，不直接跨环境拷贝正在写入的数据目录；迁移步骤见 [Docker 指南](README.md)。
- LightRAG：先通过 `docker inspect` 确认当前实际挂载。当前 Windows Docker 方案的活动存储是命名卷 `lightrag-rag-storage`，宿主机 `data/rag_storage` 只是旧迁移快照。停止文档入库与检索进程后导出活动卷，恢复到新配置的 `${DATA_PATH}/lightrag/rag_storage`；另行复制 `data/inputs` 到 `${DATA_PATH}/lightrag/inputs`、提示词到 `${DATA_PATH}/lightrag/prompts`。默认容器内路径保持 `/app/data/rag_storage`、`/app/data/inputs`，以便保留既有文件引用。
- 必须一并迁移活动工作目录里的 `platform_models_<workspace>.json` 等运行配置，保留工作区与租户/知识库绑定。文件包含模型凭据，需要保护备份；仅迁移 LightRAG 数据却新建一个不同 ID 的平台知识库会遇到绑定冲突，应按原模型绑定文档处理。
- MinerU：复制解析模型即可；历史 `output` 不是平台正式知识库数据，不作为必须迁移项。需要留档时单独归档，正在处理或尚未下载结果的任务不得清理。

恢复时先启动中间件并导入备份，再启动 Console 完成迁移，确认日志正常后启动 Runtime、网关和前端。LightRAG 的完整数据集应在首次启动前恢复，不要先生成一套空索引后混入旧文件。启动后对照文档数量，执行一次旧文档检索和新文档入库，验证原文、图片及模型绑定均有效。

## 更新与维护

```bash
# 更新上传的源码后，重建并启动；脚本会刷新前端 upstream
bash docker/linux-gpu.sh up

# 查看状态或持续跟踪解析日志
bash docker/linux-gpu.sh ps
bash docker/linux-gpu.sh logs -f mineru lightrag

# 停止服务，保留数据
bash docker/linux-gpu.sh stop
```

`down` 仅移除本编排容器与网络，脚本不删除数据目录。MinerU 任务产物默认在结束后保留 24 小时、每 5 分钟检查清理；进程重启前遗留且未重新登记的产物可能需要另行归档清理。备份、原始文件和知识库索引应各自设置保留策略，不能因目录名像缓存就直接删除。
