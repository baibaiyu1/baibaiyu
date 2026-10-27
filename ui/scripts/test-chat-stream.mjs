import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { createRenderer, ref } from 'vue'

const stubs = {
  'ant-design-vue': 'export const message = { error(value) { globalThis.__chatStreamErrors?.push(value) }, warning(value) { globalThis.__chatStreamWarnings?.push(value) } }',
  '@/stores': 'export const useAccountStore = () => ({ userInfo: { id: "account" } })',
  '@/api/auth': 'export const refreshToken = () => {}',
  '@/config/setting.ts': 'export default { tokenHeader: "Authorization" }',
  '@/utils/auth': `
    export const getToken = () => ''
    export const isShareRoute = () => false
    export const removeShareSession = () => {}
    export const getRefreshToken = () => ''
    export const setToken = () => {}
    export const setRefreshToken = () => {}
    export const removeToken = () => {}
    export const removeRefreshToken = () => {}
  `,
  '@/api/agui/request': `
    export const getAgentRunURL = () => '/run'
    export const getSSEHeaders = () => ({})
    export const getRESTHeaders = () => ({})
    export const getReconnectURL = id => '/reconnect/' + id
    export const getResumeURL = id => '/resume/' + id
    export const getPendingURL = id => '/pending/' + id
    export const getStatusURL = id => '/status/' + id
    export const getStopURL = id => '/stop/' + id
    export const getActiveRunsURL = () => '/active-runs'
  `,
}

const renderer = createRenderer({
  insert() {},
  remove() {},
  createElement: () => ({}),
  createText: () => ({}),
  createComment: () => ({}),
  setText() {},
  setElementText() {},
  parentNode: () => null,
  nextSibling: () => null,
  patchProp() {},
})

