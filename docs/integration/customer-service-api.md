# 外部平台客服助手接口与调用教程

本文面向公司平台的接入开发人员，按当前 Controller、DTO 和运行实现编写。地址使用部署后前端 Nginx 的 `/api/` 入口，默认 Runtime 路径为 `/api/runtime/agui`。不需要访问数据库或直接暴露 Java 服务端口。

配套文件：[可导入 Postman 的调用集合](customer-service.postman_collection.json)。后文完整列出当前标记 `@SkAccess` 的接口，以及文件预览和游客换取令牌入口；表中 MCP GET 是已存在但明确返回 405 的端点。

## 1. 准备三个值

| 配置 | 示例 | 从哪里获得 |
| --- | --- | --- |
| `baseUrl` | `https://ai.example.com` | 平台部署后的域名或服务器地址，不带末尾 `/`，不带 `/web` 或 `/api` |
| `apiKey` | `sk-实际密钥` | 由平台管理员/有权限账号在 API 密钥页面创建，存放在公司平台后端 |
| `agentId` | `2038965802636013500` | 平台运营人员提供的客服智能体数据库 ID；不能填写智能体名称 |

智能体须已启用、已发布，并允许创建密钥的账号使用。先调用 A01 可以取得 `agentCode`。`agentId` 和 `agentCode` 是两种值：创建对话用前者，运行对话用后者。

普通请求统一携带：

```http
Authorization: Bearer sk-实际密钥
Accept: application/json
```

JSON 请求再加 `Content-Type: application/json`。上传使用 `multipart/form-data`，让 HTTP 客户端生成 boundary。流式请求改为 `Accept: text/event-stream`，并携带 `X-GMD-FAQ-Thread-Id: 会话ID`；对同一会话的状态、停止、重连和续跑请求也携带该头。

普通业务接口返回：

```json
{"code":200,"success":true,"data":{},"msg":"操作成功"}
```

读取 `data` 前检查 `code === 200`。部分鉴权失败也可能使用 HTTP 200 + 非 200 业务码，不能只检查 HTTP 状态。运行接口返回 SSE；运行状态、审批列表等直接返回 JSON，不套 `data`；下载返回二进制，不能按 JSON 解析。

长数字 ID 在 JSON 中按字符串使用，不要转成 JavaScript `Number`。服务端会将 Long 序列化为字符串。

| 名称 | 是什么 | 什么时候更新 |
| --- | --- | --- |
| `agentId` | 客服智能体的数据库 ID，创建会话时使用 | 切换客服智能体时 |
| `agentCode` | A01 返回的运行编号，填入运行接口路径 | 切换客服智能体时 |
| `sessionId` / `threadId` | 同一个正式会话 ID，两种接口叫法不同 | 用户点击“新对话”时 |
| `messageId` | S02 返回的本次用户消息 ID | 用户每次发送问题时 |
| `runId` | 本轮运行的新 UUID，由接入方生成 | 每次发起 R01 时 |
| `filePath` / `attachmentId` | 前者是工作区相对路径，后者是上传附件 ID | 分别从工作区/图片结果或 F01 取得，不能互换 |

## 2. 用 Postman 跑通第一段对话

1. 在 Postman 中 Import 同目录 JSON 集合。
2. 打开集合 Variables，填写 `baseUrl`、`apiKey`、`agentId`，其余先保留默认值。
3. 进入“01 首次联调”，依次手工发送 A01 → S01 → S02 → R01 → S03 → S04。
4. A01 自动保存 `agentCode`；S01 自动保存 `sessionId`；S02 自动保存 `messageId`；R01 每次自动生成新 `runId`。
5. 继续追问：修改变量 `question`，再发送 S02 → R01。不要再次发送 S01。
6. 开启新对话：再次发送 S01，然后发送 S02 → R01。旧对话仍保留，原 ID 应由公司后端保存。

R01 是 SSE，响应可能需要等待。支持 SSE 的 Postman 版本可以直接查看事件；否则使用下一节的 `curl -N`。响应后的自动取文件路径脚本，要等流结束才执行。上传请求中的文件需要自己选择。F01 会将附件 ID 放入 `fileIds` 变量；后续提问不再附图时，把该变量改回 `[]`。工具参数、文本文件内容等可直接修改对应请求的 JSON Body。R06 会保存逐项 `pendingDecisions`，将每项 `approved` 改为真实用户的决定后再发送 R07；默认值为拒绝。

集合还包含删除、清空和覆盖文件请求，请按功能手工选择，不要对整个集合运行 Collection Runner。此集合不包含真实凭据，也未向任何服务器自动发送请求。

