# LightRAG 管理页集成

本目录保存平台使用的 LightRAG 原生文档管理与图谱浏览组件。源码来源于 [HKUDS/LightRAG](https://github.com/HKUDS/LightRAG) 的 `lightrag_webui/src/`，迁入时基于提交 `aaebfe1bb8d55deb6a4304d357a3bf4c92fcd40a`。保留原项目的 [MIT 许可证](LICENSE)。

页面由平台维护、构建和部署。Vue 知识库编辑页提供入口，React 页面通过同源 iframe 加载 `/web/lightrag-management/index.html`；iframe 用于隔离 Tailwind 与平台样式。远端 LightRAG 可以只部署 API 服务，管理页不读取其 HTML 或静态资源。

## 构建与验证

在仓库根目录执行：

```sh
pnpm -C ui install --frozen-lockfile
pnpm -C ui type-check
pnpm -C ui test:lightrag-management
pnpm -C ui build:main
```

主应用采用 Vite 多页面构建，输出 `ui/dist-main/lightrag-management/index.html` 及相关分片。React 组件延迟加载，主应用入口不加载管理页的 Tailwind 样式。现有前端 Docker 构建直接包含这些产物。文档应用继续独立构建。

## 平台连接

- `src/main.tsx` 只从 URL 读取已保存的知识库 ID 和 `documents` / `graph` 视图。API 地址固定为同源 `/api/knowledge/config/{id}/lightrag`，URL 中传入的远端地址、密钥或其他视图不生效。
- `src/api/lightrag.ts` 沿用原生文档上传、分页、管道进度和图谱查询协议，通过 Console 连接 LightRAG。沿用平台登录 Cookie，页面不存储 LightRAG 密钥，不刷新原生登录令牌，不重试文档写请求。
- Console 在每次请求中检查知识库编辑权限、资源可见性和管理权，使用已保存的服务地址及 API 密钥连接 LightRAG。仅放行文档管理和图谱读取接口，远端 HTML、静态资源和图谱修改接口不开放。
- `PlatformManagementApp` 只加载文档和只读图谱组件。管理页与 Vue 宿主通过校验来源的 `postMessage` 通知就绪或连接错误。
- 图谱实体搜索历史仅在当前页面内存中保存，并按知识库区分；显示偏好使用平台专用存储键。原生独立登录、导航、对话、系统设置、图谱属性编辑和其他语言包未迁入。

服务可达性、认证、工作区隔离和上传限制见 [Docker 指南](../../docker/README.md#外挂-lightrag-知识库)。

## 更新原生组件

更新时先比较上游文档管理和图谱组件及其依赖，保留 `@lightrag/` 别名、平台 HTTP 客户端、独立入口和只读约束。不要直接覆盖平台入口、连接逻辑和权限边界。检查接口允许列表是否仍覆盖原生页面实际需要的 API，并在本文件更新来源提交。

组件测试使用 Vitest 与 happy-dom，覆盖入口、平台认证失败、multipart 上传、删除参数、写操作不重试、图谱只读和存储隔离；Console 测试通过本地 HTTP 上游覆盖真实 MVC 路由及转发协议。升级后还需在浏览器验证文档状态、处理进度、实体搜索和图谱详情。
