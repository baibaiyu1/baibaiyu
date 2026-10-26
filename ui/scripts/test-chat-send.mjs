import assert from 'node:assert/strict'
import test from 'node:test'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import { createServer, transformWithEsbuild } from 'vite'
import { createRenderer, h, nextTick } from 'vue'

const sourceRoot = fileURLToPath(new URL('../src/', import.meta.url)).replaceAll('\\', '/')
const chatFile = sourceRoot + 'views/Chat/index.vue'
const stubs = {
  'vue-router': 'export const useRoute = () => ({ params: { agentId: "agent" } })',
  'ant-design-vue': 'export const message = { info() {}, warning() {}, error() {}, success() {} }; export const Modal = { confirm() {} }',
  '@ant-design/icons-vue': 'export const LoadingOutlined = { render: () => null }',
  '@/stores': `export const useAccountStore = () => ({ userInfo: { id: 'account' }, hasPerm: () => true });
    export const useChatStore = () => ({ getSidebarCollapsed: () => false, getWorkspacePanelWidth: () => 400 });`,
  '@/composables/chat/useAgentDetail': `import { ref } from 'vue';
    export const useAgentDetail = () => ({ agentDetail: ref({ id: 'agent', agentCode: 'agent', agentType: 'CUSTOM',
      codeExecutionConfigId: globalThis.chatSendFixture.codeExecutionConfigId }), allowFileType: ref([]) });`,
  '@/composables/chat/useSessions': `import { ref } from 'vue';
    export const useSessions = () => ({ pinnedSessions: ref([]), otherSessions: ref(globalThis.chatSendFixture.otherSessions ?? []), loading: ref(false), hasMore: ref(false),
      createSession: (...args) => globalThis.chatSendFixture.create(...args),
      updateSessionTitle: async () => {}, loadSessions() {} });`,
  '@/composables/chat/useChatStream': `import { ref } from 'vue';
    export const useChatStream = () => {
      const isRunning = ref(false);
      return { agentHasResult: ref(true), streamingContent: ref(''), streamingMessageId: ref(null), streamingRole: ref('system'),
        toolCallsInProgress: ref([]), subAgentRuns: ref([]), isRunning, isStopping: ref(false), currentPlan: ref(null),
        contextUsage: ref(null), memoryCompressionActive: ref(false), disconnect() {}, resetStreamingState() {},
        sendMessage: async (...args) => { isRunning.value = true; try { await globalThis.chatSendFixture.run(...args) } finally { isRunning.value = false } } };
    };`,
  '@/composables/chat/useChatArtifacts': `import { ref } from 'vue'; export const useChatArtifacts = () => ({ artifacts: ref([]) });`,
  '@/composables/chat/useConversationQueue': `import { ref } from 'vue';
    export const useConversationQueue = () => ({ snapshot: ref(null), error: ref(''), sending: ref(false), refresh() {},
      enqueue: (...args) => globalThis.chatSendFixture.enqueue(...args) });`,
  '@/composables/chat/useVoiceConversation': `import { ref } from 'vue';
    export const useVoiceConversation = () => ({ available: ref(false), active: ref(false), connecting: ref(false), muted: ref(false),
      state: ref(''), error: ref(''), userCaption: ref(''), assistantCaption: ref(''), needsAudioStart: ref(false), businessFinished() {} });`,
  '@/websocket/composables/useWebSocketEvent': 'export const useWebSocketEvent = () => {}',
  '@/websocket/const/websocket': 'export const WS_MESSAGE_TYPES = {}',
  '@/utils/chat/artifact': 'export const attachArtifacts = messages => messages',
  '@/api/chatSession': `export const appendMessage = (...args) => globalThis.chatSendFixture.append(...args);
    export const getCurrentMessagesPaged = (...args) => globalThis.chatSendFixture.history(...args);
    export const getMessageBranches = async () => ({ data: { data: [] } });`,
  '@/api/agui/instructions': 'export const submitInstruction = async () => {}',
  '@/api/agui/queue': 'export const enqueueMessage = async () => {}',
  '@/api/agui': 'export const getActiveRuns = async () => []; export const getStatus = async () => false; export const getPending = async () => []; export const stopRun = async () => {}',
}