## 3. 直接用 HTTP / curl 调用

本节是 Linux Bash 示例。`ai.example.com`、密钥、智能体与会话 ID 都是示例值，替换为实际值。

### 3.1 先读智能体配置：只需初始化时调用

```bash
BASE_URL='https://ai.example.com'
API_KEY='sk-实际密钥'
AGENT_ID='2038965802636013500'

curl -sS "$BASE_URL/api/agent/definition/$AGENT_ID" \
  -H "Authorization: Bearer $API_KEY"
```

从响应 `data.agentCode` 取得编号，例如 `after-sales`。后续运行地址用这个编号，不能用 `AGENT_ID` 替代。

### 3.2 开启一段新对话

```bash
curl -sS -X POST "$BASE_URL/api/agent/chat/session" \
  -H "Authorization: Bearer $API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"2038965802636013500","title":"售后咨询","initWorkspace":true}'
```

响应示例只展示后续需要的字段：

```json
{"code":200,"success":true,"data":{"id":"2038965802636013570","agentId":"2038965802636013500","title":"售后咨询"}}
```

保存 `data.id`。下面把这个值称作 `SESSION_ID`：

```bash
SESSION_ID='2038965802636013570'
AGENT_CODE='after-sales'
```

正式客服会话必须由此接口创建，使用返回的数字字符串 ID。运行接口中的非数字临时 threadId 用于调试，不能替代有历史与工作区管理能力的正式会话。

### 3.3 保存用户的本次问题

```bash
curl -sS -X POST "$BASE_URL/api/agent/chat/session/$SESSION_ID/message" \
  -H "Authorization: Bearer $API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"role":"user","content":"售后服务时间是什么？"}'
```

保存响应 `data.id`，它是消息 ID，例如 `125`。此时只是保存问题，还没有生成回答。用户每发一个新问题，执行一次本步骤。

### 3.4 获取客服回答

```bash
curl -N -X POST "$BASE_URL/api/runtime/agui/run/$AGENT_CODE" \
  -H "Authorization: Bearer $API_KEY" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H "X-GMD-FAQ-Thread-Id: $SESSION_ID" \
  -d '{
    "threadId":"2038965802636013570",
    "runId":"7f257f9d-44bf-4f0c-9241-9582c8bf0a31",
    "messages":[{"id":"125","role":"user","content":"售后服务时间是什么？"}],
    "state":{},
    "tools":[],
    "context":[],
    "forwardedProps":{"fileIds":[]}
  }'
```

`threadId` 填 S01 返回的会话 ID；本示例每次调用都应替换 `runId` 为新 UUID，`messages[0].id` 填上一步返回的消息 ID，`content` 与已保存的问题保持一致。每轮向模型传本轮问题即可；需要多轮上下文时，在智能体配置中开启记忆。记忆、工具展示与计划开关以服务端配置为准，传 `userId` 或 `userInfo` 不会切换身份。

助手回复由 Runtime 写入聊天日志，普通接入不需要再把 SSE 拼出的回答用 S02 保存一次。

### 3.5 用户继续追问 / 点击新对话

| 用户操作 | 发哪些请求 | ID 如何处理 |
| --- | --- | --- |
| 接着问“周末呢？” | S02 保存新问题 → R01 获取回答 | `sessionId` 不变，`messageId` 和 `runId` 更新 |
| 点击“新对话” | S01 新建 → S02 保存 → R01 回答 | 保存新 `sessionId`，旧对话仍然存在 |
| 刷新页面或打开历史 | S03 读取消息 + S04 读取产物 + R03 查运行状态 + R06 查审批 | 使用已保存的原 `sessionId`；仍在运行时可 R04 接回流 |
| 切换其他客服智能体 | 用新的 `agentId` 执行 S01 | 原会话绑定的智能体不能直接替换 |
| 清空本会话 | 停止运行后执行 S16，若还要删文件则执行 W13 | 会话 ID 保留；这与新建会话不同 |

公司平台后端应保存：`公司租户 + 客户ID + 公司会话ID → sessionId + agentId + 使用的SkKey标识`。每次读写会话、下载图片文件时，先确认该客户确实拥有这个对应关系。S05/S06 列出的是整个当前 SkKey 身份的会话，不能原样作为某一个客户的历史列表。

同一会话一轮结束后再发送下一轮，其他会话可以并行。SkKey 当前不能使用账号专用的消息排队、插队、运行中补充要求和方案批准接口。切换到新会话时，旧流的事件仍按原 `threadId/runId` 归属，不能混进新窗口。

## 4. 如何接收 SSE 回答

