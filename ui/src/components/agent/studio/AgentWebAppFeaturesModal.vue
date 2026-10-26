<!-- eslint-disable vue-multi-word-component-names -->
<script setup lang="ts">
/**
 * 智能体编排工作台 —— 「功能增强 · Web App 用户体验」弹窗
 *
 * 对齐参照页 `ConversationSettingsModal`：配置用户进入对话时看到的开场白与推荐问题，
 * 推荐问题支持拖拽排序、单条删除、最多 6 条；保存走
 * `PUT /agent/definition/{id}/ui-config`（局部更新，只写对话端展示字段）。
 *
 * @component
 */
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { DeleteOutlined, HolderOutlined, PlusOutlined } from '@ant-design/icons-vue'
import Sortable from 'sortablejs'
import { nextTick } from 'vue'
import * as agentApi from '@/api/agent'

/** 推荐问题最大条数（与后端 MAX_OPENING_QUESTIONS 一致） */
const MAX_QUESTIONS = 6

const props = defineProps<{
  /** 是否打开 */
  open: boolean
  /** 智能体 ID */
  agentId: string
  /** 当前开场白（props 名与本地草稿、antd 的 message 都区分开，避免同名冲突） */
  openingText?: string
  /** 当前推荐问题 */
  questions?: string[]
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  /** 保存成功（父级刷新详情） */
  saved: []
}>()

const openingMessage = ref('')
const questionDrafts = ref<string[]>([])
const saving = ref(false)
const listRef = ref<HTMLElement | null>(null)
let sortable: ReturnType<typeof Sortable.create> | null = null

// 每次打开时用当前配置重置草稿
watch(() => props.open, (open) => {
  if (!open) return
  openingMessage.value = props.openingText || ''
  questionDrafts.value = [...(props.questions || [])].slice(0, MAX_QUESTIONS)
  void nextTick(initSortable)
})

/** 推荐问题拖拽排序 */
function initSortable() {
  destroySortable()
  if (!listRef.value) return
  sortable = Sortable.create(listRef.value, {
    animation: 150,
    handle: '.agstudio-webapp__drag',
    onEnd: (evt: { oldIndex?: number; newIndex?: number }) => {
      const { oldIndex, newIndex } = evt
      if (oldIndex == null || newIndex == null || oldIndex === newIndex) return
      const next = [...questionDrafts.value]
      const [moved] = next.splice(oldIndex, 1)
      if (moved === undefined) return
      next.splice(newIndex, 0, moved)
      questionDrafts.value = next
    }
  })
}

function destroySortable() {
  sortable?.destroy()
  sortable = null
}

/** 添加一条推荐问题 */
function addQuestion() {
  if (questionDrafts.value.length >= MAX_QUESTIONS) {
    message.warning(`最多添加${MAX_QUESTIONS}条推荐问题`)
    return
  }
  questionDrafts.value.push('')
  void nextTick(initSortable)
}

/** 删除一条推荐问题 */
function removeQuestion(index: number) {
  questionDrafts.value.splice(index, 1)
}

/** 保存（空字符串条目直接丢弃） */
async function submit() {
  if (!props.agentId) return
  saving.value = true
  try {
    await agentApi.saveUiConfig(props.agentId, {
      openingMessage: openingMessage.value,
      openingQuestions: questionDrafts.value.map(item => item.trim()).filter(Boolean)
    })
    message.success('已保存')
    emit('saved')
    emit('update:open', false)
  } catch (e) {
    console.error('保存 Web App 配置失败:', e)
  } finally {
    saving.value = false
  }
}

function close() {
  emit('update:open', false)
}

/** 是否有内容（用于保存按钮禁用提示） */
const canSave = computed(() => !!props.agentId)
</script>

<template>
  <a-modal
    :open="open"
    :width="560"
    :footer="null"
    :mask-closable="false"
    :destroy-on-close="true"
    title="功能增强 · Web App 用户体验"
    @cancel="close"
  >
    <p class="agstudio-webapp__desc">
      配置用户进入对话时看到的欢迎文案和推荐问题，提升首次交互体验。
    </p>

    <div class="agstudio-webapp__field">
      <span class="agstudio-webapp__label">开场白</span>
      <a-textarea
        v-model:value="openingMessage"
        :rows="2"
        :maxlength="200"
        show-count
        placeholder="例如：你好，我是你的智能助手"
      />
    </div>

    <div class="agstudio-webapp__field">
      <span class="agstudio-webapp__label">推荐问题</span>
      <div ref="listRef" class="agstudio-webapp__list">
        <div v-for="(question, index) in questionDrafts" :key="index" class="agstudio-webapp__item">
          <span class="agstudio-webapp__drag" title="拖拽排序"><HolderOutlined /></span>
          <span class="agstudio-webapp__index">{{ index + 1 }}</span>
          <a-textarea
            v-model:value="questionDrafts[index]"
            :rows="1"
            auto-size
            :maxlength="100"
            placeholder="请输入推荐问题，例如：帮我总结今天的重点"
          />
          <a-button type="text" danger size="small" @click="removeQuestion(index)">
            <template #icon><DeleteOutlined /></template>
          </a-button>
        </div>
      </div>

      <a-button type="dashed" block :disabled="questionDrafts.length >= MAX_QUESTIONS" @click="addQuestion">
        <template #icon><PlusOutlined /></template>
        添加推荐问题
      </a-button>
      <div class="agstudio-webapp__hint">
        最多配置 {{ MAX_QUESTIONS }} 条推荐问题，顺序即对话页展示顺序。
      </div>
    </div>

    <div class="agstudio-webapp__footer">
      <a-button @click="close">取消</a-button>
      <a-button type="primary" :loading="saving" :disabled="!canSave" @click="submit">确认</a-button>
    </div>
  </a-modal>
</template>

<style scoped lang="scss">
@use '@/styles/agent/studio-webapp.scss' as *;
</style>
