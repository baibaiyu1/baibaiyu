import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import { createServer, transformWithEsbuild } from 'vite'
import { createRenderer, defineComponent, h, nextTick, ref } from 'vue'

const src = fileURLToPath(new URL('../src/', import.meta.url)).replaceAll('\\', '/')
const files = ['components/agent/AgentReasoningSettings.vue', 'components/model/ReasoningFields.vue',
  'components/agent/studio/AgentModelParamsPopover.vue', 'components/agent/studio/AgentSettingsModal.vue',
  'components/model/ModelConfigForm.vue', 'components/chat/SubAgentEventList.vue', 'components/agent/AgentFormModel.vue']
const targets = new Set(files.map(file => src + file))
const icons = new Set([...targets].flatMap(file => [...readFileSync(file, 'utf8').matchAll(/import\s*\{([^}]+)\}\s*from ['"]@ant-design\/icons-vue['"]/g)]
  .flatMap(match => match[1].split(',').map(name => name.trim()))))
const caps = { profile: 'DEEPSEEK', canEnable: true, canDisable: true, efforts: ['low', 'high', 'max'], hint: '' }
const model = () => ({ id: 'model', modelId: 'deepseek-flash', apiType: 'RESPONSES', maxTokens: 8192,
  reasoningConfig: { mode: 'ENABLED', effort: 'high', showThinking: true }, reasoningCapabilities: { ...caps } })
const node = (type, text = '') => ({ type, text, children: [], props: {}, parent: null, scrollTo() {}, getBoundingClientRect() { return { top: 0 } } })
const renderer = createRenderer({
  createElement: node, createText: text => node('#text', text), createComment: text => node('#comment', text),
  setText(n, text) { n.text = text }, setElementText(n, text) { n.text = text; n.children = [] },
  parentNode: n => n.parent, nextSibling: n => n.parent?.children[n.parent.children.indexOf(n) + 1] || null,
  patchProp(n, key, _old, value) { n.props[key] = value },
  insert(n, parent, anchor) { n.parent = parent; const i = parent.children.indexOf(anchor); if (i < 0) parent.children.push(n); else parent.children.splice(i, 0, n) },
  remove(n) { if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1) },
})
const settle = async () => { await nextTick(); await new Promise(resolve => setImmediate(resolve)); await nextTick() }
const clone = value => JSON.parse(JSON.stringify(value))

test('推理配置独立覆盖与协议表单回归', { timeout: 60000 }, async t => {
  const saved = []
  globalThis.reasoningFixture = { caps, models: [{ ...model(), providerId: 'provider' }], save: async value => saved.push(clone(value)) }
  const stubs = {
    'ant-design-vue': 'export const message = { success() {}, warning() {}, error() {} }; export const Modal = {}',
    '@ant-design/icons-vue': [...icons].map(name => `export const ${name} = { render: () => null }`).join('\n'),
    '@/api/model': `export const configSave = value => globalThis.reasoningFixture.save(value), configUpdate = configSave;
      export const configPage = async () => ({ data: { data: { records: globalThis.reasoningFixture.models } } });
      export const providerPage = async () => ({ data: { data: { records: [{ id: 'provider', type: 'OPEN_AI' }] } } });
      export const reasoningCapabilities = async () => ({ data: { data: globalThis.reasoningFixture.caps } });`,
    '@/api/prompt': `export const listCategories = async () => ({ data: { data: [] } });
      export const page = async () => ({ data: { data: { records: [] } } });`,
    'vue-router': 'export const useRouter = () => ({ resolve: () => ({ href: "#" }) })',
    '@/types': 'export const ModelType = { CHAT: "CHAT" }; export const ModelProviderType = { OPEN_AI: "OPEN_AI" }',
  }
  const server = await createServer({ configFile: false, server: { middlewareMode: true, hmr: false }, optimizeDeps: { noDiscovery: true },
    resolve: { alias: [...Object.keys(stubs).map(find => ({ find, replacement: `\0reasoning:${find}` })), { find: '@', replacement: src }] },
    plugins: [{ name: 'reasoning-fixture', enforce: 'pre',
      resolveId(id, importer) {
        if (id.startsWith('\0reasoning:')) return id
        if (Object.hasOwn(stubs, id)) return `\0reasoning:${id}`
        if (!id.endsWith('.vue')) return
        const path = resolve(id.startsWith('/src/') ? src + id.slice(5) : id.startsWith('@/') ? src + id.slice(2)
          : id.startsWith('.') && importer ? resolve(dirname(importer), id) : id).replaceAll('\\', '/')
        return targets.has(path) ? path : '\0reasoning:child'
      },
      load(id) {
        if (id === '\0reasoning:child') return `import { h } from 'vue'; export default { inheritAttrs: false,
          setup(_p, { attrs, slots, expose }) { expose({ validate: async () => true }); return () => h('child', attrs, slots.default?.()); } }`
        if (id.startsWith('\0reasoning:')) return stubs[id.slice('\0reasoning:'.length)]
      },
      async transform(source, id) {
        if (!targets.has(id.replaceAll('\\', '/'))) return
        const { descriptor } = parse(source, { filename: id })
        const script = compileScript(descriptor, { id, genDefaultAs: '__component' })
        const template = compileTemplate({ source: descriptor.template.content, filename: id, id,
          compilerOptions: { bindingMetadata: script.bindings } })
        assert.deepEqual(template.errors, [])
        return transformWithEsbuild(`${script.content}\n${template.code}\n__component.render = render; export default __component`, `${id}.ts`, { loader: 'ts' })
      },
    }] })
  const previousWindow = globalThis.window, previousObserver = globalThis.IntersectionObserver
  globalThis.window = { addEventListener() {}, removeEventListener() {} }
  globalThis.IntersectionObserver = class { observe() {} disconnect() {} }
  const mounted = []
  async function mount(file, props, events = {}) {
    const Component = (await server.ssrLoadModule('/src/' + file)).default
    const root = node('root'); let instance
    const app = renderer.createApp({ setup: () => () => h(Component, { ...props.value, ...events, ref: value => { instance = value } }) })
    app.config.warnHandler = warning => { if (!warning.startsWith('Failed to resolve component:')) throw new Error(warning) }
    const shell = name => defineComponent({ inheritAttrs: false, setup(_p, { attrs, slots, expose }) {
      expose({ validate: async () => true, resetFields() {} }); return () => h(name, attrs, slots.default?.())
    } })
    for (const name of ['AForm', 'AFormItem', 'ASwitch', 'ASelect', 'AAlert', 'AInputNumber', 'ARow', 'ACol', 'AInput', 'ATextarea', 'ASlider', 'AppModal']) app.component(name, shell(name))
    app.mount(root); await settle()
    mounted.push(app)
    return () => instance.$.setupState
  }
  try {
    await t.test('独立配置复制当前值，模型变更和采样覆盖开关均不覆盖推理', async () => {
      const props = ref({ model: model(), modelValue: { temperature: 0.2 } })
      const state = await mount(files[0], props, { 'onUpdate:modelValue': value => { props.value.modelValue = value } })
      assert.equal(state().independent, false)
      state().toggle(true); await settle()
      props.value.model.reasoningConfig.effort = 'low'; await settle()
      assert.equal(state().settings.effort, 'high')
      state().update({ mode: 'ENABLED', effort: 'max', showThinking: false }); await settle()
      assert.equal(props.value.modelValue.temperature, 0.2)
      const popoverProps = ref({ model: props.value.model, formData: { modelParamsOverride: props.value.modelValue } })
      const popover = await mount(files[2], popoverProps, { 'onUpdate:override': value => { popoverProps.value.formData.modelParamsOverride = value } })
      popover().handleOpenChange(true); popover().handleToggle(false); await settle()
      assert.deepEqual(clone(popoverProps.value.formData.modelParamsOverride), { reasoning: { mode: 'ENABLED', effort: 'max', showThinking: false } })
      popover().handleToggle(true); await settle()
      assert.equal(popoverProps.value.formData.modelParamsOverride.reasoning.effort, 'max')
      assert.equal(popoverProps.value.formData.modelParamsOverride.reasoning.showThinking, false)
      state().toggle(false); await settle()
      assert.equal(state().settings.effort, 'low'); assert.equal(props.value.modelValue.temperature, 0.2)
    })
    await t.test('关闭推理清除强度和预算，DeepSeek 最高档为 max', async () => {
      const props = ref({ modelValue: { mode: 'ENABLED', effort: 'max', budgetTokens: 1024, showThinking: false }, capabilities: caps })
      const state = await mount(files[1], props, { 'onUpdate:modelValue': value => { props.value.modelValue = value } })
      assert.equal(state().efforts.at(-1).value, 'max')
      state().update({ mode: 'DISABLED' }); await settle()
      assert.deepEqual(clone(props.value.modelValue), { mode: 'DISABLED', showThinking: false })
    })
    await t.test('模型表单关闭独立推理后，修改采样参数不会恢复旧推理配置', async () => {
      const props = ref({ modelValue: { modelConfigId: 'model', systemPromptTemplateId: '', followTemplate: false, systemPrompt: '',
        modelParamsOverride: { temperature: 0.2, reasoning: { mode: 'ENABLED', effort: 'max', showThinking: false } } } })
      const state = await mount(files[6], props, { 'onUpdate:modelValue': value => { props.value.modelValue = value } })
      assert.equal(state().showModelParamsOverride, true)
      state().reasoningFormRef.$.setupState.toggle(false); await settle()
      assert.equal(props.value.modelValue.modelParamsOverride.reasoning, undefined)
      state().modelParamsForm.temperature = 0.4; await settle()
      assert.equal(props.value.modelValue.modelParamsOverride.temperature, 0.4)
      assert.equal(props.value.modelValue.modelParamsOverride.reasoning, undefined)
    })
    await t.test('Agent 设置取消不写回，保存包含独立推理快照', async () => {
      const props = ref({ open: false, basic: {}, tools: {}, advanced: {}, tags: [], model: model(),
        modelParamsOverride: { temperature: 0.2, reasoning: { mode: 'ENABLED', effort: 'low', showThinking: true } } })
      let emitted
      const state = await mount(files[3], props, { onSave: value => { emitted = value } })
      props.value.open = true; await settle()
      state().reasoningModel.reasoning.effort = 'max'
      state().close(); assert.equal(props.value.modelParamsOverride.reasoning.effort, 'low')
      props.value.open = false; await settle(); props.value.open = true; await settle()
      assert.equal(state().reasoningModel.reasoning.effort, 'low')
      state().reasoningModel.reasoning.showThinking = false
      await state().handleSave(); await settle()
      assert.equal(emitted.modelParamsOverride.reasoning.showThinking, false)
      assert.equal(emitted.modelParamsOverride.temperature, 0.2)
    })
    await t.test('模型保存结构化推理，保留非推理扩展，能力加载期间禁止提交', async () => {
      const props = ref({ visible: false, providerId: 'provider', providerType: 'OPEN_AI', data: { ...model(),
        name: 'DeepSeek', modelType: ['CHAT'], extendConfig: { bodyParams: { 'reasoning.effort': 'high', custom: 'keep' } } } })
      const state = await mount(files[4], props)
      props.value.visible = true; await settle()
      await state().handleSubmit(); assert.equal(saved.length, 0)
      await new Promise(resolve => setTimeout(resolve, 250)); await settle()
      state().reasoning.effort = 'max'; await state().handleSubmit(); await settle()
      assert.equal(saved[0].extendConfig.reasoning.effort, 'max')
      assert.deepEqual(saved[0].extendConfig.bodyParams, { custom: 'keep' })
      assert.equal(saved[0].apiType, 'RESPONSES')
    })
    await t.test('历史子智能体卡片隐藏思考但保留正文和工具轨迹', async () => {
      const props = ref({ active: false, showThinking: false, events: [
        { eventId: '1', sequence: 1, eventType: 'MESSAGE_COMPLETED', payload: { messageId: 'thought', role: 'thinking', content: '旧思考' } },
        { eventId: '2', sequence: 2, eventType: 'MESSAGE_COMPLETED', payload: { messageId: 'answer', role: 'assistant', content: '回答' } },
        { eventId: '3', sequence: 3, eventType: 'TOOL_COMPLETED', payload: { toolCallId: 'lookup', name: 'lookup', result: '结果' } },
      ] })
      const state = await mount(files[5], props)
      assert.deepEqual(state().timeline.map(item => item.kind), ['message', 'tool'])
      assert.equal(state().timeline[0].content, '回答')
      props.value.showThinking = true; await settle()
      assert.equal(state().timeline[0].role, 'thinking')
      assert.equal(state().timeline[0].content, '旧思考')
    })
  } finally {
    mounted.forEach(app => app.unmount()); await server.close()
    globalThis.window = previousWindow; globalThis.IntersectionObserver = previousObserver
    delete globalThis.reasoningFixture
  }
})