SSE 不是一个完整 JSON。HTTP 客户端要持续读响应，按空行拆事件，拼接该事件的 `data:` 行后再解析 JSON。UTF-8 字符和事件可以跨网络分块，不要对每次网络 chunk 直接 `JSON.parse`。

| 事件 | 接入方如何处理 |
| --- | --- |
| `RUN_STARTED` | 记录 `threadId`、`runId`，标记正在回答 |
| `TEXT_MESSAGE_START` | 按 `messageId` 建立消息，记住 `role` |
| `TEXT_MESSAGE_CONTENT` | 将 `delta` 追加到对应消息；对客户展示助手正文 |
| `TEXT_MESSAGE_END` | 标记该条正文输出结束；整个运行可能继续 |
| `TEXT_MESSAGE_CHUNK` | 兼容便捷文本事件，按可选 `messageId`、`role`、`delta` 处理 |
| `TOOL_CALL_START/ARGS/END/RESULT` | 可显示工具状态；工具结果不是客服正文 |
| `CUSTOM`，`name=TOOL_RESULT_CONTENT` | 处理 `value.images`、`value.artifacts`、`value.citations`，见下一节 |
| `CUSTOM`，`name=TOOL_CONFIRM_REQUIRED` | 显示 `value.pending`，等待用户决定，调用 R07 续跑 |
| `CUSTOM`，`name=CHAT_RUN_OPTIONS` | 接收服务端本轮配置；无需让客户覆盖这些开关 |
| `RUN_FINISHED` | 本次流结束；若有待审批，需要处理审批后续跑 |
| `RUN_ERROR` | 显示业务错误，保留已收到内容，不自动再次执行工具 |
| `REPLAY_CAUGHT_UP` | 重连回放已经追上实时流 |
| `REPLAY_GAP` 或 `RUN_ERROR.code=REPLAY_GAP` | 回放不可用，从 S03/S04 恢复已保存内容 |

保留 SSE 的 `id:` 字段。断网重连时把它放在 `Last-Event-ID` 请求头，并沿用 `X-GMD-FAQ-Thread-Id`。该 ID 与 JSON 的 `messageId`、`runId` 都不同。Runtime 重启或切节点后，流回放可能不可用；状态持久化不等于运行中的任务可自动迁移。多节点路由与共享文件说明见 [共享存储指南](../../docker/shared-storage.md)。

`GET /status/{sessionId}` 返回的 `running=false` 不能单独判断为业务成功，应结合 SSE 结束/错误、历史记录和 `pending`。中断时不要直接重发 S02/R01：普通追加消息没有业务幂等键，`runId` 也不是“重复调用只执行一次”的保证；公司后端要防重复提交并核对已保存状态。

## 5. 图片、文件与引用怎么取

### 5.1 智能体生成的图片或文件

流式事件示例，路径使用占位值，下载时必须使用服务器实际返回的 `filePath`：

```json
{
  "type":"CUSTOM",
  "name":"TOOL_RESULT_CONTENT",
  "value":{
    "toolCallId":"call-1",
    "toolName":"查询售后资料",
    "text":"找到相关资料",
    "images":[{
      "filePath":".tool-results/调用哈希/图片哈希.png",
      "fileName":"设备示意图.png",
      "extension":"png",
      "mimeType":"image/png",
      "sizeBytes":20480,
      "error":null,
      "sourcePath":"images/device.png"
    }],
    "artifacts":[{
      "filePath":"售后说明.docx",
      "fileName":"售后说明.docx",
      "extension":"docx",
      "sizeBytes":12345,
      "sourceTool":"生成说明",
      "createTime":"2026-10-27T10:00:00"
    }],
    "citations":[]
  }
}
```

`images[].error` 非空或 `filePath` 为空时显示图片不可用；不要请求占位路径。正常图片已保存为会话副本；`sourcePath` 只是用于将 Markdown 正文中的原始图片引用关联到副本。展示时优先匹配 `filePath/sourcePath/fileName`，正文未引用的图片可跟在来源工具步骤后。

下载正式会话里的图片或文档统一使用 W05：

```bash
FILE_PATH='售后说明.docx'
curl -sS --get "$BASE_URL/api/runtime/workspace/download" \
  -H "Authorization: Bearer $API_KEY" \
  --data-urlencode "sessionId=$SESSION_ID" \
  --data-urlencode "fileName=$FILE_PATH" \
  -o '售后说明.docx'
```

`fileName` 参数是完整相对路径，不一定只是文件名；查询参数要 URL 编码。整个工作区用 W01 列树、W07 打包；仅生成文件用 S04 查询。运行结束、页面恢复和重连后补查 S04，避免仅依赖某一次即时事件。

