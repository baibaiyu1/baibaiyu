import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { createRenderer, ref, nextTick } from 'vue'

const renderer = createRenderer({ insert() {}, remove() {}, createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
  setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null, patchProp() {} })

test('语音就绪等待、超时清理、消息去重、插话取消与会话隔离', { timeout: 60000 }, async () => {
  let activeRoom, responses = [], rpcCalls = [], tasks = 0, closeCount = 0, api, app
  class Room {
    constructor() { activeRoom = this; this.handlers = {}; this.rpc = {}; this.remoteParticipants = new Map(globalThis.voiceFixture.participants ?? [['voice-agent-test', { identity: 'voice-agent-test', kind: 4, attributes: { 'lk.agent.state': 'listening' } }]])
      this.localParticipant = { setMicrophoneEnabled: async () => {}, performRpc: async input => {
        rpcCalls.push({ ...input, value: JSON.parse(input.payload) })
        if (globalThis.voiceFixture.rpc) return globalThis.voiceFixture.rpc(input)
        return '{"accepted":true}'
      } } }
    registerRpcMethod(name, handler) { this.rpc[name] = handler }
    on(name, handler) { this.handlers[name] = handler }
    async connect() {}
    async startAudio() {}
    async disconnect() { closeCount++ }
  }
  globalThis.voiceFixture = {
    Room,
    getVoiceAvailability: async () => ({ available: true }),
    connectVoice: async () => ({ serverUrl: 'ws://test', participantToken: 'token' }),
    voiceTurn: (_sid, input, signal) => new Promise(resolve => { responses.push({ input, signal, resolve }) }),
  }
  const savedWindow = globalThis.window, savedNavigator = Object.getOwnPropertyDescriptor(globalThis, 'navigator')
  const savedSetTimeout = globalThis.setTimeout
  let agentTimeout
  globalThis.setTimeout = (handler, delay, ...args) => {
    if (delay === 45000) { agentTimeout = () => handler(...args); return savedSetTimeout(() => {}, delay) }
    return savedSetTimeout(handler, delay, ...args)
  }
  globalThis.window = { isSecureContext: true }
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { mediaDevices: { getUserMedia() {} } } })
  const server = await createServer({ root: fileURLToPath(new URL('..', import.meta.url)), configFile: false, server: { middlewareMode: true }, optimizeDeps: { noDiscovery: true },
    resolve: { alias: [{ find: 'livekit-client', replacement: '\0livekit-fixture' }, { find: '@/api/agui/voice', replacement: '\0voice-fixture' },
      { find: '@', replacement: fileURLToPath(new URL('../src', import.meta.url)) }] },
    plugins: [{ name: 'voice-fixture', resolveId: id => id.startsWith('\0') ? id : undefined, load: id => id === '\0livekit-fixture'
      ? `export const Room = globalThis.voiceFixture.Room; export const ParticipantKind = { AGENT: 4 };
          export const Track = { Kind: { Audio: 'audio' } };
          export const RoomEvent = Object.fromEntries(['ParticipantConnected','ParticipantAttributesChanged','TrackSubscribed','TrackUnsubscribed','AudioPlaybackStatusChanged','DataReceived','Reconnecting','Reconnected','ParticipantDisconnected','Disconnected'].map(x => [x,x]));`
      : id === '\0voice-fixture' ? `export const getVoiceAvailability = (...a) => globalThis.voiceFixture.getVoiceAvailability(...a);
          export const connectVoice = (...a) => globalThis.voiceFixture.connectVoice(...a);
          export const voiceTurn = (...a) => globalThis.voiceFixture.voiceTurn(...a);` : undefined }] })
  const settle = async () => { await nextTick(); await new Promise(resolve => setImmediate(resolve)); await nextTick() }
  const session = ref('1'), enabled = ref(true)
  const call = (id, text = '查资料') => activeRoom.rpc['gmd.voice.turn']({ callerIdentity: 'voice-agent-test', payload: JSON.stringify({ requestId: id, text }) })
  try {
    const { useVoiceConversation } = await server.ssrLoadModule('/src/composables/chat/useVoiceConversation.ts')
    app = renderer.createApp({ setup() { api = useVoiceConversation(session, enabled, {
      ensureSession: async () => session.value, submitTask: async () => { tasks++; return '已接收' }, submitInstruction: async () => '已补充',
    }); return () => null } }); app.mount({}); await settle(); await api.start()
    assert.equal(api.connected.value, true)
    const first = call('one'), duplicate = call('one'); await settle()
    assert.equal(responses.length, 1)
    responses[0].resolve({ requestId: 'one', speech: '收到', action: 'TASK', content: '查资料' })
    assert.equal(await first, await duplicate); assert.equal(tasks, 1)
    await call('one'); assert.equal(tasks, 1)
    const interrupted = call('two'); await settle()
    activeRoom.handlers.DataReceived(new TextEncoder().encode(JSON.stringify({ state: 'speaking', revision: 1 })),
      activeRoom.remoteParticipants.get('voice-agent-test'), 0, 'gmd.voice.user')
    assert.equal(responses[1].signal.aborted, true)
    responses[1].resolve({ requestId: 'two', speech: '旧回复', action: 'TASK', content: '不应执行' })
    assert.equal(JSON.parse(await interrupted).speech, ''); assert.equal(tasks, 1)
    await assert.rejects(activeRoom.rpc['gmd.voice.turn']({ callerIdentity: 'intruder', payload: '{}' }))
    const stale = call('three'); await settle(); session.value = '2'; await settle()
    assert.equal(api.connected.value, false); assert.equal(responses[2].signal.aborted, true)
    responses[2].resolve({ requestId: 'three', speech: '旧会话', action: 'TASK', content: '不应执行' })
    assert.equal(JSON.parse(await stale).speech, ''); assert.equal(tasks, 1); assert.ok(closeCount > 0)
    let releaseConnect
    globalThis.voiceFixture.connectVoice = () => new Promise(resolve => { releaseConnect = resolve })
    const connecting = api.start(); await settle(); await api.disconnect()
    releaseConnect({ serverUrl: 'ws://test', participantToken: 'token' }); await connecting
    assert.equal(api.connected.value, false, '连接期间结束语音不能被迟到的令牌重启')
    globalThis.voiceFixture.connectVoice = async () => ({ serverUrl: 'ws://test', participantToken: 'token' })
    globalThis.voiceFixture.participants = []
    await api.start()
    assert.equal(api.connected.value, false, '只有 LiveKit 房间连接不能视为语音已就绪')
    assert.equal(api.connecting.value, true)
    assert.equal(api.state.value, '等待语音助手就绪')
    await agentTimeout(); await settle()
    assert.equal(api.active.value, false, 'worker 未上线必须超时断开并释放麦克风')
    assert.match(api.error.value, /scripts\/start.ps1/)
    globalThis.voiceFixture.participants = [['voice-agent-test', { identity: 'voice-agent-test', kind: 4, attributes: { 'lk.agent.state': 'initializing' } }]]
    await api.start()
    assert.equal(api.connected.value, false, 'worker 加入房间但未初始化完成时继续等待')
    const initializingTimeout = agentTimeout
    await initializingTimeout(); await settle()
    assert.equal(api.active.value, false, 'worker 初始化卡住也必须超时')
    await api.start()
    const agent = activeRoom.remoteParticipants.get('voice-agent-test')
    agent.attributes['lk.agent.state'] = 'listening'
    activeRoom.handlers.ParticipantAttributesChanged({ 'lk.agent.state': 'listening' }, agent)
    assert.equal(api.connected.value, true, '识别服务就绪后才能显示已连接')
    assert.equal(api.connecting.value, false)
    await agentTimeout(); await settle()
    assert.equal(api.connected.value, true, '迟到的就绪超时不能断开已就绪语音')

    const event = (type, runId, extra = {}) => api.businessEvent({ type, sessionId: session.value, runId, ...extra })
    const text = (runId, content, extra = {}) => event('text', runId,
      { messageId: 'reply', text: content, delta: content, final: false, replay: false, ...extra })
    const spoken = () => rpcCalls.filter(call => call.method === 'gmd.voice.stream' && !call.value.cancel)
    event('start', 'early')
    text('early', '第一句话。')
    await settle()
    assert.equal(spoken().at(-1).value.text, '第一句话。\n', '首句直接送入 TTS，无需等待 RUN_FINISHED')
    assert.equal(spoken().at(-1).value.final, false)
    const earlyCount = spoken().length
    text('early', '第一句话。', { delta: '' })
    await settle()
    assert.equal(spoken().length, earlyCount, '重复正文不能重复播报')
    text('early', '第一句话。第二句继续生成。', { delta: '第二句继续生成。' })
    event('finish', 'early')
    await settle()
    assert.equal(spoken().at(-1).value.final, true)
    assert.equal(responses.length, 3, '流式回答结束后不再请求摘要模型')

    const reader = (await server.ssrLoadModule('/src/utils/chat/voice-text.ts')).VoiceTextBuffer
    const markdown = new reader()
    const raw = '## 结果\n**可以使用。**请看[说明](https://example.com/a)。\n```js\nsecret();\n```\n![图](https://image.test/x)后续正常。'
    let said = []
    for (let index = 1; index <= raw.length; index++) said.push(...markdown.update(raw.slice(0, index)))
    said.push(...markdown.finish())
    assert.match(said.join(''), /可以使用。请看说明。/)
    assert.match(said.join(''), /后续正常。/)
    assert.doesNotMatch(said.join(''), /https|secret|```|\*\*|图/)
    const replayedLink = new reader()
    replayedLink.update('[历史链接', false, false)
    assert.deepEqual(replayedLink.update('[历史链接](https://example.com)。新句。', true), ['新句。'])

    rpcCalls = []
    event('start', 'replay')
    text('replay', '历史内容。```java\n不应读出的代码', { replay: true })
    text('replay', '历史内容。```java\n不应读出的代码\n```\n这是新内容。', { delta: '\n```\n这是新内容。' })
    event('finish', 'replay')
    await settle()
    assert.equal(spoken().map(call => call.value.text).join(''), '这是新内容。\n', '回放、代码不读，只读追上后新生成的正文')

    event('start', 'interrupted-answer')
    text('interrupted-answer', '正在播报。')
    await settle()
    const countBeforeInterrupt = spoken().length
    await api.interrupt()
    text('interrupted-answer', '正在播报。旧内容不能继续。', { delta: '旧内容不能继续。' })
    event('finish', 'interrupted-answer')
    await settle()
    assert.equal(spoken().length, countBeforeInterrupt)
    assert.ok(rpcCalls.some(call => call.value.cancel), '停止播报须取消 worker 流，丢弃排队的句子')

    event('start', 'abort-run')
    text('abort-run', '任务中止之前。')
    event('cancel', 'abort-run')
    text('abort-run', '任务中止之前。迟到的句子。', { delta: '迟到的句子。' })
    await settle()
    assert.doesNotMatch(spoken().map(call => call.value.text).join(''), /迟到的句子/)

    event('start', 'reconnect-answer')
    text('reconnect-answer', '断线前内容。')
    await settle()
    activeRoom.handlers.Reconnecting()
    text('reconnect-answer', '断线前内容。断线期间内容。', { delta: '断线期间内容。' })
    activeRoom.handlers.Reconnected()
    await settle()
    text('reconnect-answer', '断线前内容。断线期间内容。重连后旧轮次。', { delta: '重连后旧轮次。' })
    await settle()
    assert.doesNotMatch(spoken().map(call => call.value.text).join(''), /断线期间内容|重连后旧轮次/)

    event('start', 'new-speaking')
    text('new-speaking', '被插话的播报。')
    await settle()
    activeRoom.handlers.DataReceived(new TextEncoder().encode(JSON.stringify({ state: 'speaking', revision: 5 })),
      activeRoom.remoteParticipants.get('voice-agent-test'), 0, 'gmd.voice.user')
    text('new-speaking', '被插话的播报。用户开口后旧内容。', { delta: '用户开口后旧内容。' })
    await settle()
    assert.doesNotMatch(spoken().map(call => call.value.text).join(''), /用户开口后旧内容/)

    event('start', 'long-answer')
    text('long-answer', '长回答的一句话。'.repeat(400))
    event('finish', 'long-answer')
    await settle()
    const longId = spoken().at(-1).value.streamId
    const longCalls = spoken().filter(call => call.value.streamId === longId)
    assert.ok(longCalls.map(call => call.value.text).join('').length < 3000, '长回答播报有上限，避免队列无限积压')
    assert.match(longCalls.map(call => call.value.text).join(''), /其余内容请查看/)
    assert.equal(longCalls.at(-1).value.final, true)

    let releaseRpc
    globalThis.voiceFixture.rpc = input => JSON.parse(input.payload).cancel ? Promise.resolve('{"accepted":true}')
      : new Promise(resolve => { releaseRpc = resolve })
    event('start', 'slow-rpc')
    text('slow-rpc', '已发送的第一句。')
    text('slow-rpc', '已发送的第一句。还在队列中的第二句。', { delta: '还在队列中的第二句。' })
    session.value = '3'; await settle()
    releaseRpc('{"accepted":true}'); await settle()
    assert.doesNotMatch(spoken().map(call => call.value.text).join(''), /还在队列中的第二句/)
    delete globalThis.voiceFixture.rpc

    text('connecting-mid-run', '连接前正文。')
    await api.start()
    text('connecting-mid-run', '连接前正文。连接后的新句。', { delta: '连接后的新句。' })
    event('finish', 'connecting-mid-run')
    await settle()
    const midId = spoken().at(-1).value.streamId
    const midSpeech = spoken().filter(call => call.value.streamId === midId).map(call => call.value.text).join('')
    assert.match(midSpeech, /连接后的新句/)
    assert.doesNotMatch(midSpeech, /连接前正文/)
  } finally {
    app?.unmount(); await server.close(); globalThis.window = savedWindow
    globalThis.setTimeout = savedSetTimeout
    if (savedNavigator) Object.defineProperty(globalThis, 'navigator', savedNavigator); else delete globalThis.navigator
    delete globalThis.voiceFixture
  }
})
