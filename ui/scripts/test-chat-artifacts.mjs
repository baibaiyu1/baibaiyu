import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { createRenderer, ref, nextTick } from 'vue'

const renderer = createRenderer({ insert() {}, remove() {}, createElement: () => ({}),
  createText: () => ({}), createComment: () => ({}), setText() {}, setElementText() {},
  parentNode: () => null, nextSibling: () => null, patchProp() {} })
const settle = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
const response = data => ({ data: { data } })
const file = (sessionId, name = '对话整理.docx', time = '2026-10-14T10:00:00') => ({
  id: name, sessionId, filePath: name, fileName: name, extension: 'docx', sizeBytes: 100, createTime: time,
})

test('generated files recover after background and lost notifications', { timeout: 30000 }, async t => {
  const original = { document: globalThis.document, window: globalThis.window, download: globalThis.__getChatArtifacts }
  globalThis.document = Object.assign(new EventTarget(), { visibilityState: 'visible' })
  globalThis.window = Object.assign(new EventTarget(), { location: { protocol: 'http:', hostname: 'localhost', port: '' } })
  const server = await createServer({ configFile: false, server: { middlewareMode: true, hmr: false },
    optimizeDeps: { noDiscovery: true }, resolve: { alias: [
      { find: '@/api/chatSession', replacement: '\0chat-artifacts-api' },
      { find: '@', replacement: fileURLToPath(new URL('../src', import.meta.url)) },
    ] }, plugins: [{ name: 'chat-artifacts-api',
      resolveId(id) { if (id === '\0chat-artifacts-api') return id },
      load(id) { if (id === '\0chat-artifacts-api') return 'export const getArtifacts = id => globalThis.__getChatArtifacts(id)' },
    }] })
  try {
    const { useChatArtifacts } = await server.ssrLoadModule('/src/composables/chat/useChatArtifacts.ts')
    const { eventBus } = await server.ssrLoadModule('/src/websocket/core/event-bus.ts')
    const mount = (session = '123') => {
      const sid = ref(session), running = ref(false)
      let state
      const app = renderer.createApp({ setup() { state = useChatArtifacts(sid, running); return () => null } })
      app.mount({})
      return { sid, running, get state() { return state }, unmount: () => app.unmount() }
    }

    await t.test('run completion and returning from background recover missed files without websocket events', async () => {
      let files = [], calls = 0
      globalThis.__getChatArtifacts = async () => { calls++; return response(files) }
      const view = mount()
      try {
        await settle()
        view.running.value = true; await settle()
        files = [file('123')]
        view.running.value = false; await settle()
        assert.equal(view.state.artifacts.value.length, 1)
        document.visibilityState = 'hidden'
        document.dispatchEvent(new Event('visibilitychange')); await settle()
        assert.equal(calls, 2, 'hidden transitions must not issue background requests')
        files = [...files, file('123', '设备报告.docx')]
        document.visibilityState = 'visible'
        document.dispatchEvent(new Event('visibilitychange')); await settle()
        assert.equal(view.state.artifacts.value.length, 2)
        assert.equal(calls, 3)
      } finally { view.unmount() }
    })

    await t.test('failed refresh keeps visible files and reconnect or online restores the list', async () => {
      let fail = false, files = [file('123')]
      globalThis.__getChatArtifacts = async () => { if (fail) throw new Error('offline'); return response(files) }
      const view = mount()
      try {
        await settle(); fail = true
        await view.state.refresh()
        assert.equal(view.state.artifacts.value.length, 1)
        fail = false; files = [...files, file('123', '新增文档.docx')]
        eventBus.emit('WEBSOCKET:CONNECTED', {}); await settle()
        assert.equal(view.state.artifacts.value.length, 2)
        files = [...files, file('123', '恢复文档.docx')]
        window.dispatchEvent(new Event('online')); await settle()
        assert.equal(view.state.artifacts.value.length, 3)
      } finally { view.unmount() }
    })

    await t.test('a late request from a previous session cannot overwrite the current session', async () => {
      const pending = []
      globalThis.__getChatArtifacts = sid => new Promise(resolve => pending.push({ sid, resolve }))
      const view = mount()
      try {
        view.sid.value = '456'; await settle()
        pending[1].resolve(response([file('456')])); await settle()
        pending[0].resolve(response([file('123')])); await settle()
        assert.equal(view.state.artifacts.value[0].sessionId, '456')
        view.sid.value = '123'; await settle()
        assert.equal(view.state.artifacts.value.length, 0, 'switching clears the previous session immediately')
        pending[2].resolve(response([file('123')])); await settle()
      } finally { view.unmount() }
    })

    await t.test('an old snapshot cannot erase a new notification and concurrent recovery queues a fresh check', async () => {
      const pending = []
      globalThis.__getChatArtifacts = () => new Promise(resolve => pending.push(resolve))
      const view = mount()
      try {
        eventBus.emit('ARTIFACT_CREATED', file('123', '对话整理.docx', '2026-10-14T10:01:00'))
        eventBus.emit('ARTIFACT_CREATED', file('456', '其他会话.docx'))
        const recovered = view.state.refresh()
        pending[0](response([file('123')])); await settle()
        assert.equal(view.state.artifacts.value.length, 1)
        assert.equal(view.state.artifacts.value[0].createTime, '2026-10-14T10:01:00')
        assert.equal(pending.length, 2, 'run end during an in-flight snapshot must trigger one fresh check')
        pending[1](response([file('123', '新增文档.docx')])); await recovered
        assert.equal(view.state.artifacts.value.length, 2)
      } finally { view.unmount() }
    })

    await t.test('unmount clears all recovery listeners and temporary sessions never call formal APIs', async () => {
      let calls = 0
      globalThis.__getChatArtifacts = async () => { calls++; return response([]) }
      const view = mount('studio-temporary')
      await settle(); assert.equal(calls, 0)
      view.sid.value = '123'; await settle(); assert.equal(calls, 1)
      view.unmount()
      document.dispatchEvent(new Event('visibilitychange'))
      window.dispatchEvent(new Event('online'))
      eventBus.emit('WEBSOCKET:CONNECTED', {})
      eventBus.emit('ARTIFACT_CREATED', file('123'))
      await settle()
      assert.equal(calls, 1)
      assert.equal(view.state.artifacts.value.length, 0)
    })
  } finally {
    await server.close()
    globalThis.document = original.document
    globalThis.window = original.window
    globalThis.__getChatArtifacts = original.download
  }
})
