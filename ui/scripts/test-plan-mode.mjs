import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import { createServer, transformWithEsbuild } from 'vite'
import { createRenderer, h, nextTick, ref } from 'vue'

const rootPath = fileURLToPath(new URL('../src/', import.meta.url)).replaceAll('\\', '/')
const target = `${rootPath}components/chat/AgentPlanDocument.vue`
const node = (type, text = '') => ({ type, text, children: [], props: {}, parent: null })
const renderer = createRenderer({
  createElement: node, createText: text => node('#text', text), createComment: text => node('#comment', text),
  setText: (n, text) => { n.text = text }, setElementText: (n, text) => { n.text = text; n.children = [] },
  parentNode: n => n.parent, nextSibling: n => n.parent?.children[n.parent.children.indexOf(n) + 1] || null,
  patchProp: (n, key, _old, value) => { n.props[key] = value },
  insert(n, parent, anchor) {
    if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1)
    n.parent = parent
    const index = parent.children.indexOf(anchor)
    if (index < 0) parent.children.push(n); else parent.children.splice(index, 0, n)
  },
  remove(n) { if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1); n.parent = null },
})
const all = (root, predicate) => [...(predicate(root) ? [root] : []), ...root.children.flatMap(n => all(n, predicate))]
const text = root => [root.text, ...root.children.map(text)].join(' ')
const settle = async () => { await nextTick(); await new Promise(resolve => setImmediate(resolve)); await nextTick() }
const doc = (status, content) => ({ status, content, revision: 2, path: '.plans/PLAN.md', digest: 'digest', approvalCallId: 'call' })

test('方案恢复隔离会话并展示当前批准状态，复用只读预览', { timeout: 60000 }, async () => {
  const server = await createServer({ configFile: false, server: { middlewareMode: true, hmr: false }, optimizeDeps: { noDiscovery: true },
    resolve: { alias: [{ find: '@/api/agui/plan', replacement: '\0plan-fixture' }, { find: '@', replacement: rootPath }] },
    plugins: [{ name: 'plan-fixture', enforce: 'pre',
      resolveId(id) {
        if (id === '\0plan-fixture') return id
        if (id.endsWith('MarkdownRenderer.vue')) return '\0markdown-fixture'
      },
      load(id) {
        if (id === '\0plan-fixture') return 'export const getPlanDocument = id => globalThis.planFixture(id)'
        if (id === '\0markdown-fixture') return 'import { h } from "vue"; export default { props: { content: String, readonlyInteraction: Boolean }, render() { return h("div", { "data-readonly": this.readonlyInteraction }, this.content) } }'
      },
      async transform(source, id) {
        if (id.replaceAll('\\', '/') !== target) return
        const { descriptor } = parse(source, { filename: id })
        const script = compileScript(descriptor, { id, genDefaultAs: '__component' })
        const template = compileTemplate({ source: descriptor.template.content, filename: id, id, compilerOptions: { bindingMetadata: script.bindings } })
        assert.deepEqual(template.errors, [])
        return transformWithEsbuild(`${script.content}\n${template.code}\n__component.render = render; export default __component`, `${id}.ts`, { loader: 'ts' })
      },
    }] })
  const props = ref({ sessionId: 'old', isRunning: false }), opened = [], queries = []
  let oldResult, app
  globalThis.planFixture = id => {
    queries.push(id)
    return id === 'old' ? new Promise(resolve => { oldResult = resolve }) : Promise.resolve(doc('AWAITING_APPROVAL', '# 当前方案'))
  }
  try {
    const Component = (await server.ssrLoadModule('/src/components/chat/AgentPlanDocument.vue')).default
    const root = node('root')
    app = renderer.createApp({ setup: () => () => h(Component, { ...props.value, onOpenFile: path => opened.push(path) }) })
    app.config.warnHandler = warning => { if (!warning.startsWith('Failed to resolve component:')) throw new Error(warning) }
    app.mount(root)
    props.value = { sessionId: 'new', isRunning: false }; await settle()
    oldResult(doc('APPROVED', '旧会话内容')); await settle()
    assert.match(text(root), /当前方案/); assert.doesNotMatch(text(root), /旧会话内容/)
    assert.match(text(root), /等待方案批准/)
    assert.equal(all(root, n => n.props['data-readonly'] === true).length, 1)
    all(root, n => n.type === 'AButton')[0].props.onClick()
    assert.deepEqual(opened, ['.plans/PLAN.md'])
    globalThis.planFixture = async () => doc('STALE', '# 已变更方案')
    props.value.isRunning = true; await settle()
    assert.match(text(root), /旧批准已失效/)
    globalThis.planFixture = async () => doc('APPROVED', '# 已批准方案')
    props.value.isRunning = false; await settle()
    assert.match(text(root), /已批准，可执行/)
    assert.equal(all(root, n => n.type === 'details')[0].props.open, false)
    app.unmount(); app = null
    const count = queries.length
    await settle()
    assert.equal(queries.length, count)
  } finally { app?.unmount(); await server.close(); delete globalThis.planFixture }
})
