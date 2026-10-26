<template>
  <svg
    xmlns="http://www.w3.org/2000/svg"
    :width="size"
    :height="size"
    :style="{ width: size + 'px', height: size + 'px', minWidth: size + 'px', flexShrink: 0 }"
    viewBox="0 0 24 24"
    :fill="iconColor"
  >
    <!-- 图片类型 -->
    <path
      v-if="isImage"
      d="M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z"
    />

    <!-- 音频类型 -->
    <path
      v-else-if="isAudio"
      d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6zm-2 16c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z"
    />

    <!-- 视频类型 -->
    <path
      v-else-if="isVideo"
      d="M17 10.5V7c0-.55-.45-1-1-1H4c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h12c.55 0 1-.45 1-1v-3.5l4 4v-11l-4 4z"
    />

    <!-- 代码类型 -->
    <template v-else-if="isCode">
      <!-- 文件主体 -->
      <path
        d="M6 2H14L19 7V20C19 21.1 18.1 22 17 22H6C4.9 22 4 21.1 4 20V4C4 2.9 4.9 2 6 2Z"
        stroke="#D1D5DB"
        stroke-width="0.8"
        fill="#F0FDF4"/>
      <!-- 折角 -->
      <path
        d="M14 2V7H19"
        fill="#DCFCE7"/>
      <!-- 类型块 -->
      <rect
        x="2"
        y="6"
        width="9"
        height="12"
        rx="1.5"
        fill="#16A34A"/>
      <!-- < -->
      <path
        d="M5 9.5L3.8 12L5 14.5"
        stroke="white"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"/>
      <!-- > -->
      <path
        d="M7.5 9.5L8.8 12L7.5 14.5"
        stroke="white"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"/>
      <!-- 代码行 -->
      <rect
        x="12"
        y="10"
        width="5"
        height="1.2"
        rx="0.6"
        fill="#16A34A"/>
      <rect
        x="12"
        y="13"
        width="3.5"
        height="1.2"
        rx="0.6"
        fill="#22C55E"/>
      <rect
        x="12"
        y="16"
        width="4"
        height="1.2"
        rx="0.6"
        fill="#16A34A"/>
    </template>

    <!-- 配置类型 -->
    <template v-else-if="isConfig">
      <!-- 文件主体 -->
      <path
        d="M6 2H14L19 7V20C19 21.1 18.1 22 17 22H6C4.9 22 4 21.1 4 20V4C4 2.9 4.9 2 6 2Z"
        stroke="#D1D5DB"
        stroke-width="0.8"
        fill="#F0FDFA"/>
      <!-- 折角 -->
      <path
        d="M14 2V7H19"
        fill="#CCFBF1"/>
      <!-- 类型块 -->
      <rect
        x="2"
        y="6"
        width="9"
        height="12"
        rx="1.5"
        fill="#0F766E"/>
      <!-- Gear -->
      <g transform="translate(2.6,8.5) scale(0.33)">
        <path
          fill="white"
          fill-rule="evenodd"
          d="M19.14,12.94c0.04-0.3,0.06-0.61,0.06-0.94c0-0.32-0.02-0.64-0.07-0.94l2.03-1.58c0.18-0.14,0.23-0.41,0.12-0.61l-1.92-3.32c-0.12-0.22-0.37-0.29-0.59-0.22l-2.39,0.96c-0.5-0.38-1.03-0.7-1.62-0.94L14.4,2.81c-0.04-0.24-0.24-0.41-0.48-0.41h-3.84c-0.24,0-0.43,0.17-0.47,0.41L9.25,5.35C8.66,5.59,8.12,5.92,7.63,6.29L5.24,5.33c-0.22-0.08-0.47,0-0.59,0.22L2.74,8.87C2.62,9.08,2.66,9.34,2.86,9.48l2.03,1.58C4.84,11.36,4.8,11.69,4.8,12s0.02,0.64,0.07,0.94l-2.03,1.58c-0.18,0.14-0.23,0.41-0.12,0.61l1.92,3.32c0.12,0.22,0.37,0.29,0.59,0.22l2.39-0.96c0.5,0.38,1.03,0.7,1.62,0.94l0.36,2.54c0.05,0.24,0.24,0.41,0.48,0.41h3.84c0.24,0,0.44-0.17,0.47-0.41l0.36-2.54c0.59-0.24,1.13-0.56,1.62-0.94l2.39,0.96c0.22,0.08,0.47,0,0.59-0.22l1.92-3.32c0.12-0.22,0.07-0.47-0.12-0.61L19.14,12.94zM12,15.6c-1.98,0-3.6-1.62-3.6-3.6s1.62-3.6,3.6-3.6s3.6,1.62,3.6,3.6S13.98,15.6,12,15.6z"
        />
      </g>
      <!-- 配置项 -->
      <rect
        x="12"
        y="10"
        width="5"
        height="1.2"
        rx="0.6"
        fill="#0F766E"/>
      <rect
        x="12"
        y="13"
        width="3.5"
        height="1.2"
        rx="0.6"
        fill="#14B8A6"/>
      <rect
        x="12"
        y="16"
        width="4.5"
        height="1.2"
        rx="0.6"
        fill="#0F766E"/>
    </template>

    <!-- Word 文档（填充） -->
    <template v-else-if="isWord">
      <!-- 文件 -->
      <path
        d="M6 2H14L19 7V20C19 21.1 18.1 22 17 22H6C4.9 22 4 21.1 4 20V4C4 2.9 4.9 2 6 2Z"
        stroke="#D1D5DB"
        stroke-width="0.8"
        fill="#EAF2FF"/>
      <!-- 折角 -->
      <path
          d="M14 2V7H19"
          fill="#D6E4FF"/>
      <!-- Word标识 -->
      <rect
          x="2"
          y="6"
          width="9"
          height="12"
          rx="1.5"
          fill="#185ABD"/>
      <path
          d="M4 9L4.5 15L6.1 11.5L7.8 15L8.5 9"
          stroke="white"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"/>
      <!-- 文档线 -->
      <rect x="12" y="10" width="5" height="1.2" rx="0.6" fill="#185ABD"/>
      <rect x="12" y="13" width="5" height="1.2" rx="0.6" fill="#185ABD"/>
      <rect x="12" y="16" width="4" height="1.2" rx="0.6" fill="#185ABD"/>
    </template>

    <!-- Excel 表格（填充） -->
    <template v-else-if="isExcel">
      <!-- 文件 -->
      <path
        d="M6 2H14L19 7V20C19 21.1 18.1 22 17 22H6C4.9 22 4 21.1 4 20V4C4 2.9 4.9 2 6 2Z"
        stroke="#D1D5DB"
        stroke-width="0.8"
        fill="#EAF8F1"/>
      <!-- 折角 -->
      <path
          d="M14 2V7H19"
          fill="#CFF0E1"/>
      <!-- Excel标识 -->
      <rect
          x="2"
          y="6"
          width="8"
          height="12"
          rx="1.5"
          fill="#107C41"/>
      <path
          d="M4 10L8 14.5M8 10L4 14.5"
          stroke="white"
          stroke-width="1.6"
          stroke-linecap="round"/>
      <!-- 表格 -->
      <rect x="11" y="9" width="6" height="7" rx="0.8" fill="#107C41" opacity="0.15"/>
      <path
          d="M13 9V16M15 9V16M11 11.3H17M11 13.7H17"
          stroke="#107C41"
          stroke-width="1"/>
    </template>

    <!-- PPT 演示（填充） -->
    <template v-else-if="isPpt">
      <!-- 文件 -->
      <path
        d="M6 2H14L19 7V20C19 21.1 18.1 22 17 22H6C4.9 22 4 21.1 4 20V4C4 2.9 4.9 2 6 2Z"
        stroke="#D1D5DB"
        stroke-width="0.8"
        fill="#FFF2EA"/>
      <!-- 折角 -->
      <path
          d="M14 2V7H19"
          fill="#FFE1D1"/>
      <!-- PPT标识 -->
      <rect
          x="2"
          y="6"
          width="9"
          height="12"
          rx="1.5"
          fill="#D24726"/>
      <!-- P -->
      <path
          d="M5 15V9H6.8C8.1 9 8.8 9.7 8.8 10.8C8.8 12 8.1 12.7 6.8 12.7H5"
          stroke="white"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"/>
      <!-- 饼图 -->
      <circle
          cx="15"
          cy="13"
          r="3"
          fill="#D24726"
          opacity="0.2"/>
      <path
        d="M14.5 13V10 A3 3 0 0 1 17.5 14Z"
        fill="#D24726"/>
    </template>

    <!-- PDF 文档（填充） -->
    <template v-else-if="isPdf">
      <!-- 文件 -->
      <path
        d="M6 2H14L19 7V20C19 21.1 18.1 22 17 22H6C4.9 22 4 21.1 4 20V4C4 2.9 4.9 2 6 2Z"
        stroke="#D1D5DB"
        stroke-width="0.8"
        fill="#FFF1F0"/>
      <!-- 折角 -->
      <path
          d="M14 2V7H19"
          fill="#FFD9D6"/>
      <!-- PDF标识 -->
      <rect
          x="2"
          y="6"
          width="9"
          height="12"
          rx="1.5"
          fill="#CE1E29"/>
      <!-- P -->
      <path
          d="M5 15V9H6.8C8.1 9 8.8 9.7 8.8 10.8C8.8 12 8.1 12.7 6.8 12.7H5"
          stroke="white"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"/>
      <!-- PDF内容 -->
      <rect
          x="12"
          y="10"
          width="5"
          height="1.2"
          rx="0.6"
          fill="#CE1E29"/>
      <rect
          x="12"
          y="13"
          width="5"
          height="1.2"
          rx="0.6"
          fill="#CE1E29"/>
      <rect
          x="12"
          y="16"
          width="4"
          height="1.2"
          rx="0.6"
          fill="#CE1E29"/>
    </template>

    <!-- Markdown（纯 MD 字母） -->
    <template v-else-if="isMd">
      <!-- 文件主体 -->
      <path
          d="M6 2H14L19 7V20C19 21.1 18.1 22 17 22H6C4.9 22 4 21.1 4 20V4C4 2.9 4.9 2 6 2Z"
          stroke="#D1D5DB"
          stroke-width="0.8"
          fill="#F8FAFC"/>
      <!-- 折角 -->
      <path
          d="M14 2V7H19"
          fill="#E2E8F0"/>
      <!-- 类型色块 -->
      <rect
          x="2"
          y="6"
          width="8"
          height="12"
          rx="1.5"
          fill="#111827"/>
      <!-- M -->
      <path
        d="M4.5 14V10"
        stroke="white"
        stroke-width="1.4"
        stroke-linecap="round"/>
      <path
        d="M4.5 10L6 12"
        stroke="white"
        stroke-width="1.4"
        stroke-linecap="round"
        stroke-linejoin="round"/>
      <path
        d="M6 12L7.5 10"
        stroke="white"
        stroke-width="1.4"
        stroke-linecap="round"
        stroke-linejoin="round"/>
      <path
        d="M7.5 10V14"
        stroke="white"
        stroke-width="1.4"
        stroke-linecap="round"/>
      <!-- 内容线 -->
      <rect
          x="11"
          y="10"
          width="5"
          height="1.2"
          rx="0.6"
          fill="#111827"/>
      <rect
          x="11"
          y="13"
          width="5"
          height="1.2"
          rx="0.6"
          fill="#111827"/>
      <rect
          x="11"
          y="16"
          width="4"
          height="1.2"
          rx="0.6"
          fill="#111827"/>
    </template>

    <!-- 其他类型 -->
    <template v-else>
      <!-- 文件主体 -->
      <path
        d="M6 2H14L19 7V20C19 21.1 18.1 22 17 22H6C4.9 22 4 21.1 4 20V4C4 2.9 4.9 2 6 2Z"
        stroke="#D1D5DB"
        stroke-width="0.8"
        fill="#F8FAFC"/>
      <!-- 折角 -->
      <path
        d="M14 2V7H19"
        fill="#E2E8F0"/>
      <!-- 类型块 -->
      <rect
        x="2"
        y="6"
        width="9"
        height="12"
        rx="1.5"
        fill="#64748B"/>
      <!-- 通用文件标识 -->
      <rect
        x="5.2"
        y="9.5"
        width="3"
        height="3"
        rx="0.6"
        fill="white"/>
      <rect
        x="5.2"
        y="13.5"
        width="3"
        height="1"
        rx="0.5"
        fill="white"/>
      <!-- 内容 -->
      <rect
        x="12"
        y="10"
        width="5"
        height="1.2"
        rx="0.6"
        fill="#64748B"/>
      <rect
        x="12"
        y="13"
        width="4"
        height="1.2"
        rx="0.6"
        fill="#64748B"/>
      <rect
        x="12"
        y="16"
        width="5"
        height="1.2"
        rx="0.6"
        fill="#64748B"/>
    </template>
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue'

