import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import { createServer, transformWithEsbuild } from 'vite'
import { createRenderer, h, nextTick, ref } from 'vue'

test('对话滚动尊重阅读位置并跟随图片尺寸变化', { timeout: 30000 }, async t => {
  const rootPath = fileURLToPath(new URL('../src/', import.meta.url)).replaceAll('\\', '/')
  const target = `${rootPath}components/chat/ChatMain.vue`
  const frames = new Map()
  const observers = []
  let nextFrame = 0
  const originals = [globalThis.requestAnimationFrame, globalThis.cancelAnimationFrame, globalThis.ResizeObserver]
  globalThis.requestAnimationFrame = callback => { frames.set(++nextFrame, callback); return nextFrame }
  globalThis.cancelAnimationFrame = id => frames.delete(id)
  globalThis.ResizeObserver = class {
    constructor(callback) { this.callback = callback; observers.push(this) }
    observe(element) { this.element = element }
    disconnect() { this.element = null }
  }
  const server = await createServer({
    configFile: false, server: { middlewareMode: true, ws: false }, optimizeDeps: { noDiscovery: true },
    resolve: { alias: [{ find: '@/stores', replacement: '\0scroll-stores' }, { find: '@', replacement: rootPath }] },
    plugins: [{
      name: 'chat-scroll-test', enforce: 'pre',
      resolveId(id) {
        if (id.startsWith('\0scroll-')) return id
        if (id === '@/stores') return '\0scroll-stores'
        if (id === '@ant-design/icons-vue') return '\0scroll-icons'
        if (id.endsWith('.vue') && !id.endsWith('ChatMain.vue')) return '\0scroll-component'
      },
      load(id) {
        if (id === '\0scroll-stores') return 'export const useAccountStore = () => ({ hasPerm: () => false })'
        if (id === '\0scroll-icons') return ['MenuOutlined', 'FolderOutlined', 'FolderOpenOutlined', 'LoadingOutlined', 'ArrowDownOutlined'].map(name => `export const ${name} = { render() { return null } }`).join('\n')
        if (id === '\0scroll-component') return 'export default { render() { return null } }'
      },
      async transform(source, id) {
        if (id.replaceAll('\\', '/') !== target) return
        const { descriptor } = parse(source, { filename: id })
        const script = compileScript(descriptor, { id, genDefaultAs: '__component' })
        const template = compileTemplate({ source: descriptor.template.content, filename: id, id,
          compilerOptions: { bindingMetadata: script.bindings } })
        assert.deepEqual(template.errors, [])
        return transformWithEsbuild(`${script.content}\n${template.code}\n__component.render = render; export default __component`, `${id}.ts`, { loader: 'ts' })
      },
    }],
  })
  function element(type) {
    const node = { type, children: [], props: {}, parent: null, scrollHeight: 2000, clientHeight: 500, top: 0 }
    Object.defineProperty(node, 'scrollTop', { get: () => node.top, set: value => { node.top = Math.max(0, Math.min(value, node.scrollHeight - node.clientHeight)) } })
    return node
  }
  const renderer = createRenderer({
    createElement: element, createText: () => element('#text'), createComment: () => element('#comment'),
    setText(node, text) { node.text = text }, setElementText(node, text) { node.text = text; node.children = [] },
    parentNode: node => node.parent, nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] || null,
    patchProp(node, key, previous, next) { node.props[key] = next },
    insert(node, parent, anchor) {
      if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
      node.parent = parent
      const index = parent.children.indexOf(anchor)
      if (index < 0) parent.children.push(node); else parent.children.splice(index, 0, node)
    },
    remove(node) { node.parent?.children.splice(node.parent.children.indexOf(node), 1) },
  })
  const find = (node, name) => String(node.props.class || '').split(' ').includes(name) ? node : node.children.map(child => find(child, name)).find(Boolean)
  const flush = async () => { await nextTick(); const current = [...frames.values()]; frames.clear(); current.forEach(callback => callback()); await nextTick() }
  let app
  try {
    const Component = (await server.ssrLoadModule('/src/components/chat/ChatMain.vue')).default
    const props = ref({ title: '测试', messageSize: 2, welcomeHeadline: '测试', messages: [
      { id: 'q', role: 'user', content: '问题' }, { id: 'a', role: 'assistant', content: '回答' },
    ], toolCalls: [], inputValue: '', isRunning: true, agentId: 'test', sessionId: 'one' })
    const root = element('root')
    let historyRequests = 0
    app = renderer.createApp({ render: () => h(Component, { ...props.value, onLoadMoreHistory: () => historyRequests++ }) })
    app.component('ATooltip', { setup(_, { slots }) { return () => h('span', slots.default?.()) } })
    app.directive('permission', {})
    app.mount(root)
    await flush()
    const scroll = find(root, 'chat-main-messages-scroll')
    const onScroll = () => scroll.props.onScroll({ target: scroll })
    await t.test('初次展示、图片延迟加载和流式内容变化跟随底部', async () => {
      assert.equal(scroll.scrollTop, 1500)
      onScroll()
      scroll.scrollHeight += 200
      observers.at(-1).callback()
      await flush()
      assert.equal(scroll.scrollTop, 1700)
    })
    await t.test('主动上滑会取消已排队的滚动，生成文字和图片均不抢回位置', async () => {
      props.value = { ...props.value, messages: props.value.messages.map(message => ({ ...message, content: message.content + '新增' })) }
      await nextTick()
      assert.ok(frames.size)
      scroll.props.onWheelPassive({ deltaY: -100 })
      scroll.scrollTop = 800
      onScroll()
      scroll.scrollHeight += 100
      observers.at(-1).callback()
      await flush()
      assert.equal(scroll.scrollTop, 800)
      assert.ok(find(root, 'chat-return-latest'))
    })
    await t.test('回到最新后恢复跟随，紧接着拖动滚动条仍立即生效', async () => {
      find(root, 'chat-return-latest').props.onClick()
      await flush()
      assert.equal(scroll.scrollTop, 1800)
      assert.equal(!!find(root, 'chat-return-latest'), false)
      scroll.scrollTop = 700
      onScroll()
      props.value = { ...props.value, toolCalls: [{ id: 'tool' }] }
      await flush()
      assert.equal(scroll.scrollTop, 700)
    })
    await t.test('触顶只发一次历史请求，头部插入后保持原阅读内容位置', async () => {
      props.value = { ...props.value, hasMoreHistory: true }
      await nextTick()
      scroll.scrollTop = 10
      onScroll(); onScroll()
      assert.equal(historyRequests, 1)
      props.value = { ...props.value, historyLoading: true }
      await nextTick()
      scroll.scrollHeight += 300
      props.value = { ...props.value, messages: [{ id: 'earlier', role: 'user', content: '历史' }, ...props.value.messages], historyLoading: false }
      await flush()
      assert.equal(scroll.scrollTop, 310)
    })
    await t.test('切换会话重新跟随，卸载取消待执行回调并释放尺寸观察器', async () => {
      props.value = { ...props.value, sessionId: 'two' }
      await flush()
      assert.equal(scroll.scrollTop, scroll.scrollHeight - scroll.clientHeight)
      observers.at(-1).callback()
      assert.ok(frames.size)
      app.unmount(); app = null
      assert.equal(frames.size, 0)
      assert.equal(observers.at(-1).element, null)
    })
  } finally {
    app?.unmount()
    await server.close()
    ;[globalThis.requestAnimationFrame, globalThis.cancelAnimationFrame, globalThis.ResizeObserver] = originals
  }
})
