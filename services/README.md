# 内置知识服务

平台将 LightRAG 知识图谱和 MinerU 文档解析后端纳入同一仓库，保留 Python 运行时，通过既有 Console/Runtime 接口与 Java 平台连接。开发、构建和升级直接使用本目录源码，无需旁边再放两个项目。

```text
services/
├── lightrag/       # 图谱、向量检索、文档流水线、平台模型同步和图片返回
└── mineru/         # MinerU 3.4.5 API、pipeline 解析和所需资源
docker/mineru/     # GPU 镜像、模型路径配置和启动检查
models/mineru/     # 独立模型权重，不进 Git
```

平台继续使用已有知识库、文档管理和图谱浏览页面。LightRAG 独立 WebUI、Swagger 静态页面及 MinerU Gradio 页面不随服务导入；API 文档页面默认关闭。知识图谱默认使用内置服务，地址和密钥由部署配置统一提供；点击“连接第三方服务”才展开独立连接配置，已有第三方地址继续生效。源码导入不会新建知识库或修改存量数据。

## 模型准备

从已有 MinerU 安装中复制 pipeline 模型快照，在仓库根目录执行：

```powershell
python scripts/prepare_knowledge_models.py --source "D:\project\AI\MinerU\models\modelscope\models\OpenDataLab--PDF-Extract-Kit-1.0\snapshots\master"
python scripts/prepare_knowledge_models.py --check
```

复制工具验证 Layout、MFR、OCR、TabCls、TabRec 的文件存在，复制后校验 SHA256，重复执行跳过相同文件；发现目标存在不同内容时停止。源目录保持原样。得到的 `models/mineru/models/` 可随部署单独上传，或由离线打包工具纳入部署包。

新机器没有模型时，可在联网环境安装本仓库 MinerU 的 pipeline 依赖，通过 `mineru-models-download -s modelscope -m pipeline` 下载，再将生成的快照传给上面的复制工具。模型目录约 2.6 GB，不包含在 Git 中。`MINERU_MODELS_DIR` 可改为其他完整快照的绝对路径。

## 构建与启动

基础 `docker/docker-compose-simple.yml` 已包含内置 LightRAG，随平台构建和启动。首次部署在 `docker/.env` 设置独立的 `LIGHTRAG_API_KEY`，按需复制 `docker/lightrag.env.example` 为 `docker/lightrag.env`，配置尚未由平台选择的模型和解析服务。Console、Runtime 和 Gateway 从部署环境读取同一服务凭证，无需在知识库表单填写。已有图谱需保留实际使用的数据卷、输入目录及原模型配置，迁移步骤见 [Docker 指南](../docker/README.md#内置-lightrag-与-mineru-知识服务)。

Linux NVIDIA GPU 部署从仓库根目录执行：

```bash
bash docker/linux-gpu.sh init
# 核对 docker/.env.linux-gpu 的 GPU、模型和数据目录；填写平台所需模型配置。
bash docker/linux-gpu.sh check
bash docker/linux-gpu.sh up
```

完整环境、配置、模型同步与存量数据迁移见 [Linux GPU 部署](../docker/linux-gpu.md)。`lightrag` 默认从本目录构建，`mineru` 从本仓库源码安装，容器间地址为 `http://lightrag:9621` 和 `http://mineru:8000`。MinerU 使用 GPU；LightRAG 的图谱抽取、图片理解和向量化使用平台选择的供应商模型。

离线迁移使用[部署包工具](../docker/deployment-bundle.md)，默认读取本仓库源码和 `models/mineru/`。该工具保留已有数据库和活动卷；把源码纳入仓库不会自动迁移原服务数据。

## 验证与维护

```bash
python -m unittest discover -s scripts/tests -p "test_*.py"
python -m compileall -q services/lightrag/lightrag services/mineru/mineru
```

构建镜像后，可执行独立的 GPU 联调：

```bash
python scripts/tests/smoke_knowledge_services.py
```

该工具创建并清理隔离容器及网络，使用真实 MinerU GPU 解析和 LightRAG 入库、图谱、检索实现，以本地固定响应模型服务替代外部供应商调用，检查 API 认证、平台模型归属、重启生效和文档持久化。默认读取 Compose 的镜像标签，可用 `--lightrag-image`、`--mineru-image` 和 `--models` 指定。验证文件输出到主仓库 `output/`，不访问已有数据库或知识库卷。它验证服务协议与处理链路，不评估模型语义质量或代替平台登录后的业务验收。

部署后在平台登录，创建或打开知识图谱配置，使用默认内置连接，选择并同步模型，按提示重启 LightRAG；第三方服务点击“连接第三方服务”后填写地址和密钥。内置工作区会绑定一个平台知识库，未选择平台模型也会核对绑定，避免多个配置绕过资源权限读取同一份数据。使用 MinerU 时上传小型 PDF，确认文档完成解析和入库、图谱可浏览且智能体检索能返回来源。健康接口只证明服务运行，不能代替这一步。

LightRAG 基于 1.5.8 的本地适配快照，MinerU 固定为与 `/tasks` 协议匹配的 3.4.5。初始来源和导入内容见各自 `UPSTREAM.json`。更新时同时验证平台模型绑定、向量模型身份、解析任务协议和图片结果；不要直接覆盖为上游最新版本。

NanoVectorDB 的快照加载使用字节 JSON 解析，避免含中文元数据的大型向量文件在启动时生成整份 Unicode 文本副本；沿用原文件格式、归一化、检索与写入协议。兼容性检查见 `scripts/tests/test_nano_vector_snapshot.py`，需在安装 LightRAG 依赖的 Python 环境运行。

本平台使用 [LightRAG](https://github.com/HKUDS/LightRAG) 提供知识图谱能力，按[MIT 许可](lightrag/LICENSE)分发；使用 [MinerU](https://github.com/opendatalab/MinerU) 提供文档解析能力，按[MinerU 开源许可协议](mineru/LICENSE.md)分发。
