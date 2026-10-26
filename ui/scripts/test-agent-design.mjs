import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import { createServer, transformWithEsbuild } from 'vite'
import { createRenderer, h, nextTick, ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'

const components = [
  'agent/AgentFormKnowledge', 'agent/AgentKnowledgeStrategy', 'agent/AgentFormTools', 'agent/AgentFormModel', 'agent/studio/AgentResourcePicker',
  'agent/studio/AgentModelParamsPopover', 'chat/MessageList', 'chat/MessageItem',
]
const sourceRoot = fileURLToPath(new URL('../src/', import.meta.url)).replaceAll('\\', '/')
const paths = new Set(components.map(name => `${sourceRoot}components/${name}.vue`))
let resourceRoutes = []
const sources = [...paths].map(path => readFileSync(path, 'utf8'))
const icons = new Set(sources.flatMap(source => [...source.matchAll(/import\s*\{([^}]+)\}\s*from ['"]@ant-design\/icons-vue['"]/g)]
  .flatMap(match => match[1].split(',').map(name => name.trim()))))
const records = {
  knowledge: [
    { id: 'kb1', name: '知识库一', enabled: true, kbType: 'LOCAL', ragMode: 'GENERIC', retrievalConfig: { topK: 9, scoreThreshold: 0.7 } },
    { id: 'kb2', name: '知识库二', enabled: true, kbType: 'RAGFLOW', ragMode: 'AGENTIC', retrievalConfig: { topK: 3, similarityThreshold: 0.8 } },
  ],
  mcp: [{ id: 'mcp1', name: 'MCP一', enabled: true, activationStatus: 'ACTIVE', availableToolCount: 1 }],
  prompt: [
    { id: 'prompt1', name: '客服模板', category: '客服', content: '客服模板正文' },
    { id: 'prompt2', name: '写作模板', category: '写作', content: '写作模板正文' },
  ],
}
const stubs = {
  'ant-design-vue': 'export const message = { error() {}, warning() {} }',
  '@ant-design/icons-vue': [...icons].map(name => `export const ${name} = { render: () => null }`).join('\n'),
  sortablejs: 'export default { create: () => ({ destroy() {} }) }',
  '@/stores': 'export const useAccountStore = () => ({ isAdmin: false })',
  ...Object.fromEntries(['workspace', 'attach'].map(name => [
    `@/api/${name}`, 'export const download = async () => ({ data: new Blob() })',
  ])),
  ...Object.fromEntries(['knowledge', 'mcp', 'agent', 'tool', 'skill', 'sensitive', 'hook', 'workflow', 'model', 'prompt'].map(name => [
    `@/api/${name}`, `
      export const page = async () => {
        globalThis.__agentDesignCalls?.push('${name}')
        return { data: { data: { records: ${JSON.stringify(records[name] || [])} } } }
      }
      export const workflowPage = page
      export const providerPage = page
      export const configPage = page
      export const detail = async id => globalThis.__agentDesignDetail?.('${name}', id)
        ?? { data: { data: ${JSON.stringify(records[name] || [])}.find(item => item.id === id) } }
      export const listCategories = async () => ({ data: { data: ${JSON.stringify(name === 'prompt' ? ['客服', '写作'] : [])} } })
      export const listTools = async () => ({ data: { data: [] } })
    `,
  ])),
}

function element(type, text = '') { return { type, text, children: [], props: {}, parent: null } }
const renderer = createRenderer({
  createElement: element,
  createText: text => element('#text', text),
  createComment: text => element('#comment', text),
  setText(node, text) { node.text = text },
  setElementText(node, text) { node.text = text; node.children = [] },
  parentNode: node => node.parent,
  nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] || null,
  patchProp(node, key, previous, next) { node.props[key] = next },
  insert(node, parent, anchor) {
    if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
    node.parent = parent
    const index = anchor ? parent.children.indexOf(anchor) : -1
    if (index < 0) parent.children.push(node)
    else parent.children.splice(index, 0, node)
  },
  remove(node) {
    if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
    node.parent = null
  },
})

function findAll(node, predicate) {
  return [...(predicate(node) ? [node] : []), ...node.children.flatMap(child => findAll(child, predicate))]
}
function byClass(root, name) {
  return findAll(root, node => String(node.props.class || '').split(' ').includes(name))
}
async function settle() {
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}
function mount(Component, props, events = {}) {
  const root = element('root')
  let instance
  const app = renderer.createApp({
    setup: () => () => h(Component, { ...props.value, ...events, ref: value => { instance = value } }),
  })
  app.config.warnHandler = warning => {
    if (!warning.startsWith('Failed to resolve component:')) throw new Error(warning)
  }
  app.directive('permission', {})
  const router = createRouter({ history: createMemoryHistory('/web/#'), routes: [
    ...resourceRoutes,
    { path: '/:pathMatch(.*)*', component: { render: () => null } },
  ] })
  app.use(router)
  app.mount(root)
  return { root, router, setup: () => instance.$.setupState, unmount: () => app.unmount() }
}
function knowledgeModel(overrides = {}) {
  return { knowledgeBase: [], mcp: [], mcpBindings: [], subAgent: [], ragConfig: null, ...overrides }
}

test('智能体资源操作与对话最终回复操作回归', { timeout: 90000 }, async t => {
  const server = await createServer({
    configFile: false,
    server: { middlewareMode: true },
    optimizeDeps: { noDiscovery: true },
    resolve: { alias: [
      ...Object.keys(stubs).map(id => ({ find: id, replacement: `\0agent-design-test:${id}` })),
      { find: '@', replacement: sourceRoot },
    ] },
    plugins: [{
      name: 'agent-design-test-components', enforce: 'pre',
      resolveId(id, importer) {
        if (id.startsWith('\0agent-design-test:')) return id
        if (Object.hasOwn(stubs, id)) return `\0agent-design-test:${id}`
        if (id.endsWith('.vue')) {
          const candidate = resolve(id.startsWith('/src/') ? `${sourceRoot}${id.slice(5)}`
            : id.startsWith('@/') ? `${sourceRoot}${id.slice(2)}`
              : id.startsWith('.') && importer ? resolve(dirname(importer), id) : id).replaceAll('\\', '/')
          return paths.has(candidate) ? candidate : '\0agent-design-test:component'
        }
      },
      load(id) {
        if (id === '\0agent-design-test:component') return 'import { h } from "vue"; export default { props: ["content"], render() { return h("div", this.content || "") } }'
        if (id.startsWith('\0agent-design-test:')) return stubs[id.slice('\0agent-design-test:'.length)]
      },
      async transform(source, id) {
        if (!paths.has(id.replaceAll('\\', '/'))) return
        const { descriptor } = parse(source, { filename: id })
        const script = compileScript(descriptor, { id, genDefaultAs: '__component' })
        const template = compileTemplate({ source: descriptor.template.content, filename: id, id,
          compilerOptions: { bindingMetadata: script.bindings } })
        assert.deepEqual(template.errors, [])
        return transformWithEsbuild(`${script.content}\n${template.code}\n__component.render = render; export default __component`, `${id}.ts`, { loader: 'ts' })
      },
    }],
  })
  try {
    resourceRoutes = (await server.ssrLoadModule('/src/router/modules/biz.ts')).default
    const load = async name => (await server.ssrLoadModule(`/src/components/${name}.vue`)).default
    const Knowledge = await load('agent/AgentFormKnowledge')
    const Strategy = await load('agent/AgentKnowledgeStrategy')
    const Tools = await load('agent/AgentFormTools')
    const Picker = await load('agent/studio/AgentResourcePicker')
    const Params = await load('agent/studio/AgentModelParamsPopover')
    const Model = await load('agent/AgentFormModel')
    const Messages = await load('chat/MessageList')

    await t.test('设计页资源链接保留 web 基路径，MCP 治理直接打开所选服务的工具页', async () => {
      const mounted = mount(Knowledge, ref({ modelValue: knowledgeModel(), groups: ['mcp'] }))
      const previousWindow = globalThis.window
      const opened = []
      globalThis.window = { open: (...args) => opened.push(args) }
      try {
        await mounted.router.push('/agent/config/123')
        await settle()
        mounted.setup().configMcpId = 'mcp1'
        mounted.setup().goMcpGovernance()
        assert.equal(opened.length, 1)
        const target = new URL(opened[0][0], 'http://localhost/web/#/agent/config/123')
        assert.equal(target.pathname, '/web/')
        assert.equal(target.hash, '#/mcp/mcp1/tools')
        assert.equal(opened[0][1], '_blank')
        const links = findAll(mounted.root, node => node.props.href).map(node => node.props.href)
        assert.ok(links.some(href => new URL(href, 'http://localhost/web/').hash === '#/mcp'))
        assert.ok(links.every(href => !href.includes('/agent/config/')))
        for (const meta of Object.values(mounted.setup().pickerMeta)) {
          assert.ok(!mounted.router.resolve({ name: meta.configRoute }).path.includes('/agent/config/'))
        }
      } finally {
        mounted.unmount()
        if (previousWindow === undefined) delete globalThis.window
        else globalThis.window = previousWindow
      }
    })

    await t.test('拆分资源卡各加载一次所需候选项，并通知父级刷新概览', async () => {
      const calls = []
      globalThis.__agentDesignCalls = calls
      const mounted = []
      let ready = 0
      try {
        for (const groups of [['knowledge'], ['mcp'], ['subAgent']]) {
          mounted.push(mount(Knowledge, ref({ modelValue: knowledgeModel(), groups }), { onReady: () => { ready++ } }))
        }
        for (const groups of [['tool'], ['skill'], ['workflow'], ['hook', 'sensitive']]) {
          mounted.push(mount(Tools, ref({ modelValue: { hook: [], tool: [], skill: [], workflow: [] }, groups }),
            { onReady: () => { ready++ } }))
        }
        assert.ok(mounted.every(component => component.setup().loading))
        await settle()
        assert.deepEqual(calls.sort(), ['agent', 'hook', 'knowledge', 'mcp', 'sensitive', 'skill', 'tool', 'workflow'])
        assert.equal(ready, mounted.length)
        assert.ok(mounted.every(component => !component.setup().loading))
      } finally {
        for (const component of mounted) component.unmount()
        delete globalThis.__agentDesignCalls
      }
    })

    await t.test('MCP 删除同步保存两个字段，保留其他服务的工具配置，清空后重载不会恢复', async () => {
      const retained = { mcpServerId: 'mcp2', exposureMode: 'SELECTED_ONLY', mcpToolIds: ['tool2'] }
      const props = ref({ modelValue: knowledgeModel({ mcp: ['mcp1', 'mcp2'], mcpBindings: [
        { mcpServerId: 'mcp1', exposureMode: 'ALL_GLOBAL', mcpToolIds: [] }, retained,
      ] }), groups: ['mcp'] })
      const mounted = mount(Knowledge, props, { 'onUpdate:modelValue': value => { props.value.modelValue = value } })
      try {
        await settle()
        mounted.setup().removeResource('mcp', 'mcp1')
        assert.deepEqual(props.value.modelValue.mcp, ['mcp2'])
        assert.deepEqual(props.value.modelValue.mcpBindings, [retained])
        await nextTick()
        mounted.setup().removeResource('mcp', 'mcp2')
        await settle()
        props.value.modelValue = JSON.parse(JSON.stringify(props.value.modelValue))
        await settle()
        mounted.setup().ensureMcpBindings()
        assert.deepEqual(props.value.modelValue.mcp, [])
        assert.deepEqual(props.value.modelValue.mcpBindings, [])
        mounted.setup().handlePickerConfirm('mcp', ['mcp1'])
        assert.deepEqual(props.value.modelValue.mcpBindings, [{ mcpServerId: 'mcp1', exposureMode: 'ALL_GLOBAL', mcpToolIds: [] }])
      } finally { mounted.unmount() }
    })

    await t.test('知识库选择原子回写默认参数，清空后参数保持 null，重新添加使用新默认值', async () => {
      const props = ref({ modelValue: knowledgeModel(), groups: ['knowledge'], showAdvanced: true })
      const mounted = mount(Knowledge, props, { 'onUpdate:modelValue': value => { props.value.modelValue = value } })
      try {
        await settle()
        mounted.setup().handlePickerConfirm('knowledge', ['kb1'])
        assert.deepEqual(props.value.modelValue.ragConfig, { topK: 9, scoreThreshold: 0.7, ragMode: 'GENERIC' })
        await settle()
        mounted.setup().removeResource('knowledge', 'kb1')
        await settle()
        assert.equal(props.value.modelValue.ragConfig, null)
        mounted.setup().handlePickerConfirm('knowledge', ['kb2'])
        await settle()
        assert.deepEqual(props.value.modelValue.ragConfig, { topK: 3, scoreThreshold: 0.8, ragMode: 'AGENTIC' })
        props.value.modelValue = knowledgeModel({ knowledgeBase: ['kb1'], ragConfig: { topK: 17, scoreThreshold: 0.2, ragMode: 'GENERIC' } })
        await settle()
        assert.equal(mounted.setup().ragConfigForm.topK, 17)
        mounted.setup().updateRagConfig({ topK: 12 })
        assert.deepEqual(props.value.modelValue.ragConfig, { topK: 12, scoreThreshold: 0.2, ragMode: 'GENERIC' })
      } finally { mounted.unmount() }
    })

    await t.test('拆分资源卡共用模型，加载和重载不覆盖检索参数', async () => {
      const props = ref({ modelValue: knowledgeModel({ knowledgeBase: ['kb2', 'kb1'] }), groups: ['knowledge', 'mcp'] })
      const mounted = mount(Knowledge, props)
      try {
        await settle()
        assert.equal(props.value.modelValue.ragConfig, null)
        assert.equal(mounted.setup().ragConfigForm.topK, 3)
        props.value.modelValue.ragConfig = { topK: 27, scoreThreshold: 0.1, ragMode: 'GENERIC' }
        await settle()
        assert.equal(mounted.setup().ragConfigForm.topK, 27)
        assert.equal(props.value.modelValue.ragConfig.topK, 27)
      } finally { mounted.unmount() }
    })

    await t.test('每个智能体独立选择全部 LightRAG 模式，继承和嵌套参数不会互相覆盖', async () => {
      const kb = { id: 'light', name: '图谱库', kbType: 'LIGHTRAG', ragMode: 'AGENTIC',
        retrievalConfig: { mode: 'mix', topK: 20, graphTopK: 60, maxTotalTokens: 30000 } }
      const props = ref({ modelValue: null, knowledgeBases: [kb] })
      const mounted = mount(Strategy, props, { 'onUpdate:modelValue': value => { props.value.modelValue = value } })
      try {
        await settle()
        assert.equal(props.value.modelValue, null, '加载不把默认策略写入草稿')
        assert.equal(byClass(mounted.root, 'strategy-editor').length, 0, '初始仅显示策略摘要')
        byClass(mounted.root, 'strategy-toggle')[0].props.onClick()
        await settle()
        assert.equal(props.value.modelValue, null, '展开设置不制造待保存改动')
        assert.equal(mounted.setup().presetKind, 'inherit')
        mounted.setup().patchLight('graphTopK', 12)
        await settle()
        for (const mode of ['naive', 'mix', 'local', 'global', 'hybrid']) {
          mounted.setup().patchLight('mode', mode)
          await settle()
          assert.equal(props.value.modelValue.lightRag.mode, mode)
          assert.equal(props.value.modelValue.lightRag.graphTopK, 12)
          assert.equal(kb.retrievalConfig.mode, 'mix')
        }
        mounted.setup().patch({ timeoutSeconds: 20, maxCallsPerRound: 1 })
        await settle()
        assert.equal(props.value.modelValue.lightRag.mode, 'hybrid')
        const preset = byClass(mounted.root, 'strategy-preset').find(node => findAll(node, child => child.text?.includes('速度优先')).length)
        assert.ok(preset, '显示一键推荐入口')
        preset.props.onClick()
        await settle()
        assert.equal(props.value.modelValue.ragMode, 'GENERIC')
        assert.equal(props.value.modelValue.maxCallsPerRound, 1)
        assert.equal(props.value.modelValue.lightRag.mode, 'naive')
        assert.equal(props.value.modelValue.strictEvidence, true)
        assert.equal(props.value.modelValue.maxImages, 2)
        assert.equal(mounted.setup().presetKind, 'fast')
        assert.equal(mounted.setup().usesGraph, false)
        assert.equal(findAll(mounted.root, node => node.props['aria-label'] === '图谱检索数量').length, 0, '向量模式收纳不生效的图谱字段')
        mounted.setup().patch({ timeoutSeconds: 25 })
        await settle()
        assert.equal(mounted.setup().presetKind, 'custom', '微调后不能仍显示已选推荐预设')
        mounted.setup().patchLight('mode', undefined)
        await settle()
        assert.equal(props.value.modelValue.lightRag.mode, null, '清空模式恢复知识库继承')
        assert.equal(mounted.setup().usesGraph, true)
        assert.equal(props.value.modelValue.lightRag.graphTopK, 20, '隐藏字段保留原有预算')
        mounted.setup().applyPreset('graph')
        await settle()
        assert.equal(mounted.setup().presetKind, 'graph')
        assert.equal(props.value.modelValue.lightRag.mode, 'mix')
        const inherited = byClass(mounted.root, 'strategy-preset').find(node => findAll(node, child => child.text?.includes('继承默认')).length)
        inherited.props.onClick()
        await settle()
        assert.equal(props.value.modelValue, null)
        assert.equal(kb.retrievalConfig.graphTopK, 60)
        assert.equal(mounted.setup().lightModeSummary, '图谱 + 向量 · mix')
        byClass(mounted.root, 'strategy-toggle')[0].props.onClick()
        await settle()
        assert.equal(byClass(mounted.root, 'strategy-editor').length, 0)
        assert.equal(props.value.modelValue, null)
      } finally { mounted.unmount() }
    })

    await t.test('知识库策略按类型和调用模式显示相关配置，继承多个库时不误报统一模式', async () => {
      const props = ref({ modelValue: null, knowledgeBases: [
        { id: 'light1', name: '向量库', kbType: 'LIGHTRAG', ragMode: 'AGENTIC', retrievalConfig: { mode: 'naive' } },
        { id: 'light2', name: '图谱库', kbType: 'LIGHTRAG', ragMode: 'GENERIC', retrievalConfig: { mode: 'local' } },
      ] })
      const mounted = mount(Strategy, props, { 'onUpdate:modelValue': value => { props.value.modelValue = value } })
      try {
        mounted.setup().openSettings()
        await settle()
        assert.equal(mounted.setup().lightModeSummary, '按知识库分别继承')
        assert.equal(mounted.setup().usesGraph, true)
        assert.equal(findAll(mounted.root, node => node.props['aria-label'] === '分数 / 相似度阈值').length, 0)
        mounted.setup().patch({ ragMode: 'GENERIC', maxCallsPerRound: 5 })
        await settle()
        mounted.setup().patch({ ragMode: 'AGENTIC' })
        await settle()
        assert.equal(props.value.modelValue.maxCallsPerRound, 5, '切换调用方式保留按需检索额度')
        props.value.knowledgeBases = records.knowledge
        props.value.modelValue = { includeImages: false, maxImages: 2, maxContextChars: null }
        await settle()
        assert.equal(mounted.setup().value.maxContextChars, 30000, '清空预算显示平台实际默认值')
        assert.equal(byClass(mounted.root, 'strategy-preset').length, 2, '非 LightRAG 不展示图谱预设')
        assert.equal(findAll(mounted.root, node => node.props['aria-label'] === '知识图谱检索模式').length, 0)
        assert.equal(findAll(mounted.root, node => node.props['aria-label'] === '最多返回图片数').length, 0)
        assert.equal(props.value.modelValue.maxImages, 2, '关闭图片保留原额度')
      } finally { mounted.unmount() }
    })

    await t.test('知识库调用策略校验无效预算，修改普通字段保留高级覆盖', async () => {
      const props = ref({ modelValue: knowledgeModel({ knowledgeBase: ['kb1'],
        ragConfig: { topK: 6, lightRag: { mode: 'local' }, maxCallsPerRound: 0 } }), groups: ['knowledge'], showAdvanced: true })
      const mounted = mount(Knowledge, props, { 'onUpdate:modelValue': value => { props.value.modelValue = value } })
      try {
        await settle()
        assert.equal(await mounted.setup().validate(), false)
        await settle()
        assert.equal(byClass(mounted.root, 'strategy-editor').length, 1, '校验失败自动打开配置，避免错误藏在收纳区')
        const alerts = findAll(mounted.root, node => node.props.type === 'error')
        assert.ok(alerts.some(node => node.props.message === '每轮最多检索次数须为 1 到 8 之间的整数'))
        mounted.setup().updateRagConfig({ topK: 8 })
        await settle()
        assert.equal(props.value.modelValue.ragConfig.lightRag.mode, 'local')
        mounted.setup().updateKnowledgeStrategy({ maxContextChars: 2000, maxChunkChars: 4000 })
        await settle()
        assert.equal(await mounted.setup().validate(), false)
      } finally { mounted.unmount() }
    })

    await t.test('工具、技能、工作流、钩子和敏感词清空不恢复，子智能体删除不影响其他资源', async () => {
      const props = ref({ modelValue: { hook: ['h'], tool: ['t'], workflow: ['w'], skill: ['s'], toolChoiceStrategy: 'AUTO',
        specificToolName: '', sensitiveWordConfigId: 'sensitive', sensitiveFilterEnabled: true }, groups: ['tool'] })
      const mounted = mount(Tools, props, { 'onUpdate:modelValue': value => { props.value.modelValue = value } })
      try {
        await settle()
        for (const [key, id] of [['hook', 'h'], ['tool', 't'], ['workflow', 'w'], ['skill', 's'], ['sensitive', 'sensitive']]) {
          mounted.setup().removeResource(key, id)
          await settle()
        }
        for (const key of ['hook', 'tool', 'workflow', 'skill']) assert.deepEqual(props.value.modelValue[key], [])
        assert.equal(props.value.modelValue.sensitiveWordConfigId, '-1')
        assert.equal(props.value.modelValue.sensitiveFilterEnabled, false)
      } finally { mounted.unmount() }
      const knowledgeProps = ref({ modelValue: knowledgeModel({ subAgent: ['a'], mcp: ['mcp1'] }) })
      const knowledge = mount(Knowledge, knowledgeProps, { 'onUpdate:modelValue': value => { knowledgeProps.value.modelValue = value } })
      try {
        await settle()
        knowledge.setup().removeResource('subAgent', 'a')
        await settle()
        assert.deepEqual(knowledgeProps.value.modelValue.subAgent, [])
        assert.deepEqual(knowledgeProps.value.modelValue.mcp, ['mcp1'])
      } finally { knowledge.unmount() }
    })

    await t.test('资源选择取消保持原值，重新打开初始化选择，确认后只应用草稿', async () => {
      const props = ref({ open: true, title: '选择资源', items: [{ id: '1', name: '资源一' }, { id: '2', name: '资源二' }], selectedIds: ['1'] })
      let selected
      const mounted = mount(Picker, props, { 'onUpdate:open': value => { props.value.open = value }, onConfirm: ids => { selected = ids } })
      try {
        assert.deepEqual(mounted.setup().draft, ['1'])
        mounted.setup().toggle(props.value.items[1])
        mounted.setup().close()
        await settle()
        assert.equal(selected, undefined)
        assert.deepEqual(props.value.selectedIds, ['1'])
        props.value.open = true
        await settle()
        assert.deepEqual(mounted.setup().draft, ['1'])
        mounted.setup().toggle(props.value.items[1])
        mounted.setup().confirm()
        assert.deepEqual(selected, ['1', '2'])
      } finally { mounted.unmount() }
    })

    await t.test('调整模型参数保留已有上下文窗口覆盖', async () => {
      let override
      const mounted = mount(Params, ref({ formData: { modelParamsOverride: { temperature: 0.4, contextWindow: 64000 } } }),
        { 'onUpdate:override': value => { override = value } })
      try {
        mounted.setup().handleOpenChange(true)
        mounted.setup().draft.temperature = 0.2
        mounted.setup().emitChange()
        assert.equal(override.temperature, 0.2)
        assert.equal(override.contextWindow, 64000)
        mounted.setup().draft.temperature = null
        mounted.setup().draft.seed = ''
        mounted.setup().emitChange()
        assert.equal(Object.hasOwn(override, 'temperature'), false)
        assert.equal(Object.hasOwn(override, 'seed'), false)
        assert.equal(override.contextWindow, 64000)
        mounted.setup().handleToggle(false)
        assert.equal(override, null)
      } finally { mounted.unmount() }
    })

    await t.test('提示词卡加载不规范化顶栏已有模型覆盖或制造脏状态', async () => {
      const props = ref({ modelValue: { modelConfigId: 'model', modelParamsOverride: { temperature: 0.2 },
        systemPromptTemplateId: '', systemPrompt: '', followTemplate: false }, promptOnly: true })
      const mounted = mount(Model, props)
      try {
        await settle()
        assert.deepEqual(props.value.modelValue.modelParamsOverride, { temperature: 0.2 })
      } finally { mounted.unmount() }
    })

    await t.test('不跟随时可从所有分类导入模板并独立编辑，重新加载保留修改', async () => {
      const props = ref({ modelValue: { modelConfigId: 'model', modelParamsOverride: null,
        systemPromptTemplateId: 'prompt1', systemPrompt: '已有自定义内容', followTemplate: false }, promptOnly: true })
      let mounted = mount(Model, props)
      try {
        await settle()
        assert.equal(props.value.modelValue.systemPrompt, '已有自定义内容', '加载候选不覆盖已保存正文')
        assert.deepEqual(mounted.setup().headerTemplateOptions.map(option => option.value), ['prompt1', 'prompt2'])
        await mounted.setup().handlePromptChange('prompt2')
        await settle()
        assert.equal(props.value.modelValue.systemPromptTemplateId, 'prompt2')
        assert.equal(props.value.modelValue.systemPrompt, '写作模板正文')
        assert.equal(props.value.modelValue.followTemplate, false)
        assert.equal(findAll(mounted.root, node => node.props.language === 'markdown')[0].props.readonly, false)
        props.value.modelValue.systemPrompt = '导入后独立修改'
        mounted.unmount()
        mounted = mount(Model, props)
        await settle()
        assert.equal(props.value.modelValue.systemPrompt, '导入后独立修改')
        assert.equal(props.value.modelValue.followTemplate, false)
      } finally { mounted.unmount() }
    })

    await t.test('跟随模板同步最新正文并只读，关闭后忽略在途同步且保留正文', async () => {
      const pending = []
      globalThis.__agentDesignDetail = () => new Promise(resolve => pending.push(resolve))
      const props = ref({ modelValue: { modelConfigId: 'model', modelParamsOverride: null,
        systemPromptTemplateId: 'prompt1', systemPrompt: '独立内容', followTemplate: false }, promptOnly: true })
      const mounted = mount(Model, props)
      try {
        await settle()
        mounted.setup().handleFollowTemplateToggle(true)
        pending.shift()({ data: { data: { content: '模板最新正文' } } })
        await settle()
        assert.equal(props.value.modelValue.systemPrompt, '模板最新正文')
        assert.equal(findAll(mounted.root, node => node.props.language === 'markdown')[0].props.readonly, true)
        mounted.setup().handleFollowTemplateToggle(false)
        assert.equal(props.value.modelValue.systemPrompt, '模板最新正文')
        const selection = mounted.setup().handlePromptChange('prompt2')
        props.value.modelValue.systemPrompt = '导入后立即修改'
        pending.shift()({ data: { data: { content: '迟到的模板正文' } } })
        await selection
        assert.equal(props.value.modelValue.systemPrompt, '导入后立即修改')
        mounted.setup().handleFollowTemplateToggle(true)
        mounted.setup().handleFollowTemplateToggle(false)
        pending.shift()({ data: { data: { content: '关闭跟随后返回的内容' } } })
        await settle()
        assert.equal(props.value.modelValue.systemPrompt, '导入后立即修改')
        assert.equal(findAll(mounted.root, node => node.props.language === 'markdown')[0].props.readonly, false)
      } finally { mounted.unmount(); delete globalThis.__agentDesignDetail }
    })

    await t.test('快速切换和清除模板选择忽略旧响应，清除保留正文并关闭跟随', async () => {
      const pending = new Map()
      globalThis.__agentDesignDetail = (_name, id) => new Promise(resolve => pending.set(id, resolve))
      const props = ref({ modelValue: { modelConfigId: 'model', modelParamsOverride: null,
        systemPromptTemplateId: '', systemPrompt: '', followTemplate: true }, promptOnly: true })
      const mounted = mount(Model, props)
      try {
        await settle()
        const first = mounted.setup().handlePromptChange('prompt1')
        const second = mounted.setup().handlePromptChange('prompt2')
        pending.get('prompt2')({ data: { data: { content: '写作最新正文' } } }); await second
        pending.get('prompt1')({ data: { data: { content: '过期客服正文' } } }); await first
        assert.equal(props.value.modelValue.systemPrompt, '写作最新正文')
        const third = mounted.setup().handlePromptChange('prompt1')
        const retained = props.value.modelValue.systemPrompt
        await mounted.setup().handlePromptChange(undefined)
        assert.equal(props.value.modelValue.systemPromptTemplateId, '')
        assert.equal(props.value.modelValue.followTemplate, false)
        pending.get('prompt1')({ data: { data: { content: '清除后返回的内容' } } }); await third
        assert.equal(props.value.modelValue.systemPrompt, retained)
        mounted.setup().formRef = { validate: async () => {} }
        assert.equal(await mounted.setup().validate(), true)
      } finally { mounted.unmount(); delete globalThis.__agentDesignDetail }
    })

    const message = (id, role, extra = {}) => ({ id, role, content: id, canRegenerate: role === 'assistant', ...extra })
    const conversation = [
      message('question1', 'user'), message('reasoning1', 'thinking', { branchIndex: 0, branchTotal: 2 }),
      message('step1', 'assistant'), message('tool1', 'tool'), message('step2', 'assistant'), message('tool2', 'tool'), message('answer1', 'assistant'),
      message('question2', 'user'), message('reasoning2', 'thinking'), message('step3', 'assistant'), message('tool3', 'tool'), message('answer2', 'assistant'),
    ]
    await t.test('每轮只有最终回复显示操作，中间步骤无操作行，分支导航保持原锚点', async () => {
      let switched
      let regenerated
      const props = ref({ messages: conversation, toolCalls: [], agentHasResult: true })
      const mounted = mount(Messages, props, { onSwitchBranch: value => { switched = value }, onRegenerate: value => { regenerated = value } })
      try {
        assert.equal(byClass(mounted.root, 'msg-reply-actions').length, 2)
        assert.equal(byClass(mounted.root, 'msg-copy-btn--assistant').length, 2)
        assert.equal(byClass(mounted.root, 'chat-message-turn').length, 4)
        const nextBranch = findAll(mounted.root, node => node.props.title === '下一版')[0]
        assert.ok(nextBranch)
        nextBranch.props.onClick()
        assert.deepEqual(switched, { messageId: 'reasoning1', delta: 1 })
        props.value.messages = conversation.map(item => item.id === 'reasoning1'
          ? { ...item, role: 'tool', content: JSON.stringify({ name: 'plot', hideDetails: true }) } : item)
        await settle()
        findAll(mounted.root, node => node.props.title === '下一版')[0].props.onClick()
        assert.deepEqual(switched, { messageId: 'reasoning1', delta: 1 }, '隐藏工具的分支仍可通过最终回复切换')
        byClass(mounted.root, 'msg-branch-text')[1].props.onClick()
        assert.equal(regenerated, 'answer2')
        props.value.branchDisabled = true
        await settle()
        assert.equal(byClass(mounted.root, 'msg-reply-actions').length, 1)
        assert.equal(byClass(mounted.root, 'msg-branch-text')[0].props.disabled, true)
      } finally { mounted.unmount() }
    })

    await t.test('流式文本、待完成的工具调用和子智能体中间文本不显示回复操作', async () => {
      const props = ref({ messages: [message('question', 'user'), message('step', 'assistant'), message('tool', 'tool')], toolCalls: [] })
      const mounted = mount(Messages, props)
      try {
        assert.equal(byClass(mounted.root, 'msg-reply-actions').length, 0)
        props.value.messages.push(message('stream', 'assistant', { isStreaming: true }))
        await settle()
        assert.equal(byClass(mounted.root, 'msg-reply-actions').length, 0)
        props.value.messages.at(-1).isStreaming = false
        await settle()
        assert.equal(byClass(mounted.root, 'msg-reply-actions').length, 1)
      } finally { mounted.unmount() }
      const Item = await load('chat/MessageItem')
      const standalone = mount(Item, ref({ id: 'step', role: 'assistant', content: '子智能体中间文本', currentIndex: 0, totalMessages: 1, readonlyInteraction: true }))
      try { assert.equal(byClass(standalone.root, 'msg-reply-actions').length, 0) } finally { standalone.unmount() }
    })
  } finally { await server.close() }
})
