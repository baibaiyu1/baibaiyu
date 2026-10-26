<script setup lang="ts">
import { AudioOutlined, AudioMutedOutlined, CloseOutlined, SoundOutlined } from '@ant-design/icons-vue'
defineProps<{
  available: boolean
  active: boolean
  connecting: boolean
  muted: boolean
  state: string
  error: string
  userCaption: string
  assistantCaption: string
  needsAudioStart: boolean
}>()
defineEmits<{ start: []; disconnect: []; mute: []; interrupt: []; startAudio: [] }>()
const labels: Record<string, string> = { initializing: '正在准备', listening: '正在聆听', thinking: '正在思考', speaking: '正在说话' }
</script>

<template>
  <section v-if="available || active || error" class="voice-conversation" aria-label="语音对话">
    <div class="voice-controls">
      <AButton v-if="!active" size="small" @click="$emit('start')"><AudioOutlined />语音对话</AButton>
      <template v-else>
        <span class="voice-status" role="status">{{ labels[state] || state }}</span>
        <AButton size="small" :disabled="connecting" @click="$emit('mute')">
          <AudioMutedOutlined v-if="muted" /><AudioOutlined v-else />{{ muted ? '打开麦克风' : '静音' }}
        </AButton>
        <AButton size="small" :disabled="connecting" @click="$emit('interrupt')">停止播报</AButton>
        <AButton size="small" @click="$emit('disconnect')"><CloseOutlined />结束语音</AButton>
        <AButton v-if="needsAudioStart" size="small" @click="$emit('startAudio')"><SoundOutlined />开启声音</AButton>
      </template>
      <span v-if="active" class="voice-hint">随时开口插话 · 正式资料显示在聊天中</span>
    </div>
    <div v-if="active && userCaption" class="voice-caption"><span>你：</span>{{ userCaption }}</div>
    <div v-if="active && assistantCaption" class="voice-caption"><span>语音助手：</span>{{ assistantCaption }}</div>
    <div v-if="error" class="voice-error" role="alert">{{ error }}</div>
  </section>
</template>

<style scoped>
.voice-conversation { margin: 0 20px 8px; padding: 10px 12px; border: 1px solid var(--border-color, #e5e7eb); border-radius: 10px; }
.voice-controls { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.voice-status { font-size: 13px; color: var(--primary-color, #1677ff); }
.voice-hint, .voice-caption { font-size: 12px; opacity: .75; }
.voice-caption { margin-top: 6px; max-height: 42px; overflow: auto; overflow-wrap: anywhere; }
.voice-error { color: #d4380d; margin-top: 6px; font-size: 12px; }
@media (max-width: 600px) { .voice-conversation { margin-inline: 8px; } .voice-hint { width: 100%; } }
</style>
