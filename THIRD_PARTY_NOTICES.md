# 第三方组件说明

本仓库的项目许可见 [LICENSE](LICENSE)。集成的第三方源码保留其原有许可，不由主项目许可替代。

| 组件 | 版本及用途 | 源码 | 许可 |
| --- | --- | --- | --- |
| [LightRAG](https://github.com/HKUDS/LightRAG) | 1.5.8 本地适配快照；知识图谱、索引与检索 | `services/lightrag/` | [MIT](services/lightrag/LICENSE) |
| [MinerU](https://github.com/opendatalab/MinerU) | 3.4.5；PDF、OCR、表格、公式及文档解析 | `services/mineru/` | [MinerU 开源许可协议](services/mineru/LICENSE.md)，包含 Apache 2.0 及附加条款 |

源码来源、导入范围和初始摘要记录在各目录 `UPSTREAM.json`。模型权重与 Python 依赖独立分发，使用和分发时还需遵守其各自许可。
