import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

test('reconnect keeps the cursor within a thread and resets it across threads', { timeout: 15000 }, async () => {
  const server = await createServer({
    configFile: false,
      server: { middlewareMode: true, ws: false },
    optimizeDeps: { noDiscovery: true },
    ssr: { noExternal: ['fast-json-patch'] },
    plugins: [{
      name: 'agui-cursor-test-stubs',
      enforce: 'pre',
      resolveId(id) {
        if (['@/api/auth', '@/utils/auth', '@/config/setting.ts'].includes(id)) {
          return `\0agui-test:${id}`
        }
      },
      load(id) {
        if (id === '\0agui-test:@/api/auth') return 'export const refreshToken = () => {}'
        if (id === '\0agui-test:@/config/setting.ts') return 'export default { tokenHeader: "Authorization" }'
        if (id === '\0agui-test:@/utils/auth') {
          return `
            export const getToken = () => ''
            export const isShareRoute = () => false
            export const removeShareSession = () => {}
            export const getRefreshToken = () => ''
            export const setToken = () => {}
            export const setRefreshToken = () => {}
            export const removeToken = () => {}
            export const removeRefreshToken = () => {}
          `
        }
      }
    }],
    resolve: {
      alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) }
    }
  })
  const originalFetch = globalThis.fetch
  try {
    const { AgentClient } = await server.ssrLoadModule('/src/api/agui/agent-client.ts')
    const requests = []
    globalThis.fetch = async (url, options) => {
      const threadId = String(url).split('/').at(-1)
      const lastEventId = new Headers(options.headers).get('Last-Event-ID')
      requests.push({ threadId, lastEventId })
      const frame = threadId === 'thread-a'
        ? 'id: run-a:1\ndata: {"type":"RUN_FINISHED"}\n\n'
        : 'id: run-b:1\ndata: {"type":"RUN_STARTED"}\n\n'
      return new Response(frame, { headers: { 'Content-Type': 'text/event-stream' } })
    }

    const client = new AgentClient('/unused')
    await client.reconnect('/api/runtime/agui/reconnect/thread-a')
    assert.equal(client.hasTerminalEvent, true)

    await client.reconnect('/api/runtime/agui/reconnect/thread-b')
    assert.equal(client.hasTerminalEvent, false)
    await client.reconnect('/api/runtime/agui/reconnect/thread-b')

    assert.deepEqual(requests, [
      { threadId: 'thread-a', lastEventId: null },
      { threadId: 'thread-b', lastEventId: null },
      { threadId: 'thread-b', lastEventId: 'run-b:1' }
    ])

    await client.reconnect('/api/runtime/agui/reconnect/thread-b', true)
    assert.deepEqual(requests.at(-1), { threadId: 'thread-b', lastEventId: null })

    const replies = []
    let executedTools = 0
    const replay = new AgentClient('/unused', {}, {
      onTextMessageEnd: (_event, text) => replies.push(text),
    }, { lookup: async () => { executedTools++ } })
    globalThis.fetch = async () => new Response([
      'id: run-c:1\ndata: {"type":"TEXT_MESSAGE_START","messageId":"reply","role":"assistant"}\n\n',
      'id: run-c:2\ndata: {"type":"TEXT_MESSAGE_CONTENT","messageId":"reply","delta":"完整内容"}\n\n',
      'id: run-c:3\ndata: {"type":"TEXT_MESSAGE_END","messageId":"reply"}\n\n',
      'id: run-c:4\ndata: {"type":"TOOL_CALL_START","toolCallId":"tool","toolCallName":"lookup"}\n\n',
      'id: run-c:5\ndata: {"type":"TOOL_CALL_ARGS","toolCallId":"tool","delta":"{}"}\n\n',
      'id: run-c:6\ndata: {"type":"TOOL_CALL_END","toolCallId":"tool"}\n\n',
    ].join(''))
    await replay.reconnect('/reconnect/thread-c')
    await replay.reconnect('/reconnect/thread-c', true)
    assert.deepEqual(replies, ['完整内容', '完整内容'])
    assert.equal(replay.messages.length, 1)
    assert.equal(executedTools, 0, '回放展示不能再次执行工具')

    const staleRequests = []
    globalThis.fetch = async (_url, options) => {
      const cursor = new Headers(options.headers).get('Last-Event-ID')
      staleRequests.push(cursor)
      return new Response(cursor
        ? 'data: {"type":"REPLAY_GAP","message":"运行已变更"}\n\n'
        : 'id: run-d:1\ndata: {"type":"RUN_FINISHED"}\n\n')
    }
    await replay.reconnect('/reconnect/thread-c')
    assert.deepEqual(staleRequests, ['run-c:6'])
    assert.equal(replay.replayGapMessage, '运行已变更')
    assert.equal(replay.hasTerminalEvent, true)

    const readers = {}, signals = {}, seen = []
    globalThis.fetch = async (url, options) => {
      signals[url] = options.signal
      return new Response(new ReadableStream({ start(controller) { readers[url] = controller } }))
    }
    const switching = new AgentClient('/unused', {}, { onEvent: event => seen.push(event.type) })
    const old = switching.reconnect('/reconnect/old')
    await new Promise(resolve => setImmediate(resolve))
    switching.abort()
    const current = switching.reconnect('/reconnect/new')
    await new Promise(resolve => setImmediate(resolve))
    readers['/reconnect/old'].enqueue(new TextEncoder().encode('data: {"type":"RUN_ERROR","message":"旧连接"}\n\n'))
    readers['/reconnect/old'].close()
    await old
    assert.deepEqual(seen, [], '已断开的旧连接不能把迟到事件写进新会话')
    switching.abort()
    assert.equal(signals['/reconnect/new'].aborted, true, '旧连接结束不能清除新连接的中止句柄')
    readers['/reconnect/new'].close()
    await current
  } finally {
    globalThis.fetch = originalFetch
    await server.close()
  }
})
