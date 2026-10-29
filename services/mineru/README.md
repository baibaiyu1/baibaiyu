# MinerU 文档解析后端

本目录包含从本机已安装的 MinerU 3.4.5 提取的 Python 后端源码，以及运行所需的 OCR 字典、网络结构配置和语言检测资源。`pyproject.toml` 根据该安装包的元数据恢复，仅提供基础和 `pipeline` 依赖以及 CLI、API、模型下载入口。`mineru-vl-utils` 固定为本机配套的 1.2.1。

API 保留 `/health`、`/tasks`、`/tasks/{id}`、`/tasks/{id}/result`，与本仓库 LightRAG 的本地解析适配器配套。Gradio 应用和页面资源未导入；其他后端的内部模块因 API 公共代码有依赖而保留，但镜像只安装 pipeline 依赖。Docker 从本目录构建和安装源码，不从 PyPI 再安装另一份 MinerU。

模型准备、GPU 部署和验证见[知识服务说明](../README.md)。模型权重放在主仓库 `models/mineru/` 并单独分发，不放入 Git；容器配置和启动检查位于主仓库 `docker/mineru/`。初始来源和打包调整见 `UPSTREAM.json`。

本平台使用 [MinerU](https://github.com/opendatalab/MinerU) 提供文档解析能力，按本目录 [MinerU 开源许可协议](LICENSE.md)分发，该协议包含 Apache 2.0 及附加条款。