// 接收类型 + 大小
const props = defineProps({
  type: {
    type: String,
    required: true,
    default: ''
  },
  size: {
    type: [String, Number],
    default: 24 // 默认图标大小
  }
})

// 统一转小写判断
const mediaType = computed(() => props.type.trim().toLowerCase())

// 图片类型
const isImage = computed(() =>
  ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'tiff', 'tif', 'svg', 'ico', 'heic', 'heif', 'jfif'].includes(mediaType.value)
)

// 音频类型
const isAudio = computed(() =>
  ['mp3', 'wav', 'ogg', 'flac', 'aac', 'm4a', 'wma', 'amr', 'ape', 'opus'].includes(mediaType.value)
)

// 视频类型
const isVideo = computed(() =>
  ['mp4', 'mpeg', 'mpg', 'mov', 'avi', 'wmv', 'flv', 'webm', 'mkv', 'm4v', '3gp', 'ts'].includes(mediaType.value)
)

// Word 文档
const isWord = computed(() =>
  ['doc', 'docx'].includes(mediaType.value)
)

// Excel 文档
const isExcel = computed(() =>
  ['xlsx', 'xls', 'csv'].includes(mediaType.value)
)

// PPT 文档
const isPpt = computed(() =>
  ['pptx', 'ppt'].includes(mediaType.value)
)

