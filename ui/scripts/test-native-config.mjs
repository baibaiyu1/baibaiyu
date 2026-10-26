import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { parse, compileScript } from 'vue/compiler-sfc'
import { createServer, transformWithEsbuild } from 'vite'
import { createRenderer, defineComponent, h, nextTick, ref } from 'vue'

const src = fileURLToPath(new URL('../src/', import.meta.url)).replaceAll('\\', '/')
const targets = [`${src}components/model/ModelConfigForm.vue`, `${src}components/tool/ToolForm.vue`]
const node = (type, text = '') => ({ type, text, children: [], props: {}, parent: null })
const renderer = createRenderer({
  createElement: node, createText: text => node('#text', text), createComment: text => node('#comment', text),
  setText: (n, text) => { n.text = text }, setElementText: (n, text) => { n.text = text; n.children = [] },
  parentNode: n => n.parent, nextSibling: n => n.parent?.children[n.parent.children.indexOf(n) + 1] || null,
  patchProp: (n, key, _old, value) => { n.props[key] = value },
  insert(n, parent, anchor) { n.parent = parent; const index = parent.children.indexOf(anchor); if (index < 0) parent.children.push(n); else parent.children.splice(index, 0, n) },
  remove(n) { if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1) },
})
const find = (n, type) => n.type === type ? n : n.children.map(child => find(child, type)).find(Boolean)
const settle = async () => { await nextTick(); await new Promise(resolve => setImmediate(resolve)); await nextTick() }

test('模型和工具表单回填并提交原生协议与直接返回配置', { timeout: 60000 }, async () => {
  const saved = [], warnings = []
  globalThis.nativeConfigFixture = { save: value => { saved.push(JSON.parse(JSON.stringify(value))); return Promise.resolve() }, warnings }
  const server = await createServer({ configFile: false, server: { middlewareMode: true, hmr: false }, optimizeDeps: { noDiscovery: true },
    resolve: { alias: [{ find: '@', replacement: src }, ...['ant-design-vue', '@ant-design/icons-vue', 'sortablejs'].map(find => ({ find, replacement: `\0${find}` }))] }, plugins: [{ name: 'native-config-fixture', enforce: 'pre',
      resolveId(id) {
        if (id.startsWith('\0')) return id
        if (id.endsWith('/api/model') || id.endsWith('/api/tool')) return '\0config-api'
        if (id.endsWith('/types')) return '\0config-types'
        if (id === 'ant-design-vue' || id === '@ant-design/icons-vue' || id === 'sortablejs') return `\0${id}`
        if (id.endsWith('.vue') && !/\/(model\/ModelConfigForm|tool\/ToolForm)\.vue$/.test(id.replaceAll('\\', '/'))) return '\0child'
      },
      load(id) {
        if (id === '\0config-api') return 'export const configSave = globalThis.nativeConfigFixture.save, configUpdate = configSave, save = configSave, update = save; export const reasoningCapabilities = async () => ({ data: { data: { profile: "OPENAI", canEnable: true, canDisable: true, efforts: ["low", "medium", "high"] } } })'
        if (id === '\0config-types') return 'export const ModelType = { CHAT: "CHAT" }, ModelProviderType = { OPEN_AI: "OPEN_AI" }, ToolType = { BUILTIN: "BUILTIN", CUSTOM: "CUSTOM" }, CodeLanguage = { JAVA: "JAVA" }'
        if (id === '\0ant-design-vue') return 'export const message = { success() {}, warning: x => globalThis.nativeConfigFixture.warnings.push(x) }, Modal = {}'
        if (id === '\0@ant-design/icons-vue') return 'export const EyeOutlined = {}, PlusOutlined = {}, MinusCircleOutlined = {}, HolderOutlined = {}, ToolOutlined = {}, BugOutlined = {}'
        if (id === '\0sortablejs') return 'export default class Sortable { static create() { return { destroy() {} } } }'
        if (id === '\0child') return 'export default { render() { return null } }'
      },
      async transform(source, id) {
        if (!targets.includes(id.replaceAll('\\', '/'))) return
        const { descriptor } = parse(source, { filename: id })
        const script = compileScript(descriptor, { id, inlineTemplate: true })
        return transformWithEsbuild(script.content, `${id}.ts`, { loader: 'ts' })
      },
    }] })
  let app
  async function mount(path, props) {
    const Component = (await server.ssrLoadModule(path)).default
    const root = node('root')
    app = renderer.createApp({ setup: () => () => h(Component, props.value) })
    app.config.warnHandler = () => {}
    const shell = name => defineComponent({ inheritAttrs: false, setup(_p, { attrs, slots }) { return () => h(name, attrs, slots.default?.()) } })
    for (const name of ['AppModal', 'AFormItem', 'ARow', 'ACol']) app.component(name, shell(name))
    app.component('AForm', defineComponent({ props: ['model'], setup(p, { expose, slots }) {
      expose({ validate: async () => {}, resetFields() {} }); return () => h('form', { model: p.model }, slots.default?.())
    } }))
    app.mount(root); props.value.visible = true; await settle()
    return root
  }
  try {
    const props = ref({ visible: false, providerId: 'provider', providerType: 'OPEN_AI', data: { id: 'model', name: 'Responses', modelId: 'fixture', modelType: ['CHAT'], apiType: 'RESPONSES', responsesSampling: false,
      streaming: true, thinking: false, contextWindow: 8192, maxTokens: 1024, temperature: 0.7, topP: 0.9, topK: 50, repeatPenalty: 1, seed: '', description: 'fixture', reasoningConfig: { mode: 'ENABLED', effort: 'high', showThinking: true }, extendConfig: { headers: { custom: 'keep' }, bodyParams: { 'reasoning.effort': 'high' } } } })
    let root = await mount('/src/components/model/ModelConfigForm.vue', props)
    assert.equal(find(root, 'form').props.model.apiType, 'RESPONSES')
    await new Promise(resolve => setTimeout(resolve, 250)); await settle()
    await find(root, 'AppModal').props.onOk(); await settle()
    assert.equal(saved[0].apiType, 'RESPONSES'); assert.equal(saved[0].responsesSampling, false)
    assert.equal(saved[0].extendConfig.reasoning.effort, 'high'); assert.equal(saved[0].extendConfig.headers.custom, 'keep')
    assert.equal(saved[0].extendConfig.bodyParams['reasoning.effort'], undefined)
    app.unmount(); app = null
    const tool = ref({ visible: false, categories: [], data: { id: 'tool', name: '工具', toolId: 'lookup', toolType: 'CUSTOM', needConfirm: true, returnDirect: true, version: '1', inputSchema: [],
      outputSchema: { type: 'object', properties: { text: { type: 'string' } } }, category: '', description: 'fixture', code: 'fixture' } })
    root = await mount('/src/components/tool/ToolForm.vue', tool)
    assert.equal(find(root, 'form').props.model.returnDirect, true)
    find(root, 'form').props.model.outputSchemaText = '{invalid'
    await find(root, 'AppModal').props.onOk(); await settle()
    assert.equal(saved.length, 1); assert.equal(warnings.length, 1)
    find(root, 'form').props.model.outputSchemaText = '{"type":"object"}'
    await find(root, 'AppModal').props.onOk(); await settle()
    assert.equal(saved[1].returnDirect, true); assert.equal(saved[1].needConfirm, true); assert.deepEqual(saved[1].outputSchema, { type: 'object' })
  } finally { app?.unmount(); await server.close(); delete globalThis.nativeConfigFixture }
})