重新打开历史时，S03 返回 `role=tool` 的消息，其 `content` 是 JSON 字符串：解析后读取 `images`、`artifacts`、`citations`。只显示 `role=assistant` 文本会丢失这些媒体信息。S04 不代替历史工具消息，不能用它枚举所有工具图片副本。

### 5.2 浏览器怎样显示图片

推荐公司后端验证客户和会话关系后，携带 SkKey 代理 W05，将图片按元数据的 MIME 类型返回给自己的前端。普通 `<img src>` 不会添加 Authorization，不要把 SkKey 放进地址。

也可先由后端调用 W08 得到 `data` 预览令牌，然后拼出 P01 地址：

```text
/api/runtime/workspace/raw/预览令牌/sessionId/相对文件路径
```

令牌 5 分钟有效，绑定整个会话，不是永久图片地址或单文件令牌；过期后重新获取。路径逐段 URL 编码，保留目录间的 `/`。预览支持图片、HTML、文本等；Word、Excel、PDF 等未列入内联白名单的类型会下载，不提供通用 Office/PDF 转换服务。

### 5.3 用户给模型发送图片

1. F01：用 multipart 上传 `file`，得到附件 ID。
2. S02：保存用户本轮文字问题。
3. R01：同一问题运行时，把附件 ID 放进 `forwardedProps.fileIds`，例如 `{"fileIds":["2038965802636013599"]}`。
4. F02：以后读取原上传图片时使用附件 ID 下载，需要同一上传身份。

F01 的附件 ID 与 W02 返回的工作区路径不同。图片理解需要视觉模型；音频、视频也受模型能力限制。文档附件的文本读取还依赖文本提取和工具配置，不因上传成功就保证模型已读。SkKey 没有开放独立的 `/runtime/document/parse-text`、`file-text` 接口；需要智能体用文件工具处理文档时，可以先 W02 上传工作区，并在问题中明确指出返回的文件路径。

### 5.4 知识库引用

`citations` 可返回 `knowledgeId`、`knowledgeName`、`referenceId`、`fileName`、`filePath`、`content`，用于展示来源和原文片段。远端来源 `filePath` 不是平台文件下载地址；没有通用的 SkKey 知识库原文下载接口。知识库图片能否返回取决于检索配置及上游服务，本项目不会把模型随意生成的所有外部 Markdown 图片地址自动变成工作区副本。

## 6. 完整接口清单

以下路径均相对于 `baseUrl`。除 P01（预览令牌）和 G01（分享密钥交换）外，均可携带 SkKey，但仍受资源与会话归属限制。编号用于对应 Postman 请求。所有示例只对已授权的资源和会话使用。

### 01 首次联调：按顺序逐个发送

| 编号 | 方法 | 完整路径 | 用途、参数与返回 |
| --- | --- | --- | --- |
| A01 | GET | `/api/agent/definition/{agentId}` | **读取客服智能体**。路径 agentId 为智能体数据库 ID；从 data.agentCode 取得运行编号。返回配置，仅供接入后端使用。 |
| S01 | POST | `/api/agent/chat/session` | **开启新对话**。JSON：agentId 必填，title、initWorkspace 可选；保存 data.id 作为 sessionId。每点一次新对话执行一次。 |
| S02 | POST | `/api/agent/chat/session/{sessionId}/message` | **保存本次问题**。JSON：role="user"、content=问题。保存 data.id 作为本次 messageId；本接口只保存，不生成回答。 |
| R01 | POST | `/api/runtime/agui/run/{agentCode}` | **运行客服并接收流式回答**。路径使用 agentCode；JSON：threadId=sessionId、runId=每轮新 UUID、messages=本轮问题；详见调用示例。 |
| S03 | GET | `/api/agent/chat/session/{sessionId}/messages/current` | **读取当前对话历史**。返回 data 数组，包含当前分支的用户、助手和工具消息；工具 content 是含 images/artifacts/citations 的 JSON 字符串。 |
| S04 | GET | `/api/agent/chat/session/{sessionId}/artifacts` | **读取生成文件列表**。返回 data 数组：filePath、fileName、extension、sizeBytes、sourceTool、createTime 等；filePath 用于下载。 |

### 02 会话查询与消息分支

