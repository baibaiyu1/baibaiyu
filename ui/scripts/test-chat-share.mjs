import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { parse, compileScript } from 'vue/compiler-sfc'
import { createServer, transformWithEsbuild } from 'vite'
import { createRenderer, h, nextTick, ref } from 'vue'

const sourceRoot = fileURLToPath(new URL('../src/', import.meta.url)).replaceAll('\\', '/')
const editorFile = sourceRoot + 'components/chat/ChatInputEditor.vue'
const stubs = {
  'ant-design-vue': 'export const message = { warning() {} }',
  '@/api/agent': `export const enabledToolsOfAgent = id => globalThis.chatShareFixture.tools(id);
    export const enabledSkillsOfAgent = id => globalThis.chatShareFixture.skills(id);`,
  '@/composables/chat/useWorkspaceFiles': `import { ref } from 'vue';
    export const useWorkspaceFiles = () => ({ flatFiles: ref([{ path: 'report.md', name: 'report.md' }]), fetchFiles: async () => {} });`,
  '@/composables/chat/useResourceCategories': 'export const RESOURCE_CATEGORY_REGISTRY = {}; export const findKindByTagName = () => null',
  '@/utils/chat/tagSystem': 'export const extractTextFromEditor = () => ""; export const renderTaggedTextToHtml = () => ""',
}
const renderer = createRenderer({
  createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
  setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null,
  patchProp() {}, insert() {}, remove() {},
})
const settle = async () => { await nextTick(); await new Promise(resolve => setImmediate(resolve)); await nextTick() }
const response = data => ({ data: { data } })
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }

test('分享页输入框保留游客身份并隔离配置目录查询', { timeout: 30000 }, async t => {
  const server = await createServer({ configFile: false, server: { middlewareMode: true, ws: false },
    optimizeDeps: { noDiscovery: true },
    resolve: { alias: [...Object.keys(stubs).map(id => ({ find: id, replacement: `\0chat-share:${id}` })),
      { find: '@', replacement: sourceRoot }] },
    plugins: [{ name: 'chat-share-fixture', enforce: 'pre',
      resolveId(id) {
        if (id.startsWith('\0chat-share:')) return id
        if (Object.hasOwn(stubs, id)) return `\0chat-share:${id}`
        if (id.endsWith('ResourceMentionDropdown.vue')) return '\0chat-share:dropdown'
      },
      load(id) {
        if (id === '\0chat-share:dropdown') return 'export default { render: () => null }'
        if (id.startsWith('\0chat-share:')) return stubs[id.slice('\0chat-share:'.length)]
      },
      async transform(source, id) {
        if (id.replaceAll('\\', '/') !== editorFile) return
        const { descriptor } = parse(source, { filename: id })
        const script = compileScript(descriptor, { id, genDefaultAs: '__component' })
        return transformWithEsbuild(`${script.content}\n__component.render = () => null; export default __component`, `${id}.ts`, { loader: 'ts' })
      },
    }] })
  const previousWindow = globalThis.window, previousStorage = globalThis.sessionStorage
  const storage = new Map()
  globalThis.window = { location: { hash: '#/communication/share-key' } }
  globalThis.sessionStorage = { getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) }
  const mounted = []
  try {
    const auth = await server.ssrLoadModule('/src/utils/auth.ts')
    const Component = (await server.ssrLoadModule('/src/components/chat/ChatInputEditor.vue')).default
    async function mount(agentId = 'agent') {
      const calls = []
      const fixture = globalThis.chatShareFixture = {
        tools(id) { calls.push(['tools', id]); return Promise.resolve(response([{ toolId: id + '-tool', name: '工具' }])) },
        skills(id) { calls.push(['skills', id]); return Promise.resolve(response([{ name: id + '-skill', alias: '技能' }])) },
      }
      const props = ref({ agentId, modelValue: '', mentionAllowed: true })
      let instance
      const app = renderer.createApp({ setup: () => () => h(Component, { ...props.value, ref: value => { instance = value } }) })
      app.mount({})
      await settle()
      const view = { calls, fixture, props, state: instance.$.setupState,
        unmount() { app.unmount(); mounted.splice(mounted.indexOf(view), 1) } }
      mounted.push(view)
      return view
    }

    await t.test('分享链接首次加载和输入框重新挂载均不调用配置接口，游客令牌及文件引用仍可用', async () => {
      auth.setShareSession('share-key', { accessToken: 'valid-visitor-token', accessTokenTTL: Date.now() + 60000,
        userDetail: { id: 'visitor' } })
      const originalSession = [...storage.entries()]
      for (let i = 0; i < 2; i++) {
        const view = await mount()
        assert.deepEqual(view.calls, [])
        assert.deepEqual(view.state.agentTools, [])
        assert.deepEqual(view.state.agentSkills, [])
        assert.equal(view.state.flatFiles[0].path, 'report.md')
        assert.equal(auth.getToken(), 'valid-visitor-token')
        assert.deepEqual([...storage.entries()], originalSession)
        view.unmount()
      }
    })

    await t.test('正式对话仍加载工具、技能及内置技能，空智能体不发请求', async () => {
      window.location.hash = '#/chat/agent'
      const view = await mount('')
      assert.deepEqual(view.calls, [])
      view.props.value.agentId = 'agent'
      await settle()
      assert.deepEqual(view.calls, [['tools', 'agent'], ['skills', 'agent']])
      assert.equal(view.state.agentTools[0].id, 'agent-tool')
      assert.equal(view.state.agentSkills[0].id, 'agent-skill')
      assert.equal(view.state.agentSkills.length, 3)
      view.unmount()
    })

    await t.test('切换智能体时忽略旧请求，目录加载失败不会产生未处理异常', async () => {
      const view = await mount('initial')
      const tools = deferred(), skills = deferred()
      view.fixture.tools = id => id === 'slow' ? tools.promise : Promise.reject(new Error('目录暂不可用'))
      view.fixture.skills = id => id === 'slow' ? skills.promise : Promise.reject(new Error('目录暂不可用'))
      view.props.value.agentId = 'slow'
      await settle()
      assert.deepEqual(view.state.agentTools, [])
      view.props.value.agentId = 'next'
      await settle()
      tools.resolve(response([{ toolId: 'old-tool', name: '旧工具' }]))
      skills.resolve(response([{ name: 'old-skill', alias: '旧技能' }]))
      await settle()
      assert.deepEqual(view.state.agentTools, [])
      assert.equal(view.state.agentSkills.length, 2)
      assert.ok(view.state.agentSkills.every(item => item.id !== 'old-skill'))
      view.unmount()
    })

    await t.test('从正式对话切到分享路由并切换智能体会清空配置目录', async () => {
      const view = await mount('internal')
      window.location.hash = '#/communication/share-key'
      view.props.value.agentId = 'shared'
      await settle()
      assert.deepEqual(view.calls, [['tools', 'internal'], ['skills', 'internal']])
      assert.deepEqual(view.state.agentTools, [])
      assert.deepEqual(view.state.agentSkills, [])
      view.unmount()
    })
  } finally {
    while (mounted.length) mounted[0].unmount()
    delete globalThis.chatShareFixture
    globalThis.window = previousWindow
    globalThis.sessionStorage = previousStorage
    await server.close()
  }
})
