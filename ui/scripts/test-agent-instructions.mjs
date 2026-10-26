import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { createRenderer, ref, nextTick } from 'vue'

const renderer = createRenderer({ insert() {}, remove() {}, createElement: () => ({}), createText: () => ({}),
  createComment: () => ({}), setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null, patchProp() {} })
test('补充要求在切换会话、刷新状态和网络重试时保持归属与幂等', { timeout: 60000 }, async t => {
  const server = await createServer({ configFile: false, server: { middlewareMode: true }, optimizeDeps: { noDiscovery: true },
    resolve: { alias: [{ find: '@/api/agui/instructions', replacement: '\0instruction-fixture' },
      { find: '@', replacement: fileURLToPath(new URL('../src', import.meta.url)) }] },
    plugins: [{ name: 'fixture', resolveId: id => id === '\0instruction-fixture' ? id : undefined,
      load: id => id === '\0instruction-fixture' ? `export const getInstructions = id => globalThis.instructionFixture.get(id);
        export const submitInstruction = (...args) => globalThis.instructionFixture.submit(...args);` : undefined }] })
  const savedDocument = globalThis.document
  globalThis.document = { hidden: false, addEventListener() {}, removeEventListener() {} }
  let api, app
  const session = ref('first'), running = ref(false)
  let finishFirst
  const submitted = []
  globalThis.instructionFixture = {
    get: id => id === 'first' ? new Promise(resolve => { finishFirst = resolve })
      : Promise.resolve({ enabled: true, runId: `run-${id}`, accepting: true, messages: [] }),
    submit: (...args) => { submitted.push(args); return Promise.reject(new Error('网络中断')) },
  }
  const settle = async () => { await nextTick(); await new Promise(resolve => setImmediate(resolve)); await nextTick() }
  try {
    const { useAgentInstructions } = await server.ssrLoadModule('/src/composables/chat/useAgentInstructions.ts')
    app = renderer.createApp({ setup() { api = useAgentInstructions(session, running); return () => null } }); app.mount({})
    session.value = 'second'; await settle()
    finishFirst({ enabled: true, runId: 'run-first', accepting: true, messages: [{ id: 'old' }] }); await settle()
    assert.equal(api.inbox.value.runId, 'run-second')
    assert.deepEqual(api.inbox.value.messages, [])
    api.content.value = '优先最近一个月'
    await api.send(); await api.send()
    assert.equal(submitted.length, 2)
    assert.equal(submitted[0][0], 'second')
    assert.equal(submitted[0][2], submitted[1][2], '网络重试复用请求标识')
    assert.equal(api.content.value, '优先最近一个月')
    globalThis.instructionFixture.submit = async () => ({ id: 'one' })
    globalThis.instructionFixture.get = async () => ({ enabled: true, runId: 'run-second', accepting: true,
      messages: [{ id: 'one', state: 'APPLIED', content: '优先最近一个月' }] })
    await api.send()
    assert.equal(api.content.value, '')
    assert.equal(api.inbox.value.messages[0].state, 'APPLIED')
    app.unmount(); app = undefined
  } finally {
    app?.unmount(); await server.close(); globalThis.document = savedDocument; delete globalThis.instructionFixture
  }
})
