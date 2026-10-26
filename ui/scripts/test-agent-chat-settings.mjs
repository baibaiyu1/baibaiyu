import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import { createServer, transformWithEsbuild } from 'vite'
import { createRenderer, defineComponent, h, nextTick, ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'

const sourceRoot = fileURLToPath(new URL('../src/', import.meta.url)).replaceAll('\\', '/')
const files = ['components/agent/AgentForm.vue', 'components/agent/config/AgentConfigEdit.vue',
  'views/Agent/AgentStudioView.vue', 'components/agent/AgentFormAdvanced.vue',
  'components/chat/ChatMain.vue', 'components/chat/ChatInputToolbar.vue',
  'components/agent/AgentFormModelResilience.vue', 'components/agent/studio/AgentSettingsModal.vue',
  'components/chat/AgentRunUsage.vue', 'components/chat/ChatRunPanel.vue',
  'components/chat/Welcome.vue', 'components/chat/ChatInput.vue', 'components/agent/AgentFormBasic.vue',
  'components/agent/studio/AgentStudioCard.vue']
const targets = new Set(files.map(file => sourceRoot + file))
const icons = new Set([...targets].flatMap(file => [...readFileSync(file, 'utf8').matchAll(/import\s*\{([^}]+)\}\s*from ['"]@ant-design\/icons-vue['"]/g)]
  .flatMap(match => match[1].split(',').map(name => name.trim()))))
const stubs = {
  'ant-design-vue': 'export const message = { success() {}, warning() {}, error() {} }; export const Modal = { confirm() {} }',
  '@ant-design/icons-vue': [...icons].map(name => `export const ${name} = { render: () => null }`).join('\n'),
  '@/stores': `import { ref } from 'vue'; const store = { tags: ref([]), fetchTags() {} };
    export const useAgentStore = () => store;
    export const useAccountStore = () => ({ currentTenant: { tenantCode: 'test' },
      isAdmin: !!globalThis.chatSettingsFixture?.isAdmin,
      hasPerm: perm => !globalThis.chatSettingsFixture?.deniedPerms?.includes(perm) });`,
  '@/api/agent': `export const save = value => globalThis.chatSettingsFixture.save(value);
    export const update = save; export const detail = () => globalThis.chatSettingsFixture.detail();`,
  '@/api/model': `export const configPage = async () => {
    globalThis.chatSettingsFixture?.modelCalls?.push('configPage');
    return { data: { data: { records: globalThis.chatSettingsFixture?.models || [] } } };
  }`,
  '@/api/approval': 'export const submit = async () => ({ data: { data: {} } })',
  '@/api/agui/telemetry': 'export const getTelemetry = id => globalThis.chatSettingsFixture?.telemetry?.(id) ?? Promise.resolve({ runs: [], records: [] })',
  '@/composables/chat/useChatAttachments': 'export const useChatAttachments = () => ({ fileAcceptAttr: () => "*/*", handleFileChange() {}, removeFile() {} })',
}
const element = (type, text = '') => ({ type, text, children: [], props: {}, parent: null,
  setAttribute(key, value) { this.props[key] = value }, scrollTo() {}, getBoundingClientRect() { return { top: 0 } } })
const renderer = createRenderer({
  createElement: element, createText: text => element('#text', text), createComment: text => element('#comment', text),
  setText(n, text) { n.text = text }, setElementText(n, text) { n.text = text; n.children = [] },
  parentNode: n => n.parent, nextSibling: n => n.parent?.children[n.parent.children.indexOf(n) + 1] || null,
  patchProp(n, key, _old, value) { n.props[key] = value },
  insert(n, parent, anchor) {
    if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1)
    n.parent = parent
    const index = parent.children.indexOf(anchor)
    if (index < 0) parent.children.push(n); else parent.children.splice(index, 0, n)
  },
  remove(n) { if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1); n.parent = null },
})
const all = (root, predicate) => [...(predicate(root) ? [root] : []), ...root.children.flatMap(n => all(n, predicate))]
const settle = async () => { await nextTick(); await new Promise(resolve => setImmediate(resolve)); await nextTick() }
const clone = value => JSON.parse(JSON.stringify(value))
const definition = () => ({ id: 'agent', agentCode: 'test-agent', agentType: 'CUSTOM', name: '测试智能体', description: '',
  modelConfigId: 'model', toolChoiceStrategy: 'AUTO', enableMemory: true, enablePlanning: false, showToolProcess: false,
  modelResilienceConfig: { maxAttempts: 2, fallbackModelId: 'backup', summaryModelId: 'summary' },
  uiConfig: { showPlanMode: false, openingMessage: '保留开场白', openingQuestions: ['保留问题'] } })

test('智能体设置控制对话选项和计划模式入口', { timeout: 60000 }, async t => {
  const server = await createServer({ configFile: false, server: { middlewareMode: true, hmr: false }, optimizeDeps: { noDiscovery: true },
    resolve: { alias: [...Object.keys(stubs).map(id => ({ find: id, replacement: `\0settings:${id}` })),
      { find: '@', replacement: sourceRoot }] },
    plugins: [{ name: 'chat-settings-fixture', enforce: 'pre',
      resolveId(id, importer) {
        if (id.startsWith('\0settings:')) return id
        if (Object.hasOwn(stubs, id)) return `\0settings:${id}`
        if (!id.endsWith('.vue')) return
        const path = resolve(id.startsWith('/src/') ? sourceRoot + id.slice(5) : id.startsWith('@/') ? sourceRoot + id.slice(2)
          : id.startsWith('.') && importer ? resolve(dirname(importer), id) : id).replaceAll('\\', '/')
        return targets.has(path) ? path : `\0settings:component:${path.split('/').at(-1).slice(0, -4)}`
      },
      load(id) {
        if (id.startsWith('\0settings:component:')) {
          const name = JSON.stringify(id.slice('\0settings:component:'.length))
          return `import { h } from 'vue'; export default { inheritAttrs: false, setup(_p, { attrs, slots, expose }) {
            expose({ validate: async () => true }); return () => h(${name}, attrs, slots.default?.()); } }`
        }
        if (id.startsWith('\0settings:')) return stubs[id.slice('\0settings:'.length)]
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
  const previousWindow = globalThis.window, previousObserver = globalThis.IntersectionObserver, previousDocument = globalThis.document
  const previousAnimationFrame = globalThis.requestAnimationFrame
  const previousCancelAnimationFrame = globalThis.cancelAnimationFrame
  globalThis.requestAnimationFrame = callback => { callback(); return 0 }
  globalThis.cancelAnimationFrame = () => {}
  globalThis.window = { addEventListener() {}, removeEventListener() {} }
  globalThis.document = { hidden: false, addEventListener() {}, removeEventListener() {} }
  globalThis.IntersectionObserver = class { observe() {} disconnect() {} }
  const mounted = []
  async function mount(file, props, events = {}) {
    const Component = (await server.ssrLoadModule('/src/' + file)).default
    const root = element('root')
    let instance
    const app = renderer.createApp({ setup: () => () => h(Component, { ...props.value, ...events, ref: value => { instance = value } }) })
    app.config.warnHandler = warning => { if (!warning.startsWith('Failed to resolve component:')) throw new Error(warning) }
    app.directive('permission', {})
    const shell = name => defineComponent({ inheritAttrs: false, setup(_p, { attrs, slots, expose }) {
      expose({ validate: async () => true }); return () => h(name, attrs, slots.default?.())
    } })
    for (const name of ['AForm', 'AFormItem', 'ARow', 'ACol', 'ASwitch', 'ASpace', 'ATooltip', 'AppModal',
      'AModal', 'AButton', 'ADrawer', 'ATabs', 'ATabPane', 'AAlert', 'ASpin', 'AEmpty', 'APagination']) app.component(name, shell(name))
    const router = createRouter({ history: createMemoryHistory('/web/#'), routes: [{ path: '/agent/:id', component: { render: () => null } }] })
    await router.push('/agent/agent'); app.use(router)
    app.mount(root); await settle()
    const result = { root, setup: () => instance.$.setupState, unmount: () => { app.unmount(); mounted.splice(mounted.indexOf(result), 1) } }
    mounted.push(result)
    return result
  }
  try {
    await t.test('基本信息即可创建自定义和两种 A2A 智能体，成功事件携带设计页 ID', async () => {
      for (const a2aType of [undefined, 'WELLKNOWN', 'NACOS']) {
        let saved, createdId, closed
        globalThis.chatSettingsFixture = { save: async value => {
          saved = clone(value)
          return { data: { data: { ...value, id: '9007199254740993', agentCode: 'test-agent-generated' } } }
        } }
        const props = ref({ visible: true, tags: [], a2aType })
        const view = await mount(files[0], props, {
          onSuccess: id => { createdId = id }, 'onUpdate:visible': value => { closed = value },
        })
        try {
          Object.assign(view.setup().basicData, { name: '客服助手', description: '解答问题', tag: '客服', tags: ['客服'] })
          assert.equal(all(view.root, node => node.type === 'AFormItem' && node.props.name === 'agentCode').length, 0)
          assert.equal(all(view.root, node => node.type === 'ASteps').length, 0)
          await view.setup().handleSubmit()
          assert.equal(createdId, '9007199254740993')
          assert.equal(closed, false)
          assert.equal(saved.name, '客服助手')
          assert.deepEqual(saved.tags, ['客服'])
          assert.equal(Object.hasOwn(saved, 'agentCode'), false)
          assert.equal(Object.hasOwn(saved, 'modelConfigId'), false)
          assert.equal(saved.agentType, a2aType ? 'A2A' : 'CUSTOM')
          if (a2aType) assert.equal(saved.agentA2A.a2aType, a2aType)
          else assert.equal(saved.agentA2A, undefined)
        } finally { view.unmount() }
      }
    })
    await t.test('设计页基本信息默认保留编号字段，创建时才隐藏', async () => {
      const props = ref({ modelValue: { name: '助手', agentCode: 'test-agent-123', description: '描述', tag: '' }, tags: [] })
      const view = await mount('components/agent/AgentFormBasic.vue', props)
      try {
        assert.equal(all(view.root, node => node.type === 'AFormItem' && node.props.name === 'agentCode').length, 1)
        props.value.showAgentCode = false
        await settle()
        assert.equal(all(view.root, node => node.type === 'AFormItem' && node.props.name === 'agentCode').length, 0)
      } finally { view.unmount() }
    })
    await t.test('创建校验失败不提交，请求期间防止重复提交，失败保留输入，取消后重新打开清空草稿', async () => {
      let calls = 0, rejectSave, createdId, closed
      globalThis.chatSettingsFixture = { save: () => {
        calls++
        return new Promise((_resolve, reject) => { rejectSave = reject })
      } }
      const props = ref({ visible: true, tags: [] })
      const view = await mount(files[0], props, {
        onSuccess: id => { createdId = id }, 'onUpdate:visible': value => { closed = value; props.value.visible = value },
      })
      const previousError = console.error
      console.error = () => {}
      try {
        view.setup().basicFormRef = { validate: async () => false }
        await view.setup().handleSubmit()
        assert.equal(calls, 0)
        view.setup().basicFormRef = { validate: async () => true }
        view.setup().basicData.name = '保留名称'
        const pending = view.setup().handleSubmit()
        await settle()
        await view.setup().handleSubmit()
        view.setup().handleCancel()
        assert.equal(calls, 1)
        assert.equal(closed, undefined)
        rejectSave(new Error('request failed'))
        await pending
        assert.equal(createdId, undefined)
        assert.equal(view.setup().loading, false)
        assert.equal(view.setup().basicData.name, '保留名称')
        view.setup().handleCancel()
        await settle()
        props.value.visible = true
        await settle()
        assert.equal(view.setup().basicData.name, '')
      } finally { console.error = previousError; view.unmount() }
    })
    await t.test('设计页提示词下拉在跟随开关开启和关闭时始终显示', async () => {
      globalThis.chatSettingsFixture = { detail: async () => ({ data: { data: {
        ...definition(), systemPromptTemplateId: 'prompt', followTemplate: false, systemPrompt: '独立正文',
      } } }) }
      const view = await mount(files[2], ref({}))
      const selects = () => all(view.root, node => String(node.props.class || '').split(' ').includes('agstudio-prompt-head__select'))
      try {
        assert.equal(selects().length, 1)
        assert.equal(view.setup().dirty, false)
        view.setup().formData.model.followTemplate = true; await settle()
        assert.equal(selects().length, 1)
        view.setup().formData.model.followTemplate = false; await settle()
        assert.equal(selects().length, 1)
        assert.equal(view.setup().formData.model.systemPrompt, '独立正文')
      } finally { view.unmount() }
    })
    for (const [name, file, propsFor] of [
      ['配置编辑页', files[1], data => ({ agentData: data, tags: [] })],
      ['Agent Studio', files[2], () => ({})],
    ]) {
      await t.test(`${name}保存和回读展示开关，旧配置默认显示用量，保留开场白及推荐问题`, async () => {
        let record = definition(), saved
        globalThis.chatSettingsFixture = { detail: async () => ({ data: { data: clone(record) } }),
          save: async value => { saved = clone(value); record = clone(value); return { data: { data: true } } } }
        const props = ref(propsFor(clone(record)))
        const view = await mount(file, props)
        try {
          assert.equal(view.setup().formData.advanced.showPlanMode, false)
          assert.equal(view.setup().formData.advanced.showModelUsage, true)
          assert.deepEqual(view.setup().formData.advanced.modelResilienceConfig, record.modelResilienceConfig)
          if (name === 'Agent Studio') assert.equal(view.setup().dirty, false)
          view.setup().formData.advanced.showPlanMode = true
          view.setup().formData.advanced.showModelUsage = false
          view.setup().formData.advanced.enableMemory = false
          view.setup().formData.advanced.showToolProcess = true
          view.setup().formData.advanced.modelResilienceConfig = { maxAttempts: 4, summaryModelId: 'summary2' }
          if (name === 'Agent Studio') { await settle(); assert.equal(view.setup().dirty, true); assert.equal(await view.setup().save(), true) }
          else {
            view.setup().currentStep = 4; await settle()
            await view.setup().handleSubmit()
          }
          assert.equal(saved.enableMemory, false); assert.equal(saved.showToolProcess, true)
          assert.deepEqual(saved.modelResilienceConfig, { maxAttempts: 4, summaryModelId: 'summary2' })
          assert.deepEqual(saved.uiConfig, { showPlanMode: true, showModelUsage: false,
            openingMessage: '保留开场白', openingQuestions: ['保留问题'] })
          if (name === '配置编辑页') props.value.agentData = clone(saved)
          await settle()
          assert.equal(view.setup().formData.advanced.showPlanMode, true)
          assert.equal(view.setup().formData.advanced.showModelUsage, false)
          assert.deepEqual(view.setup().formData.advanced.modelResilienceConfig, saved.modelResilienceConfig)
          if (name === 'Agent Studio') assert.equal(view.setup().dirty, false)
        } finally { view.unmount() }
      })
    }
    await t.test('高级设置区分任务清单和计划模式入口，开关分别修改对应值', async () => {
      const props = ref({ modelValue: { enablePlanning: false, showPlanMode: true, showModelUsage: false, enableMemory: true, showToolProcess: false } })
      const view = await mount(files[3], props)
      try {
        for (const [label, field] of [['启用任务清单', 'enablePlanning'], ['显示计划模式入口', 'showPlanMode'],
          ['显示实际模型用量', 'showModelUsage'], ['开启记忆', 'enableMemory'], ['显示工具调用历史', 'showToolProcess']]) {
          const item = all(view.root, n => n.props.label === label)[0]
          assert.ok(item, label)
          const control = all(item, n => n.type === 'ASwitch')[0]
          const checked = !props.value.modelValue[field]
          control.props['onUpdate:checked'](checked); control.props.onChange?.(checked); await settle()
        }
        assert.deepEqual(props.value.modelValue, { enablePlanning: true, showPlanMode: false, showModelUsage: true, enableMemory: false,
          showToolProcess: true, maxIterations: 50, maxSubtasks: 10 })
      } finally { view.unmount() }
    })
    await t.test('推荐提问只在空会话介绍区显示，输入内容不影响展示，首条消息后和重新打开历史时隐藏', async () => {
      globalThis.chatSettingsFixture = {}
      const props = ref({ title: '测试', messageSize: 0, welcomeHeadline: '欢迎', welcomeDesc: '智能体介绍', messages: [],
        toolCalls: [], inputValue: '', isRunning: false, agentId: 'agent', sessionId: '123',
        openingQuestions: ['  推荐一  ', '', '推荐二', '推荐一'] })
      let selected
      const view = await mount(files[4], props, { onPickQuestion: question => { selected = question } })
      const questions = () => all(view.root, n => n.type === 'button' && n.props.class === 'chat-opening-question')
      const inputs = () => all(view.root, n => String(n.props.class).split(' ').includes('chat-input-stage'))
      try {
        assert.equal(questions().length, 2)
        assert.equal(questions()[0].parent.props['aria-label'], '推荐提问')
        assert.equal(questions()[0].parent.parent.props.class, 'chat-welcome')
        assert.equal(all(inputs()[0], n => n.props.class === 'chat-opening-question').length, 0)
        questions()[0].props.onClick(); assert.equal(selected, '推荐一')
        props.value.inputValue = '正在输入'; await settle()
        assert.equal(questions().length, 2)
        props.value.messageSize = 1
        props.value.messages = [{ id: 'first', role: 'user', content: '已经发送' }]
        props.value.inputValue = ''; await settle()
        assert.equal(questions().length, 0)
        assert.equal(inputs().length, 1)
        props.value.sessionId = 'history'; await settle()
        assert.equal(questions().length, 0)
        props.value.messageSize = 0; props.value.messages = []; props.value.sessionId = null; await settle()
        assert.equal(questions().length, 2)
      } finally { view.unmount() }
    })
    await t.test('开始对话后立即展开输入框，空会话无需等待根消息回填', async () => {
      globalThis.chatSettingsFixture = {}
      const props = ref({ title: '测试', messageSize: 0, welcomeHeadline: '欢迎', messages: [], toolCalls: [],
        inputValue: '', isRunning: false, agentId: 'agent', sessionId: null, hasCodeExecutionConfig: true })
      let starts = 0
      const view = await mount(files[4], props, { onNewSession: () => { starts++; props.value.sessionId = 'new-session' } })
      const shell = () => all(view.root, n => String(n.props.class).split(' ').includes('chat-input-shell'))[0]
      const body = () => all(view.root, n => n.props.class === 'chat-input-body')[0]
      const collapsed = () => String(shell().props.class).split(' ').includes('is-collapsed')
      try {
        assert.equal(collapsed(), true)
        assert.equal(body().props.inert, true)
        shell().props.onClick(); await settle()
        assert.equal(starts, 1)
        assert.equal(props.value.sessionId, 'new-session')
        assert.equal(props.value.messageSize, 0)
        assert.equal(collapsed(), false)
        assert.equal(body().props.inert, false)
        shell().props.onClick(); await settle()
        assert.equal(starts, 1, '展开后的输入框点击不能再次新建会话')
        props.value.sessionId = null; await settle()
        assert.equal(collapsed(), true)
        props.value.sessionId = 'existing-empty-session'; await settle()
        assert.equal(collapsed(), false, '切换到已有空会话时也应直接显示输入框')
        props.value.sessionId = null; props.value.hasCodeExecutionConfig = false; await settle()
        assert.equal(collapsed(), false, '未配置执行环境时继续直接显示输入框')
      } finally { view.unmount() }
    })
    await t.test('欢迎区提问在执行环境尚未初始化时仍可使用，最多显示六条', async () => {
      globalThis.chatSettingsFixture = {}
      const view = await mount(files[10], ref({ messageSize: 0, headline: '欢迎', inputValue: '', agentId: 'agent',
        hasCodeExecutionConfig: true, openingQuestions: Array.from({ length: 8 }, (_, i) => `问题${i}`) }))
      try {
        assert.equal(view.setup().needInit, true)
        assert.equal(all(view.root, n => n.type === 'button' && n.props.class === 'chat-opening-question').length, 6)
      } finally { view.unmount() }
    })
    await t.test('管理员在高级设置修改模型策略，候选仅包含启用的对话模型，备用模型排除主模型', async () => {
      globalThis.chatSettingsFixture = { isAdmin: true, modelCalls: [], models: [
        { id: 'model', name: '主模型', enabled: true, modelType: ['CHAT'] },
        { id: 'backup', name: '备用模型', enabled: true, modelType: ['CHAT'] },
        { id: 'disabled', name: '已停用', enabled: false, modelType: ['CHAT'] },
        { id: 'embedding', name: '向量模型', enabled: true, modelType: ['EMBEDDING'] },
      ] }
      const props = ref({ modelValue: { maxAttempts: 2, fallbackModelId: 'backup', summaryModelId: 'model' }, modelConfigId: 'model' })
      const view = await mount(files[6], props, { 'onUpdate:modelValue': value => { props.value.modelValue = value } })
      try {
        assert.deepEqual(view.setup().options.map(option => option.value), ['model', 'backup'])
        assert.deepEqual(view.setup().fallbackOptions.map(option => option.value), ['backup'])
        assert.deepEqual(globalThis.chatSettingsFixture.modelCalls, ['configPage'])
        all(view.root, n => n.type === 'AInputNumber')[0].props.onChange(4); await settle()
        assert.deepEqual(props.value.modelValue, { maxAttempts: 4, fallbackModelId: 'backup', summaryModelId: 'model' })
        all(view.root, n => n.type === 'ASelect')[1].props.onChange(undefined); await settle()
        assert.equal(props.value.modelValue.summaryModelId, undefined)
        assert.equal(view.setup().validate(), true)
        view.setup().update({ fallbackModelId: 'model' }); await settle()
        assert.equal(view.setup().validate(), false)
        view.setup().update({ fallbackModelId: undefined, maxAttempts: 6 }); await settle()
        assert.equal(view.setup().validate(), false)
        view.setup().toggle(false); await settle(); assert.equal(props.value.modelValue, null)
        view.setup().toggle(true); await settle(); assert.deepEqual(props.value.modelValue, { maxAttempts: 3 })
      } finally { view.unmount() }
    })
    await t.test('普通账号不加载模型策略候选或改写已有管理员配置', async () => {
      globalThis.chatSettingsFixture = { isAdmin: false, modelCalls: [] }
      const original = { maxAttempts: 4, fallbackModelId: 'backup', summaryModelId: 'summary' }
      const props = ref({ modelValue: clone(original), modelConfigId: 'model' })
      let emitted = false
      const view = await mount(files[6], props, { 'onUpdate:modelValue': () => { emitted = true } })
      try {
        assert.equal(all(view.root, n => n.type === 'ASwitch').length, 0)
        view.setup().toggle(false); view.setup().update({ maxAttempts: 1 })
        assert.equal(emitted, false)
        assert.deepEqual(props.value.modelValue, original)
        assert.deepEqual(globalThis.chatSettingsFixture.modelCalls, [])
      } finally { view.unmount() }
    })
    await t.test('Agent 设置取消不修改原策略，重新打开恢复原值，保存包含独立草稿', async () => {
      globalThis.chatSettingsFixture = { isAdmin: true }
      const original = { maxAttempts: 2, fallbackModelId: 'backup', summaryModelId: 'summary' }
      const props = ref({ open: false, basic: {}, tools: {}, advanced: { modelResilienceConfig: clone(original), showModelUsage: true }, tags: [] })
      let saved
      const view = await mount(files[7], props, { 'onUpdate:open': value => { props.value.open = value }, onSave: value => { saved = value } })
      try {
        props.value.open = true; await settle()
        view.setup().advancedModel.modelResilienceConfig.maxAttempts = 5
        view.setup().advancedModel.showModelUsage = false
        view.setup().close(); await settle()
        assert.deepEqual(props.value.advanced.modelResilienceConfig, original)
        assert.equal(props.value.advanced.showModelUsage, true)
        props.value.open = true; await settle()
        assert.deepEqual(view.setup().advancedModel.modelResilienceConfig, original)
        assert.equal(view.setup().advancedModel.showModelUsage, true)
        view.setup().advancedModel.modelResilienceConfig.summaryModelId = 'summary2'
        view.setup().advancedModel.showModelUsage = false
        await view.setup().handleSave()
        assert.deepEqual(saved.advanced.modelResilienceConfig, { ...original, summaryModelId: 'summary2' })
        assert.equal(saved.advanced.showModelUsage, false)
        assert.deepEqual(props.value.advanced.modelResilienceConfig, original)
        assert.equal(props.value.advanced.showModelUsage, true)
      } finally { view.unmount() }
    })
    await t.test('实际用量跟随智能体展示设置，关闭后停止查询，仍校验权限和正式会话入口', async () => {
      let usageQueries = 0
      globalThis.chatSettingsFixture = { telemetry: async () => { usageQueries++; return { runs: [], records: [] } } }
      const props = ref({ title: '测试', messageSize: 2, welcomeHeadline: '', messages: [], toolCalls: [],
        inputValue: '', isRunning: false, agentId: 'agent', sessionId: '123', allowUsage: true, allowInstructions: false })
      const view = await mount(files[4], props)
      try {
        assert.equal(all(view.root, n => n.props['aria-label'] === '查看实际模型用量').length, 1)
        assert.equal(all(view.root, n => n.type === 'AgentInstructions').length, 0)
        assert.equal(usageQueries, 1)
        props.value.showModelUsage = false; await settle()
        assert.equal(all(view.root, n => n.props['aria-label'] === '查看实际模型用量').length, 0)
        props.value.isRunning = true; await settle()
        props.value.isRunning = false; await settle()
        assert.equal(usageQueries, 1)
        props.value.showModelUsage = true; await settle()
        assert.equal(all(view.root, n => n.props['aria-label'] === '查看实际模型用量').length, 1)
        assert.equal(usageQueries, 2)
        props.value.sessionMessageTable = 'archive'; await settle()
        assert.equal(all(view.root, n => n.props['aria-label'] === '查看实际模型用量').length, 1)
        assert.equal(all(view.root, n => String(n.props.class).split(' ').includes('chat-input-stage')).length, 0)
        props.value.showModelUsage = false; await settle()
        assert.equal(all(view.root, n => n.props['aria-label'] === '查看实际模型用量').length, 0)
        props.value.showModelUsage = true; await settle()
        props.value.allowUsage = false; await settle()
        assert.equal(all(view.root, n => n.props['aria-label'] === '查看实际模型用量').length, 0)
        globalThis.chatSettingsFixture.deniedPerms = ['agent:definition:list']
        props.value.allowUsage = true; await settle()
        assert.equal(all(view.root, n => n.props['aria-label'] === '查看实际模型用量').length, 0)
      } finally { view.unmount() }
    })
    const run = values => ({ runId: 'run', state: 'SUCCEEDED', elapsedMs: 1200, modelCalls: 2, unknownCalls: 0,
      inputTokens: 1234, outputTokens: 56, reasoningTokens: 8, failures: 0, retries: 0, switches: 0, ...values })
    const text = root => root.text + root.children.map(text).join('')
    const usageTrigger = root => all(root, n => n.type === 'button' && String(n.props.class).includes('run-panel-trigger'))[0]
    await t.test('对话直接显示供应商用量，真实零值与未知分开，未知调用标明已知小计', async () => {
      globalThis.chatSettingsFixture = { telemetry: async () => ({ runs: [run({ inputTokens: 0, outputTokens: 7, unknownCalls: 1 })], records: [] }) }
      const view = await mount(files[8], ref({ sessionId: '123', agentId: 'agent', isRunning: false }))
      try {
        const summary = text(usageTrigger(view.root))
        assert.match(summary, /输入\s*0/)
        assert.match(summary, /输出\s*7\s*Token/)
        assert.match(summary, /已知小计/); assert.match(summary, /1 次未知/)
        globalThis.chatSettingsFixture.telemetry = async () => ({ runs: [run({ inputTokens: null, outputTokens: null, unknownCalls: 2 })], records: [] })
        await view.setup().refresh(); await settle()
        assert.match(text(usageTrigger(view.root)), /输入\s*未知/)
        assert.match(text(usageTrigger(view.root)), /输出\s*未知/)
        usageTrigger(view.root).props.onClick(); await settle()
        assert.equal(view.setup().open, true)
      } finally { view.unmount() }
    })
    await t.test('切换会话忽略旧请求返回，用量查询失败保留旧值并提示刷新失败', async () => {
      const requests = new Map()
      globalThis.chatSettingsFixture = { telemetry: id => new Promise(resolve => { requests.set(id, resolve) }) }
      const props = ref({ sessionId: '123', agentId: 'agent', isRunning: false })
      const view = await mount(files[8], props)
      try {
        props.value.sessionId = '456'; await settle()
        requests.get('456')({ runs: [run({ inputTokens: 99 })], records: [] }); await settle()
        requests.get('123')({ runs: [run({ inputTokens: 999 })], records: [] }); await settle()
        assert.equal(view.setup().latestRun.inputTokens, 99)
        globalThis.chatSettingsFixture.telemetry = async () => { throw new Error('临时中断') }
        await view.setup().refresh(); await settle()
        assert.match(text(usageTrigger(view.root)), /输入\s*99/)
        assert.match(text(usageTrigger(view.root)), /刷新失败/)
      } finally { view.unmount() }
    })
    await t.test('结束流后补查最终落库用量，无需打开明细面板', async () => {
      let calls = 0
      globalThis.chatSettingsFixture = { telemetry: async () => ({ runs: [run({ inputTokens: ++calls < 3 ? 10 : 42 })], records: [] }) }
      const props = ref({ sessionId: '123', agentId: 'agent', isRunning: true })
      const view = await mount(files[8], props)
      try {
        props.value.isRunning = false; await settle()
        assert.equal(view.setup().latestRun.inputTokens, 10)
        await new Promise(resolve => setTimeout(resolve, 900)); await settle()
        assert.equal(view.setup().latestRun.inputTokens, 42)
        assert.equal(view.setup().open, false)
      } finally { view.unmount() }
    })
    await t.test('输入工具栏按入口配置显示计划模式，不再提供记忆或工具展示按钮', async () => {
      const props = ref({ canSend: false, hasContent: false, allowPlanMode: false })
      let selected
      const view = await mount(files[5], props, { onPlanMode: value => { selected = value } })
      const planButtons = () => all(view.root, n => n.type === 'button' && String(n.props.class).includes('chat-plan-mode-btn'))
      try {
        assert.equal(planButtons().length, 0)
        assert.equal(all(view.root, n => n.type === 'button').length, 3)
        props.value.allowPlanMode = true; await settle()
        planButtons()[0].props.onClick(); assert.equal(selected, true)
        props.value.planMode = true; await settle()
        planButtons()[0].props.onClick(); assert.equal(selected, false)
      } finally { view.unmount() }
    })
    await t.test('隐藏计划模式按钮仍保留已有方案查看入口', async () => {
      const view = await mount(files[4], ref({ title: '测试', messageSize: 2, welcomeHeadline: '', messages: [], toolCalls: [],
        inputValue: '', isRunning: false, agentId: 'agent', sessionId: '123', allowPlanMode: false, canViewPlan: true }))
      try {
        assert.equal(all(view.root, n => n.type === 'AgentPlanDocument').length, 1)
        assert.equal(all(view.root, n => n.type === 'button' && String(n.props.class).includes('chat-plan-mode-btn')).length, 0)
      } finally { view.unmount() }
    })
  } finally {
    mounted.slice().forEach(view => view.unmount()); await server.close()
    globalThis.window = previousWindow; globalThis.IntersectionObserver = previousObserver; globalThis.document = previousDocument
    globalThis.requestAnimationFrame = previousAnimationFrame
    globalThis.cancelAnimationFrame = previousCancelAnimationFrame
    delete globalThis.chatSettingsFixture
  }
})