| 编号 | 方法 | 完整路径 | 用途、参数与返回 |
| --- | --- | --- | --- |
| S05 | GET | `/api/agent/chat/session/list` | **列出当前凭证的会话**。可选 query：agentId、isPinned；scope 固定 mine。不支持用 userId 切换为公司客户。 |
| S06 | GET | `/api/agent/chat/session/page` | **分页列出会话**。query：page 从 1 开始、size（默认 20），可选 agentId、isPinned；data.records 为列表，data.total 为总数。参数名为 page。 |
| S07 | GET | `/api/agent/chat/session/{sessionId}` | **读取会话详情**。返回 data：id、agentId、title、currentMessageId、canRead、canWrite 等；归档会话可读，但不能继续发送。 |
| S08 | GET | `/api/agent/chat/session/{sessionId}/messages/paged` | **向上翻页读取消息**。query：size 默认 50，beforeDepth 可选。首次不传 beforeDepth；下一页传上次 data.nextBeforeDepth；data.hasMore 判断是否继续。 |
| S09 | GET | `/api/agent/chat/session/{sessionId}/message-branches` | **查询消息的不同版本**。返回 data 数组：messageId、parentId、siblingIds、currentIndex、total；只有存在多个兄弟分支时才有条目。 |
| S10 | PUT | `/api/agent/chat/session/{sessionId}/current` | **切换消息游标**。query：messageId=属于本会话的数据库消息 ID；无 JSON body。仅更新聊天游标，不自动运行模型或重建推理状态。 |
| S11 | POST | `/api/agent/chat/session/{sessionId}/regenerate` | **保存用于重新生成的消息**。JSON：role、content。当前实现是在当前消息下追加并移动游标，返回新消息；不会自行生成回答，不能当作一键重答接口。 |
| S12 | PUT | `/api/agent/chat/session/{sessionId}/pin` | **置顶会话**。无 body；成功返回 R，code=200。 |
| S13 | PUT | `/api/agent/chat/session/{sessionId}/unpin` | **取消置顶**。无 body；成功返回 R，code=200。 |
| S14 | PUT | `/api/agent/chat/session/{sessionId}/title` | **修改会话标题**。query：title=新标题；无 JSON body。 |

### 03 运行、断线恢复与审批

| 编号 | 方法 | 完整路径 | 用途、参数与返回 |
| --- | --- | --- | --- |
| R02 | POST | `/api/runtime/agui/run` | **通过请求头选择智能体运行**。与 R01 使用同一 body，另带 X-Agent-Id: agentCode；也兼容 forwardedProps.agentId=agentCode。推荐首次接入使用 R01。 |
| R03 | GET | `/api/runtime/agui/status/{sessionId}` | **查询是否仍在回答**。直接返回 {running,state}，无 R 包装；state 为 RUNNING / STOPPING / COMPLETED。暂停审批另查 pending，不能只看 running。 |
| R04 | GET | `/api/runtime/agui/reconnect/{sessionId}` | **断线后接回回答流**。请求头 Last-Event-ID 可选，值来自最后收到的 SSE id；不是 runId 或 messageId。回放不可用时补查 S03/S04。 |
| R05 | POST | `/api/runtime/agui/stop/{sessionId}` | **停止当前回答**。无 body；直接返回 {accepted,stopped,state}。STOPPING 表示正在停止，等待 status 不再运行后再清理。 |
| R06 | GET | `/api/runtime/agui/pending/{sessionId}` | **查询等待确认的工具**。直接返回 {pending:[{toolUseId,name,input}]}；用于恢复确认界面，不让客户端修改 input。 |
| R07 | POST | `/api/runtime/agui/resume/{sessionId}` | **提交确认决定并继续回答**。JSON：decisions=[{toolUseId,name,approved}]，ID 与名称取自 pending；拒绝填 false。续跑返回 SSE，沿用服务端暂停状态。 |
| R08 | GET | `/api/runtime/agui/active-runs` | **列出当前凭证可读的活动会话**。直接返回会话 ID 数组；不是完整历史列表，也不能用于发现其他 Runtime 节点的全部活动任务。 |
| R09 | GET | `/api/runtime/agui/tool-images/{temporaryThreadId}` | **读取临时调试会话图片**。query：fileName 必须是已返回的 .tool-results/… 图片路径；只支持非纯数字的已登记临时 threadId。正式客服用 W05。 |

### 04 工作区、文件和图片

