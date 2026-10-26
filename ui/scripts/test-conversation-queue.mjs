import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { createRenderer, ref, nextTick } from 'vue'

const renderer = createRenderer({ insert() {}, remove() {}, createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
  setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null, patchProp() {} })
test('消息队列恢复时隔离会话，网络重试保持幂等，权限关闭后停止查询', { timeout: 60000 }, async () => {
  const server = await createServer({ configFile: false, server: { middlewareMode: true }, optimizeDeps: { noDiscovery: true },
    resolve: { alias: [{ find: '@/api/agui/queue', replacement: '\0queue-fixture' }, { find: '@', replacement: fileURLToPath(new URL('../src', import.meta.url)) }] },
    plugins: [{ name: 'fixture', resolveId: id => id === '\0queue-fixture' ? id : undefined,
      load: id => id === '\0queue-fixture' ? `export const getQueue = id => globalThis.queueFixture.get(id);
        export const enqueueMessage = (...args) => globalThis.queueFixture.enqueue(...args);` : undefined }] })
  const saved = globalThis.document
  const savedCrypto = Object.getOwnPropertyDescriptor(globalThis, 'crypto')
  Object.defineProperty(globalThis, 'crypto', { configurable: true, value: { getRandomValues: globalThis.crypto.getRandomValues.bind(globalThis.crypto) } })
  globalThis.document = { hidden: false, addEventListener() {}, removeEventListener() {} }
  const session = ref('old'), enabled = ref(true), received = [], submitted = [], gets = []
  let finishOld, api, app
  const current = { state: 'RUNNING', paused: false, runId: 'new-run', messages: [] }
  globalThis.queueFixture = {
    get: id => { gets.push(id); return id === 'old' ? new Promise(resolve => { finishOld = resolve }) : Promise.resolve(current) },
    enqueue: (...args) => { submitted.push(args); return Promise.reject(new Error('网络中断')) },
  }
  const settle = async () => { await nextTick(); await new Promise(resolve => setImmediate(resolve)); await nextTick() }
  try {
    const { useConversationQueue } = await server.ssrLoadModule('/src/composables/chat/useConversationQueue.ts')
    app = renderer.createApp({ setup() { api = useConversationQueue(session, enabled, value => received.push(value)); return () => null } }); app.mount({})
    session.value = 'new'; await settle()
    finishOld({ state: 'IDLE', runId: 'old-run', messages: [] }); await settle()
    assert.equal(api.snapshot.value.runId, 'new-run')
    assert.equal(received.some(value => value.runId === 'old-run'), false)
    const options = { memoryActive: true, planActive: false, toolProcessActive: true, fileIds: ['123'], planMode: false }
    await assert.rejects(api.enqueue('下一轮', options)); await assert.rejects(api.enqueue('下一轮', options))
    assert.match(submitted[0][1], /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
      '局域网 HTTP 中没有 randomUUID 时仍能生成合法请求标识并发起请求')
    assert.equal(submitted[0][1], submitted[1][1])
    const accepted = { id: 'one', requestId: submitted[0][1], content: '下一轮', options, state: 'QUEUED', runId: null }
    globalThis.queueFixture.enqueue = async () => accepted
    let completeRefresh
    globalThis.queueFixture.get = () => new Promise(resolve => { completeRefresh = resolve })
    assert.equal(await api.enqueue('下一轮', options), true)
    assert.equal(api.sending.value, false, '服务端已接收后，无需等待列表查询才能继续输入')
    assert.deepEqual(api.snapshot.value.messages, [accepted], '服务端接收后立即显示消息')
    completeRefresh({ ...current, messages: [accepted] }); await settle()
    let failOldRefresh
    globalThis.queueFixture.get = () => new Promise((_resolve, reject) => { failOldRefresh = reject })
    const staleRefresh = api.refresh()
    const rejectStaleRefresh = failOldRefresh
    assert.equal(await api.enqueue('下一轮', options), true)
    assert.equal(api.snapshot.value.messages.length, 1, '同一消息的重试结果不重复显示')
    const failLatestRefresh = failOldRefresh
    globalThis.queueFixture.get = async () => ({ ...current, messages: [accepted] })
    await api.refresh()
    rejectStaleRefresh(new Error('迟到的查询失败'))
    failLatestRefresh(new Error('已被更新的查询失败'))
    await staleRefresh
    await settle()
    assert.equal(api.error.value, '', '旧快照错误不覆盖最新消息状态')
    let completeSubmit
    globalThis.queueFixture.enqueue = () => new Promise(resolve => { completeSubmit = resolve })
    const pendingSubmit = api.enqueue('切换前的消息', options)
    session.value = 'another'; await settle()
    completeSubmit({ ...accepted, id: 'old-submit' })
    assert.equal(await pendingSubmit, false)
    assert.equal(api.snapshot.value.messages.some(item => item.id === 'old-submit'), false, '旧会话的提交回执不混入新会话')
    enabled.value = false; await settle()
    const before = gets.length
    await api.refresh()
    assert.equal(gets.length, before)
    assert.equal(api.snapshot.value, null)
  } finally {
    app?.unmount(); await server.close(); globalThis.document = saved; delete globalThis.queueFixture
    if (savedCrypto) Object.defineProperty(globalThis, 'crypto', savedCrypto)
    else delete globalThis.crypto
  }
})

test('客户端标识兼容原生 UUID 和没有 Web Crypto 的环境', async () => {
  const server = await createServer({ configFile: false, server: { middlewareMode: true }, optimizeDeps: { noDiscovery: true } })
  const savedCrypto = Object.getOwnPropertyDescriptor(globalThis, 'crypto')
  try {
    const { createClientId } = await server.ssrLoadModule('/src/utils/clientId.ts')
    Object.defineProperty(globalThis, 'crypto', { configurable: true, value: { randomUUID: () => 'native-id' } })
    assert.equal(createClientId(), 'native-id')
    Object.defineProperty(globalThis, 'crypto', { configurable: true, value: undefined })
    const ids = Array.from({ length: 100 }, () => createClientId())
    assert.equal(new Set(ids).size, ids.length)
    for (const id of ids) assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i)
  } finally {
    await server.close()
    if (savedCrypto) Object.defineProperty(globalThis, 'crypto', savedCrypto)
    else delete globalThis.crypto
  }
})
