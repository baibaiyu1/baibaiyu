<script setup lang="ts">
import { ref, computed, h } from 'vue'
import {
  UploadOutlined,
  DeleteOutlined,
  DownloadOutlined,
  ClearOutlined,
  ReloadOutlined
} from '@ant-design/icons-vue'
import { Modal } from 'ant-design-vue'
import type { WorkspaceCapacityVO } from '@/types'

/**
 * 工作空间工具栏组件
 */
const props = defineProps<{
  /** 是否处于多选模式 */
  multiSelect: boolean
  /** 已选中的文件数量 */
  selectedCount: number
  /** 工作空间是否有文件 */
  hasFiles: boolean
  /** 是否正在上传 */
  uploading: boolean
  /** 工作空间容量信息 */
  capacity: WorkspaceCapacityVO | null
  /** Whether this workspace may be changed by the current user. */
  canWrite?: boolean
}>()

const emit = defineEmits<{
  (e: 'toggle-multi-select'): void
  (e: 'upload', files: File[]): void
  (e: 'delete'): void
  (e: 'download'): void
  (e: 'clear'): void
  (e: 'refresh'): void
}>()

/** 隐藏的文件输入元素 */
const fileInputRef = ref<HTMLInputElement | null>(null)

/** 刷新按钮旋转状态 */
const refreshSpinning = ref(false)

// 环形进度条参数
const RING_SIZE = 20
const RING_STROKE = 2.5
const RING_R = (RING_SIZE - RING_STROKE) / 2
const RING_C = 2 * Math.PI * RING_R

/** 环形进度偏移量 */
const ringOffset = computed(() => {
  if (!props.capacity) return RING_C
  return RING_C * (1 - props.capacity.percent / 100)
})

/** 环形进度条颜色 */
const ringColor = computed(() => {
  if (!props.capacity) return '#0F74FF'
  if (props.capacity.percent >= 95) return '#F56C6C'
  if (props.capacity.percent >= 80) return '#E6A23C'
  return '#0F74FF'
})

/** 容量悬停提示文本 */
const capacityTooltip = computed(() => {
  if (!props.capacity) return ''
  return `已用：${props.capacity.percent.toFixed(1)}%`
})

/**
 * 触发刷新，添加旋转动画
 */
const handleRefresh = () => {
  refreshSpinning.value = true
  emit('refresh')
  setTimeout(() => { refreshSpinning.value = false }, 600)
}

/**
 * 触发文件选择对话框
 */
const triggerUpload = () => {
  fileInputRef.value?.click()
}

/**
 * 处理文件选择
 */
const handleFileChange = (e: Event) => {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  if (files.length) {
    emit('upload', files)
  }
  // 重置 input，允许重复选择同一文件
  input.value = ''
}

/**
 * 确认清空工作空间
 */
const handleClear = () => {
  Modal.confirm({
    title: '确认清空',
    content: '将删除工作空间内所有文件，此操作不可恢复，是否继续？',
    okText: '确认清空',
    okButtonProps: { danger: true },
    cancelText: '取消',
    onOk: () => emit('clear'),
  })
}
</script>

<template>
  <div class="workspace-toolbar">
    <!-- 隐藏的文件选择 input -->
    <input
      ref="fileInputRef"
      type="file"
      multiple
      style="display: none"
      @change="handleFileChange"
    />

    <!-- 左侧：多选 -->
    <div class="workspace-toolbar-left">
      {{ selectedCount ? '已选择（' + selectedCount + '）' : '未选择' }}
    </div>

    <!-- 右侧：容量环、刷新、上传、下载、删除、清空 -->
    <div class="workspace-toolbar-right">
      <!-- 容量环形进度 -->
      <ATooltip v-if="capacity" placement="bottom" :title="capacityTooltip">
        <span class="workspace-capacity-ring-wrap">
          <svg
            class="workspace-capacity-ring"
            :width="RING_SIZE"
            :height="RING_SIZE"
            :viewBox="`0 0 ${RING_SIZE} ${RING_SIZE}`"
          >
            <circle
              class="workspace-capacity-ring-track"
              :cx="RING_SIZE / 2"
              :cy="RING_SIZE / 2"
              :r="RING_R"
              :stroke-width="RING_STROKE"
            />
            <circle
              class="workspace-capacity-ring-fill"
              :cx="RING_SIZE / 2"
              :cy="RING_SIZE / 2"
              :r="RING_R"
              :stroke-width="RING_STROKE"
              :stroke-dasharray="RING_C"
              :stroke-dashoffset="ringOffset"
              :style="{ stroke: ringColor }"
            />
          </svg>
        </span>
      </ATooltip>
      <!-- 刷新 -->
      <ATooltip placement="bottom" title="刷新">
        <AButton
          type="text"
          :class="{ 'is-spinning': refreshSpinning }"
          :icon="h(ReloadOutlined)"
          @click="handleRefresh"
        ></AButton>
      </ATooltip>
      <!-- 上传 -->
      <ATooltip v-if="canWrite !== false" placement="bottom" title="上传（支持压缩包）">
        <AButton
          type="text"
          :disabled="uploading"
          :icon="h(UploadOutlined)"
          @click="triggerUpload"
        ></AButton>
      </ATooltip>
      <!-- 下载 -->
      <ATooltip placement="bottom" title="下载">
        <AButton
          type="text"
          :disabled="selectedCount === 0"
          :icon="h(DownloadOutlined)"
          @click="$emit('download')"
        ></AButton>
      </ATooltip>
      <!-- 删除 -->
      <ATooltip v-if="canWrite !== false" placement="bottom" title="删除">
        <AButton
          type="text"
          danger
          :disabled="selectedCount === 0"
          :icon="h(DeleteOutlined)"
          @click="$emit('delete')"
        ></AButton>
      </ATooltip>
      <!-- 清空 -->
      <ATooltip v-if="canWrite !== false" placement="bottom" title="清空">
        <AButton
          type="text"
          :disabled="!hasFiles"
          :icon="h(ClearOutlined)"
          @click="handleClear"
        ></AButton>
      </ATooltip>

    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/chat/workspace.scss' as *;
</style>