| 编号 | 方法 | 完整路径 | 用途、参数与返回 |
| --- | --- | --- | --- |
| W01 | GET | `/api/runtime/workspace/files` | **查看会话工作区文件树**。query：sessionId；返回 data 树，节点含 name、path、directory、extension、readableSize、lastModified、children。 |
| W02 | POST | `/api/runtime/workspace/upload` | **上传单个工作区文件**。multipart：sessionId 文本、file 文件；返回 data 为相对路径。文件在指定会话中，后续提问可让智能体读取该路径。 |
| W03 | POST | `/api/runtime/workspace/upload/batch` | **上传多个工作区文件**。multipart：sessionId，重复的 files 文件字段；返回 data 为路径数组。 |
| W04 | POST | `/api/runtime/workspace/upload/archive` | **上传 ZIP 并解压**。multipart：sessionId、file 压缩包；返回 data 为解压后的路径数组。需满足上传及工作区容量限制。 |
| W05 | GET | `/api/runtime/workspace/download` | **下载一个文件或对话图片**。query：sessionId、fileName=完整相对路径（可包含子目录）；返回二进制，Content-Type 为 application/octet-stream。 |
| W06 | POST | `/api/runtime/workspace/download/batch` | **打包下载指定文件**。query：sessionId；JSON body 为相对路径数组，如 ["说明.md","images/示意图.png"]；返回 ZIP。 |
| W07 | GET | `/api/runtime/workspace/download/all` | **打包下载整个工作区**。query：sessionId；返回 workspace-{sessionId}.zip。 |
| W08 | GET | `/api/runtime/workspace/preview-token` | **取得浏览器预览令牌**。query：sessionId；返回 data=令牌，5 分钟有效且绑定整个会话。用 P01 打开图片或支持的预览文件。 |
| W09 | GET | `/api/runtime/workspace/capacity` | **查询工作区容量**。query：sessionId；data 含 usedBytes、maxBytes、usedReadable、maxReadable、percent；容量以服务器配置为准。 |
| W10 | GET | `/api/runtime/workspace/exists` | **查询工作区目录是否存在**。query：sessionId；data 为 boolean。目录不存在不等于会话不存在；initWorkspace=true 可在创建时建目录。 |

### 05 用户上传的多模态附件

| 编号 | 方法 | 完整路径 | 用途、参数与返回 |
| --- | --- | --- | --- |
| F01 | POST | `/api/attach/upload` | **上传图片等消息附件**。multipart：file；返回 data=附件 ID。把此 ID 放入 R01 的 forwardedProps.fileIds，才会随本轮问题交给模型。 |
| F02 | GET | `/api/attach/download/{attachmentId}` | **下载之前上传的附件**。路径为 F01 返回的附件 ID；只允许下载当前调用身份上传的附件；返回二进制。 |
| A02 | GET | `/api/agent/definition/{agentId}/allow/file-type` | **查询该智能体允许的附件类型**。返回 data 字符串数组；实际图片、音频、视频理解能力还取决于所选模型和配置。 |

### 06 其他可用的 SkKey 接口

| 编号 | 方法 | 完整路径 | 用途、参数与返回 |
| --- | --- | --- | --- |
| A03 | GET | `/api/agent/definition/{agentId}/enabled/tools` | **查询智能体绑定的工具**。返回 data 工具配置数组；要求有智能体使用权；仅供接入后端使用，不是普通客户的配置管理入口。 |
| A04 | GET | `/api/agent/definition/{agentId}/enabled/skills` | **查询智能体绑定的技能**。返回 data 技能包数组；要求有智能体使用权。 |
| A05 | GET | `/api/agent/definition/{agentId}/enabled/workflows` | **查询智能体绑定的工作流**。返回 data 工作流数组；要求有智能体使用权。 |
| T01 | POST | `/api/runtime/tool/{toolId}/do` | **直接调用已配置工具**。路径 toolId 是工具标识（代码形参名 toolName），JSON 为工具所需参数对象；检查启用状态和使用权。独立于客服会话。 |
| U01 | GET | `/api/account/{keyOwnerAccountId}` | **查询 SkKey 创建者账号详情**。路径必须是创建密钥的真实账号 ID；不能用来查任意公司客户，也不是 SkKey 的随机会话身份 ID。 |
| M01 | POST | `/api/runtime/mcp` | **通过 MCP 协议查询或调用平台工具**。JSON-RPC：支持 initialize、ping、tools/list、tools/call 等；仅发布启用、PLATFORM + TENANT 且不需人工确认的工具。详见 MCP 示例。 |
| M02 | DELETE | `/api/runtime/mcp` | **关闭 MCP 会话**。当前 MCP 无会话状态，返回 HTTP 204、无响应体；不删除客服会话。 |
| M03 | GET | `/api/runtime/mcp` | **检查 MCP GET 推送能力**。当前明确返回 HTTP 405：不提供长期 GET SSE 推送。正常 MCP 调用使用 POST；不属于聊天恢复接口。 |

