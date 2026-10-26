<!-- eslint-disable vue-multi-word-component-names -->
<script setup lang="ts">
/**
 * 技能「AI 对话创建」导入弹窗
 *
 * 流程：在对话中让智能体把技能文件（SKILL.md + 脚本）写入会话工作空间 →
 * 此处选择该会话，服务端按会话工作空间目录走既有本地导入通道（共享 .baibaiyu 数据卷，无需上传文件）。
 *
 * @component
 */
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { FolderOpenOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import * as skillApi from '@/api/skill'
import * as chatSessionApi from '@/api/chatSession'
import * as workspaceApi from '@/api/workspace'
import type { ChatSessionVO, SkillImportResult, WorkspaceFileNode } from '@/types'
import dayjs from 'dayjs'

const props = withDefaults(defineProps<{
  visible: boolean
  /** 已有分类，供快速选择 */
  categories?: string[]
  /** 默认分类（页面当前筛选的分类，可能为空） */
  defaultCategory?: string | null
}>(), {
  categories: () => [],
  defaultCategory: ''
})

const emit = defineEmits<{
  'update:visible': [value: boolean]
  success: []
}>()

const sessions = ref<ChatSessionVO[]>([])
const sessionLoading = ref(false)
const selectedSessionId = ref<string | undefined>(undefined)
const files = ref<WorkspaceFileNode[]>([])
const fileLoading = ref(false)
const category = ref('')
const cover = ref(false)
const importing = ref(false)
const result = ref<SkillImportResult | null>(null)

/** 工作空间文件数量（递归统计） */
const fileCount = computed(() => {
  const count = (nodes: WorkspaceFileNode[]): number =>
    nodes.reduce((total, node) => total + (node.directory ? count(node.children || []) : 1), 0)
  return count(files.value)
})

/** 弹窗打开时重置并加载最近会话 */
watch(() => props.visible, (visible) => {
  if (!visible) return
  selectedSessionId.value = undefined
  files.value = []
  result.value = null
  cover.value = false
  category.value = props.defaultCategory || 'AI 生成'
  void loadSessions()
})

async function loadSessions() {
  sessionLoading.value = true
  try {
    const res = await chatSessionApi.pageSessions({ page: 1, size: 50 })
    sessions.value = res.data.data?.records || []
  } catch (e) {
    console.error('[技能导入] 加载会话列表失败', e)
  } finally {
    sessionLoading.value = false
  }
}

/** 会话下拉选项 */
const sessionOptions = computed(() =>
  sessions.value.map((item) => ({
    value: String(item.id),
    label: `${item.title || '未命名会话'}（${dayjs(item.updatedAt).format('MM-DD HH:mm')}）`
  }))
)

/**
 * 选中会话后预览其工作空间文件
 */
async function handleSessionChange(value: string | undefined) {
  files.value = []
  result.value = null
  if (!value) return
  fileLoading.value = true
  try {
    const res = await workspaceApi.listFiles(value)
    files.value = res.data.data || []
  } catch (e) {
    console.warn('[技能导入] 读取会话工作空间失败', e)
    files.value = []
  } finally {
    fileLoading.value = false
  }
}

/**
 * 执行导入
 */
async function handleImport() {
  if (!selectedSessionId.value) {
    message.warning('请选择要导入的会话')
    return
  }
  if (!fileCount.value) {
    message.warning('该会话工作空间没有文件，请先在对话中生成技能文件')
    return
  }
  importing.value = true
  try {
    const res = await skillApi.importFromWorkspace(
      selectedSessionId.value,
      category.value.trim() || 'AI 生成',
      cover.value
    )
    result.value = res.data.data
    if (result.value?.importedCount) {
      message.success(`成功导入 ${result.value.importedCount} 个技能包`)
    } else {
      message.warning('未导入任何技能包，请检查工作空间中的技能目录结构')
    }
    emit('success')
  } catch (e) {
    console.error('[技能导入] 导入失败', e)
  } finally {
    importing.value = false
  }
}

function close() {
  emit('update:visible', false)
}
</script>

<template>
  <AModal
    :open="visible"
    title="AI 对话创建技能包"
    width="680px"
    :footer="null"
    @cancel="close"
  >
    <div class="ai-skill-tip">
      在对话中让智能体把技能文件写入会话工作空间（目录结构：<code>技能名/SKILL.md</code> + 脚本），
      再在此按会话导入。导入复用与「装载本地技能包」相同的入库路径，工作空间文件会保留以便核对。
    </div>

    <div class="ai-skill-form">
      <label class="ai-skill-field">
        <span>选择会话</span>
        <ASelect
          v-model:value="selectedSessionId"
          :options="sessionOptions"
          :loading="sessionLoading"
          placeholder="选择产出技能文件的会话"
          allow-clear
          show-search
          option-filter-prop="label"
          style="flex: 1"
          @change="handleSessionChange"
        />
      </label>

      <label class="ai-skill-field">
        <span>技能分类</span>
        <AInput v-model:value="category" placeholder="默认 AI 生成" style="flex: 1" />
      </label>

      <div v-if="categories.length" class="ai-skill-categories">
        <span>已有分类：</span>
        <ATag
          v-for="item in categories"
          :key="item"
          style="cursor: pointer"
          @click="category = item"
        >{{ item }}</ATag>
      </div>

      <label class="ai-skill-field">
        <span>同名覆盖</span>
        <ASwitch v-model:checked="cover" />
        <span class="ai-skill-hint">开启后同名技能包会被本次导入覆盖</span>
      </label>
    </div>

    <div class="ai-skill-preview">
      <div class="ai-skill-preview-head">
        <span><FolderOpenOutlined /> 工作空间文件</span>
        <span v-if="selectedSessionId" class="ai-skill-preview-count">共 {{ fileCount }} 个文件</span>
      </div>
      <ASpin :spinning="fileLoading">
        <div v-if="!selectedSessionId" class="ai-skill-empty">选择会话后展示其工作空间文件</div>
        <div v-else-if="!fileCount" class="ai-skill-empty">
          该会话工作空间为空，请先在对话中生成技能文件
        </div>
        <div v-else class="ai-skill-file-list">
          <span v-for="node in files" :key="node.path" class="ai-skill-file-item">
            {{ node.directory ? '📁' : '📄' }} {{ node.name }}
          </span>
        </div>
      </ASpin>
    </div>

    <div v-if="result" class="ai-skill-result">
      导入完成：新增 {{ result.importedCount }} 个 · 跳过 {{ result.skippedCount }} 个 · 共识别
      {{ result.totalCount }} 个
      <div v-if="result.hintMessage" class="ai-skill-result-hint">{{ result.hintMessage }}</div>
    </div>

    <div class="ai-skill-actions">
      <AButton @click="loadSessions">
        <template #icon><ReloadOutlined /></template>
        刷新会话
      </AButton>
      <div>
        <AButton @click="close">关闭</AButton>
        <AButton
          type="primary"
          style="margin-left: 8px"
          :loading="importing"
          :disabled="!selectedSessionId"
          @click="handleImport"
        >导入为技能包</AButton>
      </div>
    </div>
  </AModal>
</template>

<style scoped lang="scss">
.ai-skill-tip {
  padding: 10px 12px;
  color: #5b6a86;
  border: 1px solid #e4ebf7;
  border-radius: 8px;
  background: #f7faff;
  font-size: 12px;
  line-height: 1.7;

  code {
    padding: 1px 4px;
    border-radius: 3px;
    background: #eef2f9;
  }
}

.ai-skill-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin: 16px 0;
}

