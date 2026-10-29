# LightRAG 知识图谱后端

本目录是平台内置的 LightRAG 1.5.8 Python 后端，保留本地项目中的平台模型同步、图片返回、图片质量控制和实体别名改动。Python 源码、依赖锁和 API 镜像均随主仓库维护，构建不依赖仓库外的 LightRAG 目录。

图谱与向量检索、文档入库、MinerU 解析适配、模型绑定、认证和存储后端位于 `lightrag/`。未导入独立 WebUI、Swagger 静态文件、开发示例、运行数据或密钥。平台继续使用自身的文档管理、图谱浏览和权限入口。

部署、模型准备和启动见[知识服务说明](../README.md)；平台模型协议见[模型同步说明](docs/PlatformModels.md)。`Dockerfile.api` 使用 `uv.lock` 构建，默认关闭 API 文档页面。数据、原文、图片及模型绑定通过 Compose 持久化。

来源及初始快照摘要见 `UPSTREAM.json`。导入的是本地工作树，包括未提交的平台适配；后续改动直接在本目录进行。上游项目为 [HKUDS/LightRAG](https://github.com/HKUDS/LightRAG)，按本目录 [MIT 许可证](LICENSE)分发。