### 07 修改与清理：按需手工调用

| 编号 | 方法 | 完整路径 | 用途、参数与返回 |
| --- | --- | --- | --- |
| S15 | PUT | `/api/agent/chat/session/{sessionId}/current-message/content` | **修改当前消息文本**。JSON：content；修改会话游标指向的消息，不自动生成回答，也不重建模型持久状态。 |
| W11 | PUT | `/api/runtime/workspace/file` | **创建或覆盖文本文件**。JSON：sessionId、filePath、content；expectedContent 可选，为打开时的原文，用于冲突检测，省略则不比较。 |
| W12 | DELETE | `/api/runtime/workspace/file` | **删除单个工作区文件**。query：sessionId、filePath=相对路径；图片或文档删除后原引用将无法读取。 |
| W13 | DELETE | `/api/runtime/workspace/clear` | **清空工作区文件**。query：sessionId；删除工作区文件，不等同于开启新对话。 |
| S16 | DELETE | `/api/agent/chat/session/{sessionId}/messages` | **清空本会话消息和推理状态**。保留会话 ID 并重新创建根消息，工作区文件另由 W13 清理。返回 data.deleted；运行中先 R05 停止。 |
| S17 | DELETE | `/api/agent/chat/session/{sessionId}` | **删除一个会话**。删除会话、消息、状态和工作区；运行中先 R05 停止，再确认停止完成。 |
| S18 | DELETE | `/api/agent/chat/session/agent/{agentId}` | **删除当前调用身份在该智能体下的全部会话**。影响同一 SkKey 下该智能体的所有客户会话，不能作为单个公司客户的清空按钮。返回 data.deleted。 |

### 08 可选：短时预览与游客分享

| 编号 | 方法 | 完整路径 | 用途、参数与返回 |
| --- | --- | --- | --- |
| P01 | GET | `/api/runtime/workspace/raw/{previewToken}/{sessionId}/{filePath}` | **浏览器直接预览工作区文件**。使用 W08 的令牌；无需 Authorization。path 是相对路径，逐段 URL 编码。支持图片、HTML、文本等内联，其他格式下载。 |
| G01 | POST | `/api/auth/chat-key-token/{chatKey}` | **分享链接换取游客访问令牌**。无需原登录；管理员必须已启用、发布智能体并开启游客访问。返回 data.accessToken；每次交换产生新游客身份，历史需沿用原 token。 |

## 7. 按需调用的请求体

### 工具审批 R06 → R07

R06 的响应没有 `data` 包装：

```json
{"pending":[{"toolUseId":"call-1","name":"create_ticket","input":{"subject":"售后咨询"}}]}
```

让真实用户作决定后，R07 提交每项决定；要拒绝就将 `approved` 设为 false：

```json
{"decisions":[{"toolUseId":"call-1","name":"create_ticket","approved":true}],"memoryActive":true}
```

不重新提交工具参数，不用新 R01 代替续跑。`memoryActive` 是兼容字段，正式会话仍沿用服务端暂停时保存的设置。避免空 `decisions`；当前兼容逻辑会将空列表按全部允许处理。R07 返回新的 SSE 流。

### 批量下载 W06

```http
POST /api/runtime/workspace/download/batch?sessionId=2038965802636013570
Content-Type: application/json
```

```json
["售后说明.docx","images/设备示意图.png"]
```

响应是 ZIP 字节，不是 JSON。

### 文本写入 W11

```json
{"sessionId":"2038965802636013570","filePath":"notes/售后记录.md","content":"更新后的记录","expectedContent":"打开时的原文"}
```

需要检测别人或智能体是否改过文件时，携带打开时的 `expectedContent`。新建文件可省略该字段。该接口会改动真实文件，运行时应避免与智能体同时编辑同一文件。

### MCP 查询与工具调用 M01

同一个 `POST /api/runtime/mcp` 接口，按 JSON-RPC 的 `method` 区分功能：