.ai-skill-field {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;

  > span:first-child {
    flex: 0 0 64px;
    color: #4b5563;
  }
}

.ai-skill-hint {
  color: #98a4b8;
  font-size: 12px;
}

.ai-skill-categories {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding-left: 74px;
  color: #8c98a8;
  font-size: 12px;
}

.ai-skill-preview {
  padding: 12px;
  border: 1px solid #eef1f6;
  border-radius: 8px;
  background: #fafcff;
}

.ai-skill-preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  color: #2b3445;
  font-size: 13px;
  font-weight: 600;
}

.ai-skill-preview-count {
  color: #8c98a8;
  font-size: 12px;
  font-weight: 400;
}

.ai-skill-empty {
  padding: 18px 0;
  color: #98a4b8;
  font-size: 12px;
  text-align: center;
}

.ai-skill-file-list {
  display: flex;
  max-height: 140px;
  flex-wrap: wrap;
  gap: 6px;
  overflow-y: auto;
}

.ai-skill-file-item {
  padding: 3px 8px;
  color: #4b5563;
  border-radius: 6px;
  background: #fff;
  font-size: 12px;
}

.ai-skill-result {
  margin-top: 12px;
  padding: 10px 12px;
  color: #1f9254;
  border: 1px solid #d7f0e0;
  border-radius: 8px;
  background: #f1fbf5;
  font-size: 12px;
}

.ai-skill-result-hint {
  margin-top: 4px;
  color: #6b7a90;
}

.ai-skill-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 16px;
}
</style>
