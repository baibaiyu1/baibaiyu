import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { ParticipantKind, Room, RoomEvent, Track, type Participant, type RpcInvocationData } from 'livekit-client'
import { connectVoice, getVoiceAvailability, voiceTurn, type VoiceTurn, type VoiceReply } from '@/api/agui/voice'
import { VoiceTextBuffer, type ChatSpeechEvent } from '@/utils/chat/voice-text'
import { createClientId } from '@/utils/clientId'

interface Options {
  ensureSession: () => Promise<string | null>
  submitTask: (session: string, id: string, text: string) => Promise<string>
  submitInstruction: (session: string, id: string, text: string, runId: string) => Promise<string>
}
interface Pending { controller: AbortController; revision: number; cancelled: boolean; result: Promise<string> }
interface Speech {
  runId: string; id: string; revision: number; generation: number
  readers: Map<string, VoiceTextBuffer>; chunks: string[]; sequence: number; characters: number
  sending: boolean; ended: boolean; closed: boolean; cancelled: boolean
}

/** Media stays in LiveKit; authenticated business requests stay in the existing browser session. */
export function useVoiceConversation(sessionId: Ref<string | null>, enabled: Ref<boolean>, options: Options) {
  const available = ref(false), connecting = ref(false), connected = ref(false), muted = ref(false)
  const state = ref('未连接'), error = ref(''), userCaption = ref(''), assistantCaption = ref('')
  const needsAudioStart = ref(false)
  let room: Room | undefined, agentIdentity = '', generation = 0, revision = 0, sequence = 0
  let recovering = false
  let agentTimer: ReturnType<typeof setTimeout> | undefined
  let speech: Speech | undefined
  const pending = new Map<string, Pending>(), completed = new Map<string, string>()
  const audioElements = new Set<HTMLMediaElement>()
  const active = computed(() => connected.value || connecting.value)

  function cancelSpeech() {
    const current = speech
    if (!current || current.cancelled) return
    current.cancelled = true; current.chunks = []
    if (room && agentIdentity) {
      void room.localParticipant.performRpc({ destinationIdentity: agentIdentity, method: 'gmd.voice.stream',
        payload: JSON.stringify({ streamId: current.id, revision: current.revision, cancel: true }) }).catch(() => {})
    }
  }
  function cancelPending() {
    sequence++
    for (const item of pending.values()) { item.cancelled = true; item.controller.abort() }
    cancelSpeech()
  }
  async function disconnect() {
    generation++
    cancelPending()
    clearTimeout(agentTimer)
    const current = room
    room = undefined
    recovering = false
    agentIdentity = ''
    for (const audio of audioElements) { audio.pause(); audio.srcObject = null; audio.remove() }
    audioElements.clear()
    pending.clear(); completed.clear()
    connected.value = false; connecting.value = false; muted.value = false
    state.value = '未连接'; needsAudioStart.value = false
    if (current) await current.disconnect(true)
  }
  function remember(id: string, result: string) {
    completed.set(id, result)
    if (completed.size > 64) completed.delete(completed.keys().next().value!)
  }
  function isAgent(identity: string, current: Room) {
    const participant = current.remoteParticipants.get(identity)
    return participant?.kind === ParticipantKind.AGENT && (!agentIdentity || agentIdentity === identity)
  }
  async function start() {
    if (active.value || !enabled.value || !available.value) return
    if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
      error.value = '麦克风需要通过 localhost 或 HTTPS 访问页面'
      return
    }
    error.value = ''; userCaption.value = ''; assistantCaption.value = ''
    connecting.value = true; state.value = '正在连接'
    let current: Room | undefined
    const version = ++generation
    try {
      const sid = await options.ensureSession()
      if (version !== generation) return
      if (!sid || !enabled.value) { connecting.value = false; return }
      // Creating a new formal session may trigger the session watcher before a room exists.
      connecting.value = true
      const connection = await connectVoice(sid)
      if (version !== generation || sessionId.value !== sid) {
        if (version === generation) connecting.value = false
        return
      }
      current = new Room({ adaptiveStream: true, dynacast: true,
        audioCaptureDefaults: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } })
      const ownRoom = current
      room = current; revision = 0
      let microphoneReady = false
      const isCurrent = () => room === ownRoom && generation === version && sessionId.value === sid && enabled.value
      const setAgent = (participant: Participant) => {
        if (!isCurrent() || participant.kind !== ParticipantKind.AGENT) return
        agentIdentity = participant.identity
        const agentState = participant.attributes['lk.agent.state']
        state.value = agentState || '等待语音助手就绪'
        if (microphoneReady && ['listening', 'thinking', 'speaking'].includes(agentState || '')) {
          clearTimeout(agentTimer)
          connected.value = true; connecting.value = false
        }
      }
      ownRoom.registerRpcMethod('gmd.voice.turn', async (data: RpcInvocationData) => {
        if (!isCurrent() || !isAgent(data.callerIdentity, ownRoom)) throw new Error('语音来源无效')
        agentIdentity = data.callerIdentity
        const input = JSON.parse(data.payload) as VoiceTurn
        if (!/^[a-zA-Z0-9_-]{1,100}$/.test(input.requestId) || typeof input.text !== 'string'
          || !input.text.trim() || input.text.length > 3000) throw new Error('语音消息无效')
        const cached = completed.get(input.requestId)
        if (cached) return cached
        const previous = pending.get(input.requestId)
        if (previous) return previous.result
        cancelPending()
        const turnSequence = sequence, turnRevision = revision
        const controller = new AbortController()
        const item: Pending = { controller, revision: turnRevision, cancelled: false, result: Promise.resolve('') }
        const valid = () => isCurrent() && !item.cancelled && turnSequence === sequence && turnRevision === revision
        userCaption.value = input.text
        const work = async () => {
          let reply: VoiceReply
          try {
            reply = await voiceTurn(sid, input, controller.signal)
            if (!valid()) throw new DOMException('已被插话取消', 'AbortError')
            if (reply.action === 'TASK') {
              reply.speech = await options.submitTask(sid, `voice-${input.requestId}`, input.text)
            } else if (reply.action === 'INSTRUCTION' && reply.runId) {
              reply.speech = await options.submitInstruction(sid, `voice-${input.requestId}`, input.text, reply.runId)
            }
            const result = JSON.stringify({ requestId: input.requestId, speech: valid() ? reply.speech : '' })
            remember(input.requestId, result)
            if (valid()) assistantCaption.value = reply.speech
            return result
          } catch (failure) {
            if (valid()) error.value = failure instanceof Error ? failure.message : '语音请求失败，请重试'
            const result = JSON.stringify({ requestId: input.requestId,
              speech: valid() ? '这次请求没能确认接收，请查看界面提示。' : '' })
            remember(input.requestId, result)
            return result
          } finally {
            if (pending.get(input.requestId) === item) pending.delete(input.requestId)
            void pumpSpeech()
          }
        }
        pending.set(input.requestId, item)
        item.result = work()
        return item.result
      })
      ownRoom.on(RoomEvent.ParticipantConnected, setAgent)
      ownRoom.on(RoomEvent.ParticipantAttributesChanged, (_changes, participant) => {
        if (participant.identity === agentIdentity) setAgent(participant)
      })
      ownRoom.on(RoomEvent.TrackSubscribed, (track, _publication, participant) => {
        if (!isCurrent() || track.kind !== Track.Kind.Audio || participant.kind !== ParticipantKind.AGENT) return
        setAgent(participant)
        const audio = track.attach()
        audio.style.display = 'none'; document.body.appendChild(audio); audioElements.add(audio)
      })
      ownRoom.on(RoomEvent.TrackUnsubscribed, track => {
        for (const audio of track.detach()) { audioElements.delete(audio); audio.remove() }
      })
      ownRoom.on(RoomEvent.AudioPlaybackStatusChanged, () => { needsAudioStart.value = !ownRoom.canPlaybackAudio })
      ownRoom.on(RoomEvent.DataReceived, (bytes, participant, _kind, topic) => {
        if (!isCurrent() || !participant || !isAgent(participant.identity, ownRoom)) return
        try {
          const value = JSON.parse(new TextDecoder().decode(bytes))
          if (topic === 'gmd.voice.cancel') {
            const item = pending.get(value.requestId)
            if (item) { item.cancelled = true; item.controller.abort() }
          } else if (topic === 'gmd.voice.user' && Number.isInteger(value.revision)) {
            if (value.revision > revision) { revision = value.revision; cancelPending() }
            if (value.state === 'speaking') state.value = '你正在说话'
          }
        } catch { /* Ignore unrelated or malformed room data. */ }
      })
      ownRoom.on(RoomEvent.Reconnecting, () => { if (isCurrent()) { recovering = true; cancelPending(); state.value = '正在重连' } })
      ownRoom.on(RoomEvent.Reconnected, () => {
        if (isCurrent()) {
          // The cancellation RPC may have been lost while transport was down.
          void interrupt().finally(() => { if (isCurrent()) { recovering = false; state.value = '正在聆听' } })
        }
      })
      ownRoom.on(RoomEvent.ParticipantDisconnected, participant => {
        if (isCurrent() && participant.identity === agentIdentity) {
          error.value = '语音助手已断开，请重新连接'; void disconnect()
        }
      })
      ownRoom.on(RoomEvent.Disconnected, () => { if (room === ownRoom) void disconnect() })
      await ownRoom.connect(connection.serverUrl, connection.participantToken)
      if (!isCurrent()) { await ownRoom.disconnect(true); return }
      await ownRoom.startAudio()
      await ownRoom.localParticipant.setMicrophoneEnabled(true)
      if (!isCurrent()) { await ownRoom.disconnect(true); return }
      microphoneReady = true; state.value = '等待语音助手就绪'
      agentTimer = setTimeout(() => {
        if (isCurrent() && !connected.value) {
          error.value = '语音助手未就绪，请在语音项目运行 scripts/start.ps1 后重新连接'
          void disconnect()
        }
      }, 45000)
      for (const participant of ownRoom.remoteParticipants.values()) setAgent(participant)
    } catch (failure) {
      if (version !== generation) return
      error.value = failure instanceof Error ? failure.message : '语音连接失败'
      if (!current || room === current) await disconnect()
      else await current.disconnect(true)
    }
  }
  async function toggleMute() {
    if (!room) return
    try { await room.localParticipant.setMicrophoneEnabled(muted.value); muted.value = !muted.value }
    catch (failure) { error.value = failure instanceof Error ? failure.message : '麦克风操作失败' }
  }
  async function interrupt() {
    cancelPending()
    if (!room || !agentIdentity) return
    try { await room.localParticipant.performRpc({ destinationIdentity: agentIdentity, method: 'gmd.voice.interrupt', payload: '{}' }) }
    catch { error.value = '停止播报失败，请重新连接语音' }
  }
  async function startAudio() { try { await room?.startAudio() } catch { error.value = '请检查浏览器的声音播放权限' } }
  async function pumpSpeech() {
    const current = speech, ownRoom = room
    if (!current || current.sending || current.cancelled || current.closed || !ownRoom || !connected.value || recovering
      || !agentIdentity || pending.size || current.revision !== revision || current.generation !== generation) return
    current.sending = true
    try {
      while (speech === current && room === ownRoom && !current.cancelled && !current.closed) {
        let text = ''
        while (current.chunks.length && text.length + current.chunks[0]!.length + 1 <= 360) {
          text += current.chunks.shift()! + '\n'
        }
        const final = current.ended && !current.chunks.length
        if (!text && !final) break
        const result = await ownRoom.localParticipant.performRpc({ destinationIdentity: agentIdentity,
          method: 'gmd.voice.stream', responseTimeout: 5000,
          payload: JSON.stringify({ streamId: current.id, revision: current.revision,
            sequence: ++current.sequence, text, final }) })
        if (speech !== current || current.cancelled || room !== ownRoom || current.generation !== generation) return
        if (!JSON.parse(result).accepted) throw new Error('speech rejected')
        if (text) assistantCaption.value = text.trim().replace(/\n/g, '')
        if (final) current.closed = true
      }
    } catch {
      if (speech === current && !current.cancelled && room === ownRoom) {
        cancelSpeech()
        error.value = '正文播报中断，请查看聊天内容或重新连接语音'
      }
    } finally { current.sending = false }
  }
  function businessEvent(event: ChatSpeechEvent) {
    if (event.sessionId !== sessionId.value) return
    if (event.type === 'cancel') {
      if (speech?.runId === event.runId) cancelSpeech()
      return
    }
    if (speech?.runId !== event.runId || (speech.generation !== generation && connected.value)) {
      cancelSpeech()
      speech = { runId: event.runId, id: `speech-${createClientId()}`, revision, generation,
        readers: new Map(), chunks: [], sequence: 0, characters: 0,
        sending: false, ended: false, closed: false, cancelled: false }
    }
    const current = speech
    if (current.cancelled || current.ended || current.generation !== generation) return
    if (event.type === 'text') {
      let reader = current.readers.get(event.messageId)
      if (!reader) {
        reader = new VoiceTextBuffer()
        // Joining/recovering mid-message must not replay the prefix already on screen.
        reader.update(event.text.slice(0, event.text.length - event.delta.length), false, false)
        current.readers.set(event.messageId, reader)
      }
      const chunks = reader.update(event.text, event.final, !event.replay && connected.value && !recovering)
      for (const chunk of chunks) {
        if (current.characters + chunk.length > 2400) {
          current.chunks.push('其余内容请查看聊天中的完整回答。')
          current.ended = true
          break
        }
        current.characters += chunk.length
        current.chunks.push(chunk)
      }
    } else if (event.type === 'finish') {
      if (!event.replay && connected.value && !recovering) {
        for (const reader of current.readers.values()) {
          for (const chunk of reader.finish()) {
            if (current.characters + chunk.length > 2400) break
            current.characters += chunk.length
            current.chunks.push(chunk)
          }
        }
      }
      current.ended = true
      // An empty/replayed response has nothing to synthesize or summarize.
      if (!current.characters) current.closed = true
    }
    void pumpSpeech()
  }
  watch(enabled, async value => {
    if (!value) { available.value = false; await disconnect(); return }
    try { available.value = (await getVoiceAvailability()).available } catch { available.value = false }
  }, { immediate: true })
  watch(sessionId, () => { if (room) void disconnect() })
  onBeforeUnmount(() => { void disconnect() })
  return { available, connecting, connected, muted, active, state, error, userCaption, assistantCaption,
    needsAudioStart, start, disconnect, toggleMute, interrupt, startAudio, businessEvent }
}