// PDF 文档
const isPdf = computed(() => mediaType.value === 'pdf')

// MD 文档
const isMd = computed(() => mediaType.value === 'md')

// 代码/脚本类型
const isCode = computed(() =>
  ['js', 'ts', 'py', 'sh', 'java', 'go', 'rs', 'sql','html','css'].includes(mediaType.value)
)

// 配置/数据类型
const isConfig = computed(() =>
  ['yaml', 'yml', 'json', 'toml', 'env', 'xml','conf'].includes(mediaType.value)
)

// 样式/前端类型
const isStyle = computed(() =>
  ['css', 'html'].includes(mediaType.value)
)

// 数据库类型
const isSql = computed(() => mediaType.value === 'sql')

// 图标颜色
const iconColor = computed(() => {
  if (isImage.value) return '#4285F4'
  if (isAudio.value) return '#34A853'
  if (isVideo.value) return '#EA4335'
  if (isWord.value) return '#2B579A'      // Word 蓝
  if (isExcel.value) return '#217346'      // Excel 绿
  if (isPpt.value) return '#D24726'        // PPT 橙红
  if (isPdf.value) return '#E53935'        // PDF 红
  if (isCode.value) return '#26A69A'       // 代码青绿
  if (isConfig.value) return '#5C6BC0'     // 配置靛蓝
  if (isStyle.value) return '#EF6C00'      // 样式橙
  if (isSql.value) return '#4479A1'        // 数据库蓝灰
  return '#999' // 未知类型
})
</script>
