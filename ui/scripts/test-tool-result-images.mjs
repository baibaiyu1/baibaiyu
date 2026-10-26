import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import { createServer, transformWithEsbuild } from 'vite'
import { createRenderer, h, nextTick, ref } from 'vue'
import { Window } from 'happy-dom'

const sourceRoot = fileURLToPath(new URL('../src/', import.meta.url)).replaceAll('\\', '/')
const components = ['chat/MessageList', 'chatHistory/MessageList', 'chat/MessageItem', 'chat/ToolCallItem', 'chat/ChatImages', 'chat/ChatImage', 'chat/ArtifactCard', 'chat/KnowledgeCitations', 'markdown/MarkdownRenderer', 'markdown/MarkdownHtml']
const paths = new Set(components.map(name => `${sourceRoot}components/${name}.vue`))
const icons = new Set([...paths].flatMap(path => [...readFileSync(path, 'utf8')
  .matchAll(/import\s*\{([^}]+)\}\s*from ['"]@ant-design\/icons-vue['"]/g)]
  .flatMap(match => match[1].split(',').map(name => name.trim()))))
const stubs = {
  '@ant-design/icons-vue': [...icons].map(name => `export const ${name} = { render: () => null }`).join('\n'),
  '@/api/workspace': 'export const download = (...args) => globalThis.__toolImageDownload(...args)',
  '@/api/attach': 'export const download = (...args) => globalThis.__toolImageDownload(...args)',
  '@/utils/chat/markdown': 'import { marked } from "marked"; export const renderMarkdown = content => marked.parse(content)',
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
function mount(Component, props) {
  const root = element('root')
  const app = renderer.createApp({ setup: () => () => h(Component, props.value) })
  app.config.warnHandler = warning => {
    if (!warning.startsWith('Failed to resolve component:')) throw new Error(warning)
  }
  app.mount(root)
  return { root, unmount: () => app.unmount() }
}

test('tool image downloads remain stable during streamed replies', { timeout: 60000 }, async t => {
  const server = await createServer({
    configFile: false,
    server: { middlewareMode: true, ws: false },
    optimizeDeps: { noDiscovery: true },
    resolve: { alias: [
      ...['@/api/workspace', '@/api/attach', '@/utils/chat/markdown'].map(id => ({ find: id, replacement: `\0tool-image-test:${id}` })),
      { find: '@', replacement: sourceRoot },
    ] },
    plugins: [{
      name: 'tool-image-test-components', enforce: 'pre',
      resolveId(id, importer) {
        if (id.startsWith('\0tool-image-test:')) return id
        if (Object.hasOwn(stubs, id)) return `\0tool-image-test:${id}`
        if (id.endsWith('.vue')) {
          const candidate = resolve(id.startsWith('/src/') ? `${sourceRoot}${id.slice(5)}`
            : id.startsWith('@/') ? `${sourceRoot}${id.slice(2)}`
              : id.startsWith('.') && importer ? resolve(dirname(importer), id) : id).replaceAll('\\', '/')
          return paths.has(candidate) ? candidate : '\0tool-image-test:component'
        }
      },
      load(id) {
        if (id === '\0tool-image-test:component') return 'import { h } from "vue"; export default { props: ["content", "readonlyInteraction"], render() { return h("div", { "data-readonly": this.readonlyInteraction }, this.content || "") } }'
        if (id.startsWith('\0tool-image-test:')) return stubs[id.slice('\0tool-image-test:'.length)]
      },
      async transform(source, id) {
        if (!paths.has(id.replaceAll('\\', '/'))) return
        const { descriptor } = parse(source, { filename: id })
        const script = compileScript(descriptor, { id, genDefaultAs: '__component' })
        const template = descriptor.template && compileTemplate({ source: descriptor.template.content, filename: id, id,
          compilerOptions: { bindingMetadata: script.bindings } })
        if (template) assert.deepEqual(template.errors, [])
        return transformWithEsbuild(`${script.content}\n${template ? `${template.code}\n__component.render = render;` : ''}\nexport default __component`, `${id}.ts`, { loader: 'ts' })
      },
    }],
  })
  const originalDownload = globalThis.__toolImageDownload
  const window = new Window()
  const dom = { window, document: window.document, Node: window.Node, Element: window.Element, ShadowRoot: window.ShadowRoot }
  const originalDom = Object.fromEntries(Object.keys(dom).map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  for (const [key, value] of Object.entries(dom)) Object.defineProperty(globalThis, key, { configurable: true, value })
  try {
    const MessageList = (await server.ssrLoadModule('/src/components/chat/MessageList.vue')).default
    const ArtifactCard = (await server.ssrLoadModule('/src/components/chat/ArtifactCard.vue')).default
    const HistoryMessageList = (await server.ssrLoadModule('/src/components/chatHistory/MessageList.vue')).default
    const { attachArtifacts } = await server.ssrLoadModule('/src/utils/chat/artifact.ts')
    const { messageMedia } = await server.ssrLoadModule('/src/utils/chat/messageMedia.ts')
    const { citedReferenceIds, citationTextParts, citationCopyText } = await server.ssrLoadModule('/src/utils/chat/citation.ts')
    const { acquireImageUrl } = await server.ssrLoadModule('/src/utils/chat/image.ts')
    const images = ['red', 'blue'].map(name => ({
      filePath: `.tool-results/call/${name}.png`, fileName: `${name}.png`, extension: 'png',
      mimeType: 'image/png', sizeBytes: 100, error: null,
    }))
    const message = (id, role, content, extra = {}) => ({ id, role, content, createdAt: '', ...extra })
    const tool = message('tool:call', 'tool', JSON.stringify({ name: 'screenshot', hideDetails: true, images }))
    const file = { id: 'file', sessionId: '123', filePath: '对话整理.docx', fileName: '对话整理.docx',
      extension: 'docx', sizeBytes: 100, createTime: '2026-10-14T10:00:04' }

    await t.test('knowledge queries render a readable status and three animated dots after thinking', async () => {
      const props = ref({ messages: [message('question', 'user', 'GMD9300'), message('thinking', 'thinking', '查询产品资料')],
        toolCalls: [{ id: 'query', name: 'retrieve_knowledge', args: '' }] })
      const view = mount(MessageList, props)
      try {
        const status = byClass(view.root, 'chat-tool-call-status')[0]
        assert.equal(findAll(status, node => node.type === '#text').map(node => node.text).join('').trim(), '正在查询知识库')
        assert.equal(byClass(view.root, 'knowledge-query-dots')[0].children.filter(node => node.type === 'i').length, 3)
        assert.equal(byClass(view.root, 'chat-tool-call')[0].props['aria-busy'], true)
      } finally { view.unmount() }
    })

    const knowledgeImages = ['product', 'unrelated'].map(name => ({ ...images[0],
      filePath: `.tool-results/knowledge/${name}.png`, fileName: `${name}.png` }))
    const knowledgeSources = ['used', 'unused'].map(referenceId => ({ knowledgeId: '42', knowledgeName: '产品库',
      referenceId, filePath: `${referenceId}.pdf`, fileName: `${referenceId}.pdf`, content: '原文片段' }))
    const knowledgeResult = message('retrieval', 'tool', JSON.stringify({ name: 'retrieve_knowledge', hideDetails: true,
      images: knowledgeImages, citations: knowledgeSources,
      artifacts: [{ ...file, filePath: 'extra-candidate.png', fileName: 'extra-candidate.png', extension: 'png' }] }))
    const knowledgeAnswer = message('knowledge-answer', 'assistant',
      `产品说明[[cite:used]]\n\n![产品图](${knowledgeImages[0].filePath})`)

    await t.test('knowledge candidates stay hidden through retrieval, streamed citations and gaps until the whole run finishes', async () => {
      const downloads = []
      globalThis.__toolImageDownload = (...args) => {
        downloads.push(args)
        return Promise.resolve({ data: new Blob(['product']) })
      }
      const question = message('knowledge-question', 'user', '产品型号')
      const props = ref({ sessionId: 'knowledge-run', toolCalls: [], isRunning: true,
        messages: [question, knowledgeResult] })
      const view = mount(MessageList, props)
      const assertPending = () => {
        assert.equal(byClass(view.root, 'chat-image').length, 0, 'no retrieval thumbnails above or inside the answer')
        assert.equal(byClass(view.root, 'knowledge-citations').length, 0, 'no candidate document cards')
        assert.equal(byClass(view.root, 'md-citation-link').length, 0, 'citation links wait for the corresponding cards')
        assert.deepEqual(downloads, [], 'hidden candidates must not trigger thumbnail downloads')
      }
      try {
        await settle()
        assertPending()
        props.value = { ...props.value, messages: [question, knowledgeResult, { ...knowledgeAnswer, isStreaming: true }] }
        await settle()
        assertPending()
        props.value = { ...props.value, messages: [question, knowledgeResult, knowledgeAnswer] }
        await settle()
        assertPending()
        props.value = { ...props.value, isRunning: false }
        await settle()
        assert.equal(byClass(view.root, 'chat-image').length, 1)
        assert.equal(byClass(view.root, 'chat-image')[0].props['data-image-key'], knowledgeImages[0].filePath)
        assert.equal(byClass(view.root, 'is-inline').length, 1, 'the used image belongs at its Markdown reference')
        assert.equal(byClass(view.root, 'chat-images').length, 0, 'unreferenced candidates never become a gallery')
        assert.deepEqual(byClass(view.root, 'knowledge-citation-file').map(node => node.text), ['used.pdf'])
        assert.equal(byClass(view.root, 'md-citation-link').length, 1)
        assert.deepEqual(downloads, [['knowledge-run', knowledgeImages[0].filePath]])
        props.value = { ...props.value, isRunning: true, messages: [...props.value.messages,
          message('next-question', 'user', '下一个产品'), { ...knowledgeResult, id: 'next-retrieval' }] }
        await settle()
        assert.equal(byClass(view.root, 'chat-image').length, 1, 'starting another run keeps the earlier answer image visible')
        assert.equal(byClass(view.root, 'knowledge-citations').length, 1, 'earlier completed citations remain visible')
        assert.equal(downloads.length, 1)
      } finally { view.unmount(); await settle() }
    })

    for (const Component of [MessageList, HistoryMessageList]) {
      await t.test(`${Component === MessageList ? 'live' : 'history'} knowledge results never expose unused images or uncited documents`, async () => {
        const downloads = []
        globalThis.__toolImageDownload = (...args) => {
          downloads.push(args)
          return Promise.resolve({ data: new Blob(['product']) })
        }
        const question = message('history-question', 'user', '产品型号')
        const evidence = JSON.parse(JSON.stringify(knowledgeResult))
        const props = ref({ sessionId: `knowledge-${Component === MessageList ? 'live' : 'history'}`, toolCalls: [],
          messages: [question, evidence, message('legacy', 'assistant', '没有段落引用的旧回答')] })
        const view = mount(Component, props)
        try {
          await settle()
          assert.equal(byClass(view.root, 'chat-image').length, 0)
          assert.equal(byClass(view.root, 'knowledge-citations').length, 0, 'retrieved documents are not assumed to be cited')
          assert.deepEqual(downloads, [])
          props.value = { ...props.value, messages: [question, evidence, JSON.parse(JSON.stringify(knowledgeAnswer))] }
          await settle()
          assert.equal(byClass(view.root, 'chat-image').length, 1)
          assert.equal(byClass(view.root, 'chat-images').length, 0)
          assert.deepEqual(byClass(view.root, 'knowledge-citation-file').map(node => node.text), ['used.pdf'])
          assert.deepEqual(downloads, [[props.value.sessionId, knowledgeImages[0].filePath]])
        } finally { view.unmount(); await settle() }
      })
    }

    await t.test('files belong to the current question even before any answer text exists', () => {
      const first = message('first', 'user', '第一次提问', { createdAt: '2026-10-14T10:00:00' })
      const answer = message('answer', 'assistant', '第一轮回答', { createdAt: '2026-10-14T10:00:01' })
      const next = message('next', 'user', '整理为 Word', { createdAt: '2026-10-14T10:00:03' })
      const messages = [first, answer, next, tool]
      const attached = attachArtifacts(messages, [file])
      assert.equal(attached[1].artifacts, undefined, 'a current file must never jump to the previous answer')
      assert.deepEqual(attached[3].artifacts, [file], 'a hidden tool result can anchor the generated file')
      assert.equal(messages[3].artifacts, undefined, 'computed input must remain unchanged')
      assert.deepEqual(attachArtifacts([next], [file])[0].artifacts, [file])
      const old = { ...file, createTime: '2026-10-14T09:00:00' }
      assert.equal(attachArtifacts([next, tool], [old])[1].artifacts, undefined, 'older paged-out files must stay out of this turn')
      const tail = message('tail', 'assistant', '分页后的回答末尾', { createdAt: '2026-10-14T10:00:06' })
      assert.deepEqual(attachArtifacts([tail], [file])[0].artifacts, [file], 'a file created before the first visible answer must survive missing paged-out questions')
      const laterQuestion = { ...next, createdAt: '2026-10-14T10:00:10' }
      assert.deepEqual(attachArtifacts([tail, laterQuestion, tool], [file])[0].artifacts, [file])
    })

    for (const Component of [MessageList, HistoryMessageList]) {
      await t.test(`${Component === MessageList ? 'live' : 'history'} citations follow the answer, remain beside hidden retrieval and isolated to their branch`, async () => {
        const citation = { knowledgeId: '42', knowledgeName: '设备库', referenceId: '1',
          fileName: '设备手册.pdf', filePath: '设备手册.pdf', content: '<script>unsafe</script>检查电源' }
        const evidence = message('tool:knowledge', 'tool', JSON.stringify({ name: 'retrieve_knowledge', hideDetails: true, citations: [citation] }))
        const props = ref({ sessionId: '123', toolCalls: [], messages: [message('q', 'user', '怎么使用'), evidence,
          message('answer', 'assistant', '按手册使用[[cite:1]]')] })
        const view = mount(Component, props)
        try {
          await settle()
          assert.equal(byClass(view.root, 'knowledge-citation-file')[0].text, '设备手册.pdf')
          assert.equal(byClass(view.root, 'knowledge-citation-excerpt')[0].text, citation.content, 'document excerpts must be text, never rendered HTML')
          const order = findAll(view.root, () => true)
          assert.ok(order.indexOf(byClass(view.root, 'knowledge-citations')[0]) > order.findIndex(node => node.text === '按手册使用'))
          props.value = { ...props.value, messages: [message('other', 'user', '其他分支'), message('a2', 'assistant', '回答')] }
          await settle()
          assert.equal(byClass(view.root, 'knowledge-citations').length, 0)
        } finally { view.unmount(); await settle() }
      })
      await t.test(`${Component === MessageList ? 'live' : 'history'} resources stay at their originating step outside collapsed tools`, async () => {
        globalThis.__toolImageDownload = () => Promise.resolve({ data: new Blob(['image']) })
        const props = ref({ sessionId: '123', toolCalls: [{ id: 'pending-tool', name: '生成文档', args: '{}' }], messages: [
          message('question', 'user', '整理文档'),
          message('intermediate', 'assistant', '先生成文件', { artifacts: [file] }),
          { ...tool, artifacts: [file] },
          message('last', 'assistant', '这是最终回答'),
        ] })
        const view = mount(Component, props)
        try {
          await settle()
          const turn = byClass(view.root, 'chat-message-turn').at(-1)
          const order = findAll(turn, () => true)
          const textIndex = order.findIndex(node => node.text === '这是最终回答')
          const cards = byClass(turn, 'artifact-card')
          const imageCards = byClass(turn, 'chat-image')
          assert.equal(cards.length, 1, 'one deduplicated generated file')
          assert.equal(imageCards.length, 2)
          assert.ok([...cards, ...imageCards].every(card => order.indexOf(card) < textIndex), 'resources must stay before the subsequent answer')
          if (Component === MessageList) {
            const progressIndex = order.indexOf(byClass(turn, 'chat-tool-call')[0])
            assert.ok(progressIndex > textIndex, 'a new active tool must not move earlier resources')
          }
          assert.equal(byClass(turn, 'msg-artifacts').length, 1)
          if (Component === HistoryMessageList) assert.equal(byClass(turn, 'msg-reply-actions').length, 0,
            'history interactions must remain read-only')
        } finally { view.unmount(); await settle() }
      })
    }

    await t.test('paragraph citations keep stable numbers across knowledge bases, repeated calls, streaming and history', () => {
      const source = (knowledgeId, referenceId, filePath = '手册.pdf') => ({ knowledgeId, referenceId, filePath,
        knowledgeName: `库${knowledgeId}`, fileName: filePath, content: '原文' })
      const evidence = (id, citations) => message(id, 'tool', JSON.stringify({ name: 'retrieve_knowledge', hideDetails: true, citations }))
      const inputs = [message('q-cite', 'user', '说明'),
        evidence('r1', [source('a', 'kb-a'), source('b', 'kb-b')]),
        message('intermediate', 'assistant', '甲段[[cite:kb-b]]。'),
        evidence('r2', [source('a', 'kb-a'), source('b', 'kb-b')]),
        message('final', 'assistant', '乙段[[cite:kb-a]][[cite:kb-b]]。未知[[cite:forged]]。')]
      const media = messageMedia(inputs, '123')
      const active = messageMedia(inputs, '123', true)
      assert.ok([...active.values()].every(entry => !entry.citations.length && !entry.citationReferences.length),
        'completed text segments do not end a run or expose temporary citations')
      const pending = [inputs[0], inputs[1], message('pending', 'assistant', '正在组织回答', { isStreaming: true })]
      assert.deepEqual(messageMedia(pending, '123').get('pending').citations, [], '段落来源尚未生成时不闪现临时编号')
      assert.equal(media.get('r1').citations.length, 0)
      assert.equal(media.get('intermediate').citations.length, 0)
      assert.deepEqual(media.get('final').citations.map(c => [c.knowledgeId, c.number]), [['b', 1], ['a', 2]])
      assert.equal(new Set(media.get('final').citations.map(c => c.anchorId)).size, 2)
      const refs = media.get('final').citationReferences
      assert.deepEqual(citationTextParts('依据[[cite:kb-a]][[cite:forged]]。', refs).map(p => typeof p === 'string' ? p : p.number), ['依据', 2, '。'])
      assert.deepEqual(citationTextParts('依据[[cite:kb-', refs), ['依据'])
      assert.deepEqual(citedReferenceIds('`[[cite:kb-a]]`\n\n```text\n[[cite:kb-b]]\n```\n\n正确[[cite:kb-a]] [链接](https://x.test/[[cite:kb-b]])'), ['kb-a'])
      const copy = citationCopyText(inputs.at(-1).content, refs)
      assert.match(copy, /乙段\[2\]\[1\]/)
      assert.match(copy, /\[1\] 手册.pdf（库b）/)
      assert.ok(!copy.includes('forged'))
      assert.match(citationCopyText('`[[cite:kb-a]]` 正文[[cite:kb-b]]', refs), /^`\[\[cite:kb-a\]\]` 正文\[1\]/)
      assert.deepEqual(messageMedia(JSON.parse(JSON.stringify(inputs)), '123').get('final').citations, media.get('final').citations)
      inputs.at(-1).content += '继续生成'
      assert.deepEqual(messageMedia(inputs, '123').get('final').citations, media.get('final').citations)
      const anotherTurn = messageMedia([...inputs, message('q-next', 'user', '下一个问题'),
        message('next', 'assistant', '不能沿用[[cite:kb-a]]')], '123')
      assert.deepEqual(anotherTurn.get('next').citationReferences, [])
      assert.deepEqual(anotherTurn.get('next').citations, [])
    })

    await t.test('ambiguous legacy source numbers cannot create false paragraph attribution', () => {
      const citations = ['a', 'b'].map(knowledgeId => ({ knowledgeId, referenceId: '1', knowledgeName: knowledgeId,
        filePath: '同名.pdf', fileName: '同名.pdf', content: '内容' }))
      const inputs = [message('q', 'user', '问题'), message('r', 'tool', JSON.stringify({ name: 'retrieve_knowledge', citations })),
        message('a', 'assistant', '文字[[cite:1]]')]
      const result = messageMedia(inputs, '123').get('a')
      assert.deepEqual(result.citations, [], 'ambiguous source IDs must not expose candidate documents')
      assert.deepEqual(citationTextParts(inputs.at(-1).content, result.citationReferences), ['文字', ''])
    })

    await t.test('a failed download can retry in the preview without stale leases evicting the new thumbnail', async () => {
      let calls = 0
      globalThis.__toolImageDownload = () => ++calls === 1 ? Promise.reject(new Error('temporary')) : Promise.resolve({ data: new Blob(['image']) })
      const source = { kind: 'workspace', sessionId: 'retry', path: 'retry.png' }
      const failed = acquireImageUrl(source)
      await assert.rejects(failed.url)
      const retry = acquireImageUrl(source)
      assert.ok((await retry.url).startsWith('blob:'))
      failed.release()
      await settle()
      const another = acquireImageUrl(source)
      assert.equal(await another.url, await retry.url)
      assert.equal(calls, 2)
      another.release(); retry.release()
      await settle()
    })

    for (const mode of ['complete', 'pending', 'failed']) {
      await t.test(`streaming does not reload ${mode} thumbnails or close their preview`, async () => {
        const downloads = []
        const pending = []
        globalThis.__toolImageDownload = (...args) => {
          downloads.push(args)
          if (mode === 'pending') return new Promise(resolve => pending.push(() => resolve({ data: new Blob(['image']) })))
          return Promise.resolve({ data: new Blob(mode === 'failed' ? [] : ['image']) })
        }
        const props = ref({ sessionId: '123', toolCalls: [], branchDisabled: true, messages: [
          message('question', 'user', '把对话内容写成 Word'), tool,
          message('thinking', 'thinking', '', { isStreaming: true }),
        ] })
        const view = mount(MessageList, props)
        try {
          await settle()
          assert.equal(downloads.length, 2)
          byClass(view.root, 'chat-image-content')[0].props.onClick()
          await settle()
          for (let i = 1; i <= 30; i++) {
            props.value = { ...props.value, messages: props.value.messages.map(item => ({
              ...item, content: item.id === 'thinking' ? '正在整理文档'.repeat(i) : item.content,
            })) }
            await settle()
          }
          assert.equal(downloads.length, 2, 'each image should download once across all streamed tokens')
          assert.ok(findAll(view.root, node => node.props.visible === true).length, 'stream updates must keep the preview open')
          for (const resolve of pending) resolve()
          await settle()
          assert.equal(byClass(view.root, 'chat-image').length, 2)
        } finally { view.unmount(); await settle() }
      })
    }

    await t.test('body references take priority while code examples and other turns cannot capture images', () => {
      const scoped = [message('q', 'user', '截图'), tool,
        message('body', 'assistant', '图片在这里：\n\n![截图](.tool-results/call/red.png)\n\n继续解释。')]
      const placed = messageMedia(scoped, '123')
      assert.deepEqual(placed.get(tool.id).images.map(image => image.key), [images[1].filePath])
      assert.equal(placed.get('body').references.length, 2)
      const code = messageMedia([...scoped.slice(0, 2), message('code', 'assistant', '```md\n![截图](.tool-results/call/red.png)\n```')], '123')
      assert.equal(code.get(tool.id).images.length, 2)
      const next = messageMedia([...scoped.slice(0, 2), message('q2', 'user', '下一轮'), scoped[2]], '123')
      assert.equal(next.get(tool.id).images.length, 2)
      assert.equal(next.get('body').references.length, 0)
      const local = { ...images[0], sourcePath: '图表.png' }
      const generated = message('generated', 'tool', JSON.stringify({ name: 'plot', images: [local] }))
      const linked = messageMedia([generated, message('description', 'assistant', '![趋势图](%E5%9B%BE%E8%A1%A8.png)')], '123')
      assert.equal(linked.get('generated').images.length, 0, 'original paths resolve to saved snapshots')
      assert.equal(linked.get('description').references[0].source.path, images[0].filePath)
      const repeated = message('repeated', 'tool', JSON.stringify({ name: 'plot', images: [local, local] }))
      assert.equal(messageMedia([repeated], '123').get('repeated').images.length, 1, 'duplicate images in one result remain visible once')
      const producedAgain = messageMedia([...scoped, { ...tool, id: 'later-tool' }], '123')
      assert.equal(producedAgain.get('later-tool').images.length, 2, 'an earlier reference must not consume a later tool result at the same path')
    })

    await t.test('artifact snapshots stay attached to the original tool after refresh and with details hidden', () => {
      const origin = message('origin', 'tool', JSON.stringify({ toolCallId: 'plot', name: 'execute_shell_command',
        hideDetails: true, artifacts: [{ ...file, filePath: '趋势图.png', fileName: '趋势图.png', extension: 'png' }] }))
      const messages = [message('q', 'user', '生成图表'), origin, message('answer', 'assistant', '说明')]
      const attached = attachArtifacts(messages, [{ ...file, filePath: '趋势图.png', extension: 'png', createTime: '2026-10-14T10:05:00' }])
      assert.equal(attached[2].artifacts, undefined)
      const media = messageMedia(JSON.parse(JSON.stringify(attached)), '123')
      assert.equal(media.get('origin').images[0].source.path, '趋势图.png')
      assert.equal(media.get('answer').images.length, 0)
    })

    await t.test('changing session or file reloads while metadata changes preserve the loaded thumbnail', async () => {
      const downloads = []
      globalThis.__toolImageDownload = (...args) => {
        downloads.push(args)
        return Promise.resolve({ data: new Blob(['image']) })
      }
      const props = ref({ sessionId: '123', artifact: { ...images[0], id: 'image', sourceTool: 'screenshot' } })
      const view = mount(ArtifactCard, props)
      try {
        await settle()
        const src = () => findAll(view.root, node => node.type === 'img')[0]?.props.src
        const first = src()
        assert.ok(first?.startsWith('blob:'))
        props.value = { ...props.value, artifact: { ...props.value.artifact, fileName: '新名称.png' } }
        await settle()
        assert.equal(downloads.length, 1)
        assert.equal(src(), first)
        props.value = { ...props.value, sessionId: '456' }
        await settle()
        assert.equal(downloads.length, 2)
        assert.notEqual(src(), first)
        props.value = { ...props.value, artifact: { ...props.value.artifact, filePath: images[1].filePath } }
        await settle()
        assert.deepEqual(downloads, [['123', images[0].filePath], ['456', images[0].filePath], ['456', images[1].filePath]])
      } finally { view.unmount(); await settle() }
    })
  } finally {
    globalThis.__toolImageDownload = originalDownload
    await server.close()
    await window.happyDOM.close()
    for (const [key, descriptor] of Object.entries(originalDom)) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor)
      else delete globalThis[key]
    }
  }
})