const renderer = createRenderer({
  createElement: type => ({ type, props: {} }), createText: () => ({}), createComment: () => ({}),
  setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null,
  patchProp(node, key, _old, value) { node.props[key] = value }, insert() {}, remove() {},
})
const settle = async () => { await nextTick(); await new Promise(resolve => setImmediate(resolve)); await nextTick() }
const deferred = () => { let resolve, reject; const promise = new Promise((res, rej) => { resolve = res; reject = rej }); return { promise, resolve, reject } }

test('对话首条消息和发送准备阶段不会重复提交或显示', { timeout: 30000 }, async t => {
  const server = await createServer({ configFile: false, server: { middlewareMode: true, ws: false }, optimizeDeps: { noDiscovery: true },
    resolve: { alias: [...Object.keys(stubs).map(id => ({ find: id, replacement: `\0chat-send:${id}` })), { find: '@', replacement: sourceRoot }] },
    plugins: [{ name: 'chat-send-fixture', enforce: 'pre',
      resolveId(id, importer) {
        if (id.startsWith('\0chat-send:')) return id
        if (Object.hasOwn(stubs, id)) return `\0chat-send:${id}`
        if (!id.endsWith('.vue')) return
        const path = resolve(id.startsWith('/src/') ? sourceRoot + id.slice(5) : id.startsWith('@/') ? sourceRoot + id.slice(2)
          : id.startsWith('.') && importer ? resolve(dirname(importer), id) : id).replaceAll('\\', '/')
        return path === chatFile ? path : `\0chat-send:component:${path.split('/').at(-1).slice(0, -4)}`
      },
      load(id) {
        if (id.startsWith('\0chat-send:component:')) {
          return `import { h } from 'vue'; export default { inheritAttrs: false, setup(_p, { attrs }) { return () => h(${JSON.stringify(id.slice('\0chat-send:component:'.length))}, attrs) } }`
        }
        if (id.startsWith('\0chat-send:')) return stubs[id.slice('\0chat-send:'.length)]
      },
      async transform(source, id) {
        if (id.replaceAll('\\', '/') !== chatFile) return
        const { descriptor } = parse(source, { filename: id })
        const script = compileScript(descriptor, { id, genDefaultAs: '__component' })
        const template = compileTemplate({ source: descriptor.template.content, filename: id, id, compilerOptions: { bindingMetadata: script.bindings } })
        assert.deepEqual(template.errors, [])
        return transformWithEsbuild(`${script.content}\n${template.code}\n__component.render = render; export default __component`, `${id}.ts`, { loader: 'ts' })
      },
    }] })
  const mounted = []
  async function mount(options = {}) {
    const calls = { create: [], append: [], history: [], run: [], enqueue: [] }
    const rows = []
    const fixture = globalThis.chatSendFixture = {
      ...options,
      async create(...args) { calls.create.push(args); return { id: 'session', title: '新对话' } },
      async append(sid, dto) {
        calls.append.push([sid, dto])
        const row = { id: String(rows.length + 1), sessionId: sid, ...dto }
        rows.push(row)
        return { data: { data: row } }
      },
      async history(...args) { calls.history.push(args); return { data: { data: { messages: rows.map(row => ({ ...row })), hasMore: false } } } },
      async run(...args) { calls.run.push(args) },
      async enqueue(...args) { calls.enqueue.push(args); return true },
    }
    const Component = (await server.ssrLoadModule('/src/views/Chat/index.vue')).default
    let instance
    const app = renderer.createApp({ setup: () => () => h(Component, { chatAgentId: 'agent', showAccount: true, ref: value => { instance = value } }) })
    app.mount({})
    await settle()
    const view = { state: instance.$.setupState, fixture, calls, rows,
      main: () => instance.$.subTree.children.find(child => child.type?.setup && child.props?.['agent-id'] === 'agent'),
      unmount: () => { app.unmount(); mounted.splice(mounted.indexOf(view), 1) } }
    mounted.push(view)
    return view
  }
  try {
    await t.test('开始对话创建并选中新会话，不等待历史消息回填', async () => {
      const view = await mount({ codeExecutionConfigId: 'execution' })
      try {
        await view.main().props.onNewSession()
        await settle()
        assert.deepEqual(view.calls.create, [['新对话', true]])
        assert.equal(view.state.currentSessionId, 'session')
        assert.equal(view.main().props['session-id'], 'session')
        assert.equal(view.main().props['message-size'], 0)
        assert.equal(view.calls.history.length, 0)
      } finally { view.unmount() }
    })
    await t.test('开始对话进入已有的待用新会话，消息为空也保留选中状态', async () => {
      const view = await mount({ codeExecutionConfigId: 'execution', otherSessions: [{ id: 'latest-empty', title: '新对话' }] })
      try {
        await view.main().props.onNewSession()
        await settle()
        assert.equal(view.calls.create.length, 0)
        assert.equal(view.state.currentSessionId, 'latest-empty')
        assert.equal(view.main().props['session-id'], 'latest-empty')
        assert.equal(view.main().props['message-size'], 0)
        assert.equal(view.calls.history.length, 1)
      } finally { view.unmount() }
    })
    await t.test('一次预设问题点击只请求一次，历史回填也只显示一条用户消息', async () => {
      const view = await mount()
      try {
        await view.state.handlePickQuestion('你好')
        await settle()
        assert.equal(view.calls.append.length, 1)
        assert.equal(view.calls.run.length, 1)
        assert.equal(view.state.messagesList.filter(row => row.role === 'user').length, 1)
        assert.equal(view.calls.history.length, 0, '新建会话已知为空，无需同时回填历史')
      } finally { view.unmount() }
    })
    await t.test('慢速创建会话期间重复触发预设问题只接受一次', async () => {
      const view = await mount(), creation = deferred()
      view.fixture.create = async (...args) => { view.calls.create.push(args); return creation.promise }
      try {
        const first = view.state.handlePickQuestion('你好')
        const duplicate = view.state.handlePickQuestion('你好')
        await settle()
        const busy = view.main().props['is-sending']
        creation.resolve({ id: 'session', title: '新对话' })
        await Promise.all([first, duplicate])
        assert.equal(busy, true)
        assert.equal(view.calls.create.length, 1)
        assert.equal(view.calls.append.length, 1)
        assert.equal(view.calls.run.length, 1)
        assert.equal(view.state.inputText, '')
      } finally { creation.resolve(null); view.unmount() }
    })
    await t.test('手动输入在消息保存未完成时也禁止重复发送', async () => {
      const view = await mount(), saving = deferred()
      try {
        view.state.currentSessionId = 'session'
        await settle()
        view.fixture.append = async (...args) => { view.calls.append.push(args); return saving.promise }
        view.state.inputText = '手动问题'
        const first = view.state.handleSend()
        view.state.inputText = '后续草稿'
        const duplicate = view.state.handleSend()
        saving.resolve({ data: { data: { id: 'user', sessionId: 'session', role: 'user', content: '手动问题' } } })
        await Promise.all([first, duplicate])
        assert.equal(view.calls.append.length, 1)
        assert.equal(view.calls.run.length, 1)
        assert.equal(view.state.inputText, '后续草稿')
      } finally { saving.resolve(null); view.unmount() }
    })
    await t.test('提交失败会释放发送锁，允许下一次发送', async () => {
      const view = await mount(), create = view.fixture.create
      try {
        view.fixture.create = async () => { throw new Error('登录刷新失败') }
        await assert.rejects(view.state.handlePickQuestion('你好'), /登录刷新失败/)
        await settle()
        assert.equal(view.main().props['is-sending'], false)
        view.fixture.create = create
        await view.state.handlePickQuestion('再试一次')
        assert.equal(view.calls.append.length, 1)
        assert.equal(view.calls.run.length, 1)
      } finally { view.unmount() }
    })
    await t.test('运行开始后允许发送下一轮排队消息', async () => {
      const view = await mount(), running = deferred()
      view.fixture.run = async (...args) => { view.calls.run.push(args); await running.promise }
      try {
        const first = view.state.handlePickQuestion('第一轮')
        await settle()
        assert.equal(view.state.isRunning, true)
        assert.equal(view.main().props['is-sending'], false)
        view.state.inputText = '下一轮'
        await view.state.handleSend()
        assert.equal(view.calls.enqueue.length, 1)
        assert.equal(view.calls.append.length, 1)
        assert.equal(view.calls.run.length, 1)
        running.resolve()
        await first
      } finally { running.resolve(); view.unmount() }
    })
    await t.test('选择已有会话只加载一次历史，初始化空会话不查询历史', async () => {
      const view = await mount()
      try {
        await view.state.selectSession({ id: 'existing', title: '已有会话' })
        await settle()
        assert.equal(view.calls.history.length, 1)
        assert.equal(view.state.loadingMessages, false)
        view.state.resetSession({ id: 'new', title: '新对话' })
        await settle()
        assert.equal(view.calls.history.length, 1)
        assert.equal(view.state.messagesList.length, 0)
        assert.equal(view.state.currentSessionId, 'new')
      } finally { view.unmount() }
    })
    await t.test('前一轮完成后允许再次发送相同文字', async () => {
      const view = await mount()
      try {
        await view.state.handlePickQuestion('你好')
        view.state.inputText = '你好'
        await view.state.handleSend()
        assert.equal(view.calls.append.length, 2)
        assert.equal(view.calls.run.length, 2)
        assert.equal(view.state.messagesList.filter(row => row.role === 'user').length, 2)
      } finally { view.unmount() }
    })
    await t.test('恢复历史期间切换会话，旧查询不能覆盖新会话的消息', async () => {
      const view = await mount(), history = deferred()
      try {
        view.state.resetSession({ id: 'old', title: '原会话' })
        view.state.messagesList = [{ id: 'saved', role: 'assistant', content: '已保存' }]
        view.fixture.history = () => history.promise
        const recovering = view.state.loadCurrentMessages(true)
        assert.equal(view.state.messagesList[0].content, '已保存')
        view.state.resetSession({ id: 'new', title: '新会话' })
        history.resolve({ data: { data: { messages: [{ id: 'old-reply', role: 'assistant', content: '旧会话回复' }] } } })
        await recovering
        assert.equal(view.state.currentSessionId, 'new')
        assert.deepEqual(view.state.messagesList, [])
      } finally { history.resolve({ data: { data: { messages: [] } } }); view.unmount() }
    })
    await t.test('保存期间切换会话不把回执或运行提交到新会话', async () => {
      const view = await mount(), saving = deferred()
      try {
        view.state.resetSession({ id: 'old', title: '原会话' })
        view.fixture.append = async (...args) => { view.calls.append.push(args); return saving.promise }
        view.state.inputText = '原会话的问题'
        const first = view.state.handleSend()
        view.state.resetSession({ id: 'new', title: '新会话' })
        saving.resolve({ data: { data: { id: 'user', sessionId: 'old', role: 'user', content: '原会话的问题' } } })
        await first
        assert.equal(view.calls.run.length, 0)
        assert.equal(view.state.messagesList.length, 0)
        assert.equal(view.state.currentSessionId, 'new')
      } finally { saving.resolve(null); view.unmount() }
    })
  } finally {
    for (const view of [...mounted]) view.unmount()
    delete globalThis.chatSendFixture
    await server.close()
  }
})