test('completed replies remain distinct from reasoning that shares the backend message ID',
  { timeout: 15000 }, async (t) => {
    const server = await createServer({
      configFile: false,
      server: { middlewareMode: true, ws: false },
      optimizeDeps: { noDiscovery: true },
      ssr: { noExternal: ['fast-json-patch'] },
      resolve: { alias: [
        ...Object.keys(stubs).map((id) => ({
          find: id,
          replacement: `\0chat-stream-test:${id}`,
        })),
        { find: /^@\/api\/agui$/, replacement: fileURLToPath(new URL('../src/api/agui/index.ts', import.meta.url)) },
        { find: '@', replacement: fileURLToPath(new URL('../src', import.meta.url)) },
      ] },
      plugins: [{
        name: 'chat-stream-test-stubs',
        enforce: 'pre',
        resolveId(id, importer) {
          if (id.startsWith('\0chat-stream-test:')) return id
          if (id === './request' && importer?.replaceAll('\\', '/').endsWith('/api/agui/index.ts')) {
            return '\0chat-stream-test:@/api/agui/request'
          }
          if (Object.hasOwn(stubs, id)) return `\0chat-stream-test:${id}`
        },
        load(id) {
          if (id.startsWith('\0chat-stream-test:')) {
            return stubs[id.slice('\0chat-stream-test:'.length)]
          }
        },
      }],
    })
    const originalFetch = globalThis.fetch
    try {
      const { useChatStream } = await server.ssrLoadModule('/src/composables/chat/useChatStream.ts')
      const reasoning = [
        { type: 'REASONING_MESSAGE_START', messageId: 'reply', role: 'assistant' },
        { type: 'REASONING_MESSAGE_CONTENT', messageId: 'reply', delta: '思考过程' },
        { type: 'REASONING_MESSAGE_END', messageId: 'reply' },
      ]
      const text = [
        { type: 'TEXT_MESSAGE_START', messageId: 'reply', role: 'assistant' },
        { type: 'TEXT_MESSAGE_CONTENT', messageId: 'reply', delta: '正式回复' },
      ]

      async function consume(events, showToolProcess, planning = false, reconnecting = false) {
        const saved = []
        const snapshots = []
        const speech = []
        let submitted
        let stream
        const app = renderer.createApp({
          setup() {
            stream = useChatStream(ref('agent'), ref({ agentCode: 'agent' }), ref('session'),
              undefined, undefined, undefined, showToolProcess === undefined ? undefined : ref(showToolProcess), (message) => {
                const previous = saved.find(item => item.id === message.id)
                if (previous) Object.assign(previous, message)
                else saved.push(message)
              }, ref(planning), undefined, event => speech.push({ ...event }))
            return () => null
          },
        })
        app.mount({})
        stream.client.use((event, next) => {
          next(event)
          snapshots.push({
            type: event.type,
            id: stream.streamingMessageId.value,
            role: stream.streamingRole.value,
            content: stream.streamingContent.value,
            saved: [...saved],
            tools: stream.toolCallsInProgress.value.map(tool => ({ ...tool })),
          })
        })
        globalThis.fetch = async (_url, options) => { submitted = options.body ? JSON.parse(options.body) : undefined; return new Response([
          { type: 'RUN_STARTED', threadId: 'session', runId: 'run' },
          ...events,
          { type: 'TEXT_MESSAGE_END', messageId: 'reply' },
          { type: 'RUN_FINISHED', threadId: 'session', runId: 'run' },
        ].map((event) => `data: ${JSON.stringify(event)}\n\n`).join(''), {
          headers: { 'Content-Type': 'text/event-stream' },
        }) }
        try {
          if (reconnecting) await stream.reconnect('session', true)
          else await stream.sendMessage('问题', [{ id: 'user', role: 'user', content: '问题' }])
          assert.equal(stream.isRunning.value, false)
          assert.equal(stream.streamingMessageId.value, null)
          return { saved, snapshots, speech, plan: stream.currentPlan.value, submitted }
        } finally {
          app.unmount()
        }
      }

      await t.test('first request carries plan mode independently of the todo switch', async () => {
        const { submitted } = await consume(text, false, true)
        assert.equal(submitted.forwardedProps.planMode, true)
        assert.equal(submitted.forwardedProps.planActive, false)
      })

      await t.test('sensitive word warnings are visible while the answer continues', async () => {
        const previous = globalThis.__chatStreamWarnings
        globalThis.__chatStreamWarnings = []
        try {
          const { saved } = await consume([
            { type: 'CUSTOM', name: 'SENSITIVE_WORD_WARNING', value: { message: '输入内容命中敏感词，本次对话继续。' } },
            ...text,
            { type: 'CUSTOM', name: 'SENSITIVE_WORD_WARNING', value: { message: '模型回复命中敏感词，请注意甄别。' } },
          ])
          assert.deepEqual(globalThis.__chatStreamWarnings, ['输入内容命中敏感词，本次对话继续。', '模型回复命中敏感词，请注意甄别。'])
          assert.ok(saved.some(item => item.role === 'assistant' && item.content === '正式回复'))
        } finally { globalThis.__chatStreamWarnings = previous }
      })

      await t.test('reasoning and the completed answer have unique display identities', async () => {
        const { saved, snapshots } = await consume([...reasoning, ...text])
        assert.deepEqual(saved.map(({ role, content }) => ({ role, content })), [
          { role: 'thinking', content: '思考过程' },
          { role: 'assistant', content: '正式回复' },
        ])
        assert.equal(new Set(saved.map((message) => message.id)).size, saved.length)
        const textStart = snapshots.find((snapshot) => snapshot.type === 'TEXT_MESSAGE_START')
        assert.ok(textStart.saved.every((message) => message.id !== textStart.id))
      })

      await t.test('a repeated reasoning end cannot clear an active answer', async () => {
        const { saved, snapshots } = await consume([
          ...reasoning, ...text, { type: 'REASONING_MESSAGE_END', messageId: 'reply' },
        ])
        const repeatedEnd = snapshots.filter((snapshot) => snapshot.type === 'REASONING_MESSAGE_END').at(-1)
        assert.equal(repeatedEnd.role, 'assistant')
        assert.equal(repeatedEnd.content, '正式回复')
        assert.equal(saved.filter((message) => message.role === 'assistant').length, 1)
        assert.equal(saved.at(-1).content, '正式回复')
      })

      await t.test('late reasoning ends save their own buffer while text remains active', async () => {
        const { saved, snapshots } = await consume([
          ...reasoning.slice(0, 2), ...text, reasoning[2],
        ])
        const reasoningEnd = snapshots.find((snapshot) => snapshot.type === 'REASONING_MESSAGE_END')
        assert.equal(reasoningEnd.role, 'assistant')
        assert.equal(reasoningEnd.content, '正式回复')
        assert.deepEqual(saved.map(({ role, content }) => ({ role, content })), [
          { role: 'thinking', content: '思考过程' },
          { role: 'assistant', content: '正式回复' },
        ])
      })

      await t.test('plain text replies still complete normally', async () => {
        const { saved } = await consume(text)
        assert.deepEqual(saved.map(({ role, content }) => ({ role, content })), [
          { role: 'assistant', content: '正式回复' },
        ])
      })

      await t.test('voice receives live assistant deltas before run finishes, excluding reasoning and tools', async () => {
        const { speech } = await consume([
          ...reasoning,
          { type: 'TOOL_CALL_START', toolCallId: 'tool', toolCallName: 'search' },
          { type: 'TOOL_CALL_ARGS', toolCallId: 'tool', delta: '{"secret":"工具参数"}' },
          { type: 'TOOL_CALL_END', toolCallId: 'tool' },
          { type: 'TOOL_CALL_RESULT', toolCallId: 'tool', content: '工具返回' },
          ...text,
          { type: 'TEXT_MESSAGE_CONTENT', messageId: 'reply', delta: '。第二句。' },
        ])
        assert.deepEqual(speech.filter(e => e.type === 'text' && e.delta).map(e => e.delta), ['正式回复', '。第二句。'])
        assert.equal(speech[0].type, 'start')
        assert.equal(speech.at(-1).type, 'finish')
        assert.ok(speech.every(e => e.sessionId === 'session' && e.runId === 'run'))
        assert.doesNotMatch(JSON.stringify(speech), /思考过程|工具参数|工具返回/)
      })

      await t.test('voice marks replayed text and only resumes live deltas after catch-up', async () => {
        const { speech } = await consume([
          ...text,
          { type: 'REPLAY_CAUGHT_UP' },
          { type: 'TEXT_MESSAGE_CONTENT', messageId: 'reply', delta: '。后续正文。' },
        ], undefined, false, true)
        assert.equal(speech.find(e => e.type === 'text').replay, true)
        assert.deepEqual(speech.filter(e => e.type === 'text' && !e.replay && e.delta).map(e => e.delta), ['。后续正文。'])
      })

      await t.test('AgentScope 2 todo events update the plan with tool details hidden', async () => {
        const todos = [
          { content: '检查数据', status: 'completed' },
          { content: '生成报告', status: 'in_progress' },
          { content: '验证报告', status: 'pending' },
        ]
        const { plan } = await consume([
          { type: 'TOOL_CALL_START', toolCallId: 'todo', toolCallName: 'todo_write' },
          { type: 'TOOL_CALL_ARGS', toolCallId: 'todo', delta: JSON.stringify({ todos }) },
          { type: 'TOOL_CALL_END', toolCallId: 'todo' },
          { type: 'TOOL_CALL_RESULT', toolCallId: 'todo', content: '2 open todo(s):\n- [x] 检查数据\n- [~] 生成报告\n- [ ] 验证报告' },
          ...text,
        ], false)
        assert.deepEqual(plan.subtasks.map(task => [task.name, task.state]), [
          ['检查数据', 'done'], ['生成报告', 'in_progress'], ['验证报告', 'todo'],
        ])
      })

      await t.test('a rejected todo update does not invent a plan', async () => {
        const { plan } = await consume([
          { type: 'TOOL_CALL_START', toolCallId: 'todo', toolCallName: 'todo_write' },
          { type: 'TOOL_CALL_ARGS', toolCallId: 'todo', delta: JSON.stringify({ todos: [{ content: '危险操作', status: 'completed' }] }) },
          { type: 'TOOL_CALL_END', toolCallId: 'todo' },
          { type: 'TOOL_CALL_RESULT', toolCallId: 'todo', content: 'Error: 用户拒绝执行' },
          ...text,
        ], false)
        assert.equal(plan, null)
      })

      for (const serverShowsTools of [false, true]) {
        await t.test(`server run options override stale browser tool visibility: ${serverShowsTools}`, async () => {
          const { saved, snapshots } = await consume([
            { type: 'CUSTOM', name: 'CHAT_RUN_OPTIONS', value: { toolProcessActive: serverShowsTools } },
            { type: 'TOOL_CALL_START', toolCallId: 'call', toolCallName: 'lookup' },
            { type: 'TOOL_CALL_ARGS', toolCallId: 'call', delta: '{"query":"test"}' },
            { type: 'CUSTOM', name: 'TOOL_CONFIRM_REQUIRED', value: { pending: [{ toolUseId: 'call', name: 'lookup', input: { query: 'test' } }] } },
            { type: 'TOOL_CALL_RESULT', toolCallId: 'call', content: '查询结果', role: 'tool' }, ...text,
          ], !serverShowsTools)
          assert.equal(snapshots.find(snapshot => snapshot.type === 'TOOL_CALL_START').tools.length, serverShowsTools ? 1 : 0)
          const confirmation = snapshots.find(snapshot => snapshot.tools.some(tool => tool.needConfirm))
          assert.ok(confirmation, '审批独立于详情显示开关')
          assert.equal(saved.some(message => message.role === 'tool'), serverShowsTools)
        })
      }

      await t.test('事件缓存失效后自动补查历史直到任务结束，并恢复待确认工具，不重发运行请求', async () => {
        const requests = []
        const recovered = []
        const errors = []
        globalThis.__chatStreamErrors = errors
        let statusChecks = 0
        let stream
        const app = renderer.createApp({
          setup() {
            stream = useChatStream(ref('agent'), ref({ agentCode: 'agent' }), ref('session'),
              undefined, undefined, undefined, undefined, undefined, undefined, async (sid, running) => {
                recovered.push({ sid, running, busy: stream.isRunning.value })
                if (!running) stream.restorePending([{ toolUseId: 'approval', name: 'write', input: {} }])
              })
            return () => null
          },
        })
        app.mount({})
        globalThis.fetch = async (url, options = {}) => {
          requests.push({ url, method: options.method ?? 'GET' })
          if (url === '/status/session') {
            const running = statusChecks++ === 0
            return Response.json({ running, state: running ? 'RUNNING' : 'COMPLETED' })
          }
          assert.equal(url, '/reconnect/session')
          return new Response('data: {"type":"REPLAY_GAP","message":"事件缓存已过期，无法恢复完整对话，请刷新会话"}\n\n')
        }
        try {
          await stream.reconnect('session')
          assert.deepEqual(recovered, [
            { sid: 'session', running: true, busy: true },
            { sid: 'session', running: false, busy: true },
          ])
          assert.equal(stream.isRunning.value, false)
          assert.equal(stream.isReplaying.value, false)
          assert.equal(stream.toolCallsInProgress.value[0].needConfirm, true)
          assert.equal(stream.toolCallsInProgress.value[0].args, '{}')
          assert.deepEqual(errors, [])
          assert.deepEqual(requests.map(request => request.method), ['GET', 'GET', 'GET'])
        } finally {
          app.unmount()
          delete globalThis.__chatStreamErrors
        }
      })

      for (const showDetails of [false, true]) {
        for (const outcome of ['success', 'empty', 'error', 'interrupted']) {
          await t.test(`knowledge query progress stays visible with details ${showDetails} until ${outcome}`, async () => {
            const { snapshots } = await consume([
              ...reasoning,
              { type: 'TOOL_CALL_START', toolCallId: 'knowledge', toolCallName: 'retrieve_knowledge' },
              { type: 'TOOL_CALL_END', toolCallId: 'knowledge' },
              ...outcome === 'interrupted'
                ? [{ type: 'RUN_ERROR', message: '查询中断' }]
                : [{ type: 'TOOL_CALL_RESULT', toolCallId: 'knowledge', content: outcome === 'empty' ? '' : outcome, role: 'tool' }],
              ...text,
            ], showDetails)
            assert.equal(snapshots.find(snapshot => snapshot.type === 'TOOL_CALL_START').tools[0].name, 'retrieve_knowledge')
            assert.equal(snapshots.find(snapshot => snapshot.type === 'TOOL_CALL_END').tools.length, 1, '结束参数流不能提前结束查询状态')
            assert.equal(snapshots.find(snapshot => snapshot.type === (outcome === 'interrupted' ? 'RUN_ERROR' : 'TOOL_CALL_RESULT')).tools.length, 0)
          })
        }
        await t.test(`automatic retrieval reports progress independently of tool details ${showDetails}`, async () => {
          const start = { type: 'CUSTOM', name: 'KNOWLEDGE_RETRIEVAL', value: { toolCallId: 'auto', status: 'STARTED' } }
          const { snapshots, saved } = await consume([
            start, start,
            { type: 'CUSTOM', name: 'KNOWLEDGE_RETRIEVAL', value: { toolCallId: 'auto', status: 'FINISHED' } },
            ...text,
          ], showDetails)
          const progress = snapshots.filter(snapshot => snapshot.type === 'CUSTOM')
          assert.deepEqual(progress.map(snapshot => snapshot.tools.length), [1, 1, 0])
          assert.equal(saved.some(message => message.role === 'tool'), false, '查询状态本身不应伪造工具历史')
        })
        for (const automatic of [false, true]) {
          await t.test(`knowledge citations survive ${automatic ? 'automatic' : 'agentic'} retrieval with details ${showDetails ? 'shown' : 'hidden'}`, async () => {
            const citation = { knowledgeId: '42', knowledgeName: '设备库', referenceId: '1',
              fileName: '设备手册.pdf', filePath: '设备手册.pdf', content: '使用前检查电源' }
            const { saved } = await consume([
              ...automatic ? [] : [{ type: 'TOOL_CALL_START', toolCallId: 'knowledge', toolCallName: 'retrieve_knowledge' }],
              { type: 'CUSTOM', name: 'TOOL_RESULT_CONTENT', value: { toolCallId: 'knowledge', toolName: 'retrieve_knowledge',
                text: '使用前检查电源', citations: [citation] } },
              ...automatic ? [] : [{ type: 'TOOL_CALL_RESULT', toolCallId: 'knowledge', content: '使用前检查电源', role: 'tool' }],
              ...text,
            ], showDetails)
            const tools = saved.filter(message => message.role === 'tool')
            assert.equal(tools.length, 1)
            const tool = JSON.parse(tools[0].content)
            assert.deepEqual(tool.citations, [citation])
            assert.equal(tool.hideDetails, !showDetails)
          })
        }
        await t.test(`tool images survive completion and replay with details ${showDetails ? 'shown' : 'hidden'}`, async () => {
          const images = [
            { filePath: '.tool-results/call/red.png', fileName: '红色截图.png', extension: 'png', mimeType: 'image/png', sizeBytes: 123, error: null },
            { filePath: '.tool-results/call/blue.png', fileName: '蓝色截图.png', extension: 'png', mimeType: 'image/png', sizeBytes: 123, error: null },
          ]
          const resultEvent = { type: 'CUSTOM', name: 'TOOL_RESULT_CONTENT', value: { toolCallId: 'call', toolName: 'screenshot', text: '截图完成', images } }
          const { saved } = await consume([
            { type: 'TOOL_CALL_START', toolCallId: 'call', toolCallName: 'screenshot' },
            { type: 'TOOL_CALL_ARGS', toolCallId: 'call', delta: '{"page":"test"}' },
            { type: 'TOOL_CALL_END', toolCallId: 'call' },
            resultEvent, resultEvent,
            { type: 'TOOL_CALL_RESULT', toolCallId: 'call', messageId: 'tool-msg', content: '截图完成', role: 'tool' },
            ...text,
          ], showDetails)
          assert.equal(saved.filter(message => message.role === 'tool').length, 1)
          const tool = JSON.parse(saved.find(message => message.role === 'tool').content)
          assert.deepEqual(tool.images, images)
          assert.equal(tool.hideDetails, !showDetails)
          const { toolResultImages } = await server.ssrLoadModule('/src/utils/chat/toolResult.ts')
          assert.equal(toolResultImages(saved).length, 2)
          assert.equal(toolResultImages(JSON.parse(JSON.stringify(saved))).length, 2)
          assert.equal(saved.at(-1).content, '正式回复')
        })
        await t.test(`artifact-only tool results keep their attachment when details are ${showDetails ? 'shown' : 'hidden'}`, async () => {
          const artifact = { filePath: '趋势图.png', fileName: '趋势图.png', extension: 'png',
            sizeBytes: 100, sourceTool: 'execute_shell_command', createTime: '2026-10-16T10:00:00' }
          const { saved } = await consume([
            { type: 'TOOL_CALL_START', toolCallId: 'plot', toolCallName: 'execute_shell_command' },
            { type: 'CUSTOM', name: 'TOOL_RESULT_CONTENT', value: { toolCallId: 'plot', toolName: 'execute_shell_command',
              text: '完成', artifacts: [artifact] } },
            { type: 'TOOL_CALL_RESULT', toolCallId: 'plot', content: '完成', role: 'tool' }, ...text,
          ], showDetails)
          const tool = JSON.parse(saved.find(message => message.role === 'tool').content)
          assert.equal(tool.artifacts[0].filePath, artifact.filePath)
          assert.equal(tool.artifacts[0].sessionId, 'session')
          assert.equal(tool.hideDetails, !showDetails)
        })
      }
    } finally {
      globalThis.fetch = originalFetch
      await server.close()
    }
  })