```json
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
```

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"已发布工具的toolId","arguments":{"参数名":"值"}}}
```

还支持 `initialize`、`ping`，`notifications/initialized` 等无 id 通知返回 HTTP 202；`resources/list`、`prompts/list` 当前返回空列表。工具执行错误可能出现在 `result.isError=true`，协议错误在 `error`；与普通 R 返回不同。这里发布的是平台共享工具，不是客服智能体的对话接口，也不是工作区文件访问接口。

### 游客分享 G01

游客方式使用 `/api/auth/chat-key-token/{chatKey}` 换取 `data.accessToken`，再以 `Authorization: Bearer 该token` 调用允许 ChatKey 的会话、聊天、工作区接口。当前游客令牌有效期 24 小时，关闭游客访问或刷新分享密钥会撤销旧访问。每次重新交换都会产生新身份，不能靠反复交换来恢复原游客历史。公司账号体系接入推荐使用本文 SkKey + 公司后端映射方式。

### 工作流网关 API

网关 API 的路径、端口、参数和返回由运营人员创建的应用/工作流决定，没有固定的通用客服 URL。应用上线后可按 `http://执行节点IP:应用端口/API路径` 调用，或通过公司 HTTPS 入口转发；使用 TOKEN 鉴权时携带平台凭证。网关工作流返回结构不等于本教程的 AG-UI 聊天协议。详见 [网关端口与部署说明](../../docker/README.md#网关-api-端口访问说明)。

## 8. 当前不能直接当作 SkKey 接口使用的功能

| 功能 | 当前情况 |
| --- | --- |
| 任意账号查询、给某客户传 `userId` 切身份 | 不支持；U01 只能查密钥创建者，会话身份来自服务器凭证 |
| 智能体分页目录、创建、编辑、发布、分享开关 | 管理端功能，不能因知道路径就用 SkKey 调用；由运营提供 agentId |
| 附件详情 `/attach/selectOne`、批量元数据 `/attach/list`、分片上传、批量附件下载、附件删除 | 这些端点未开放 SkKey；接入后端保存 F01 的原始名称、格式与返回 ID，文件用 F02 下载 |
| `/agent/chat/session/access`、团队/全部会话和游客批量清理 | 账号与管理权限功能，不能用来列某个公司客户的会话 |
| 运行中消息排队、插队、补充要求、方案模式审批、实际用量查询 | 当前仅正式登录账号可用，SkKey 不能直接使用；普通工具审批使用 R06/R07 |
| 统一的 OpenAI `/v1/chat/completions` 客服代理 | 当前无此固定对外端点，聊天使用 AG-UI / SSE；外部平台只支持 OpenAI 协议时需要适配 |
| 已删除文件、会话的永久图片地址 | 不提供；文件和图片仍归属于会话，删除或清理后引用会失效 |

## 9. 接入检查与源码依据

先用 A01 → S01 → S02 → R01 → S03 跑通纯文字，再验证 F01 发图、W02 传文件、W05 下载、W08/P01 预览，以及开启新会话、重连、停止和审批。用两个公司客户验证会话和文件不会互相访问。同一 SkKey 下所有请求是同一调用身份；重新创建另一把 SkKey 会产生新的身份，旧会话不会自动移交。

普通读写接口以当前代码声明的参数为准，不能把表中路径参数和 query 字段塞进任意 JSON body。会话列表的分页参数是 `page`，不是旧界面示例中出现的 `current`。`regenerate` 当前只是保存消息，切换分支也不自动重建模型历史；首次客服接入可先实现顺序对话。

本教程提供当前源码的接口契约与调用示例。最终可用性需要在实际部署环境，使用真实密钥与智能体完成上述联调；本文的示例 ID、路径与响应不是生产调用记录。

源码入口：

- [会话与消息 Controller](../../GMD-FAQ-RUNNER-CONSOLE/src/main/java/com/faq/server/main/console/agent/ChatSessionController.java)
- [智能体查询 Controller](../../GMD-FAQ-RUNNER-CONSOLE/src/main/java/com/faq/server/main/console/agent/AgentDefinitionController.java)
- [运行、重连、停止与审批](../../GMD-FAQ-RUNNER-RUNTIME/src/main/java/io/agentscope/spring/boot/agui/mvc/AguiRestController.java)
- [工作区接口](../../GMD-FAQ-RUNNER-RUNTIME/src/main/java/com/faq/server/main/runtime/workspace/WorkspaceController.java)
- [预览令牌与文件预览](../../GMD-FAQ-RUNNER-RUNTIME/src/main/java/com/faq/server/main/runtime/workspace/WorkspacePreviewController.java)
- [附件上传下载](../../GMD-FAQ-RUNNER-CONSOLE/src/main/java/com/faq/server/main/console/resource/AttachController.java)
- [工具直接调用](../../GMD-FAQ-RUNNER-RUNTIME/src/main/java/com/faq/server/main/runtime/endpoint/ToolEndPoint.java)
- [MCP 端点](../../GMD-FAQ-RUNNER-RUNTIME/src/main/java/com/faq/server/main/runtime/mcp/McpServerEndPoint.java)
- [账号查询](../../GMD-FAQ-RUNNER-CONSOLE/src/main/java/com/faq/server/main/console/account/AccountController.java)
