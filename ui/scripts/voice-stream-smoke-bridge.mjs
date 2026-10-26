// Headless frontend for the real LiveKit/SSE smoke test in GMD-FAQ-VOICE.
// stdout is a JSON-lines transport; login credentials stay in the Python caller.
import { createInterface } from 'node:readline'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { createRenderer, ref, nextTick } from 'vue'

const output = value => process.stdout.write(JSON.stringify(value) + '\n')
const renderer = createRenderer({ insert() {}, remove() {}, createElement: () => ({}), createText: () => ({}),
  createComment: () => ({}), setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null, patchProp() {} })
let room, rpcSequence = 0
const requests = new Map()
const stubs = {
  'ant-design-vue': 'export const message = {error() {}}',
  '@/stores': 'export const useAccountStore = () => ({userInfo:{id:"smoke"}})',
  '@/api/agui/request': `export const getAgentRunURL = () => '/run'; export const getSSEHeaders = () => ({});
    export const getRESTHeaders = () => ({}); export const getReconnectURL = () => '/reconnect';
    export const getResumeURL = () => '/resume'; export const getPendingURL = () => '/pending';
    export const getStatusURL = () => '/status'; export const getStopURL = () => '/stop'; export const getActiveRunsURL = () => '/active';`,
}
class Room {
  constructor() {
    room = this
    this.handlers = {}
    this.remoteParticipants = new Map([['agent', { identity: 'agent', kind: 4, attributes: { 'lk.agent.state': 'listening' } }]])
    this.localParticipant = {
      setMicrophoneEnabled: async () => {},
      performRpc: input => new Promise((resolve, reject) => {
        const id = ++rpcSequence
        requests.set(id, { resolve, reject })
        output({ type: 'rpc', id, method: input.method, payload: input.payload })
      }),
    }
  }
  registerRpcMethod() {}
  on(name, handler) { this.handlers[name] = handler }
  async connect() {}
  async startAudio() {}
  async disconnect() {}
}
globalThis.voiceSmoke = { Room }
globalThis.window = { isSecureContext: true }
Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { mediaDevices: { getUserMedia() {} } } })
const server = await createServer({ root: fileURLToPath(new URL('..', import.meta.url)), configFile: false,
  logLevel: 'silent', server: { middlewareMode: true, ws: false }, optimizeDeps: { noDiscovery: true },
  ssr: { noExternal: ['fast-json-patch'] },
  resolve: { alias: [{ find: 'livekit-client', replacement: '\0livekit-smoke' }, { find: '@/api/agui/voice', replacement: '\0voice-smoke' },
    ...Object.keys(stubs).map(id => ({find:id,replacement:'\0stub-smoke:' + id})),
    { find: '@', replacement: fileURLToPath(new URL('../src', import.meta.url)) }] },
  plugins: [{ name: 'voice-smoke', enforce: 'pre', resolveId: (id, importer) => id.startsWith('\0') ? id
    : (id === './request' && importer?.replaceAll('\\', '/').endsWith('/api/agui/index.ts'))
      || id.replaceAll('\\', '/').endsWith('/api/agui/request.ts') ? '\0stub-smoke:@/api/agui/request' : undefined,
    load: id => id.startsWith('\0stub-smoke:') ? stubs[id.slice('\0stub-smoke:'.length)] : id === '\0livekit-smoke'
    ? `export const Room = globalThis.voiceSmoke.Room; export const ParticipantKind = { AGENT: 4 };
        export const Track = { Kind: { Audio: 'audio' } };
        export const RoomEvent = Object.fromEntries(['ParticipantConnected','ParticipantAttributesChanged','TrackSubscribed','TrackUnsubscribed','AudioPlaybackStatusChanged','DataReceived','Reconnecting','Reconnected','ParticipantDisconnected','Disconnected'].map(x => [x,x]));`
    : id === '\0voice-smoke' ? `export const getVoiceAvailability = async () => ({available:true});
        export const connectVoice = async () => ({serverUrl:'fixture',participantToken:'fixture'});
        export const voiceTurn = async () => {throw new Error('Unexpected summary request')};` : undefined }] })
const { useVoiceConversation } = await server.ssrLoadModule('/src/composables/chat/useVoiceConversation.ts')
const { useChatStream } = await server.ssrLoadModule('/src/composables/chat/useChatStream.ts')
const sid = ref(process.argv[2])
let voice, chat, sseController, sseTask
const app = renderer.createApp({ setup() {
  voice = useVoiceConversation(sid, ref(true), { ensureSession: async () => sid.value,
    submitTask: async () => '', submitInstruction: async () => '' })
  chat = useChatStream(ref('smoke'), ref({agentCode:'smoke'}), sid,
    undefined, undefined, undefined, undefined, undefined, undefined, undefined, event => voice.businessEvent(event))
  return () => null
} })
app.mount({})
await nextTick()
await voice.start()
output({ type: 'ready' })
const lines = createInterface({ input: process.stdin })
for await (const line of lines) {
  const value = JSON.parse(line)
  if (value.type === 'rpcResult') {
    const request = requests.get(value.id)
    requests.delete(value.id)
    if (value.error) request?.reject(new Error(value.error))
    else request?.resolve(value.result)
  } else if (value.type === 'event') {
    voice.businessEvent(value.event)
  } else if (value.type === 'beginSse') {
    const body = new ReadableStream({start(controller) {sseController = controller}})
    globalThis.fetch = async () => new Response(body, {headers:{'Content-Type':'text/event-stream'}})
    sseTask = chat.reconnect(sid.value, true)
  } else if (value.type === 'sse') {
    sseController.enqueue(new TextEncoder().encode(value.data + '\n\n'))
  } else if (value.type === 'endSse') {
    sseController.close()
    await sseTask
  } else if (value.type === 'user') {
    room.handlers.DataReceived(new TextEncoder().encode(JSON.stringify(value.event)), room.remoteParticipants.get('agent'), 0, 'gmd.voice.user')
  } else if (value.type === 'interrupt') {
    // Do not block reading rpcResult while this operation awaits its RPC.
    void voice.interrupt().then(() => output({ type: 'interrupted' }))
  } else if (value.type === 'close') {
    app.unmount()
    await server.close()
    break
  }
}
