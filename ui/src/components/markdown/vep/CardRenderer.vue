<template>
  <div class="vep-card">
    <!-- 标题 -->
    <div v-if="item.title" class="vep-card-title">{{ item.title }}</div>
    <!-- 洞察 -->
    <div v-if="item.insight" class="vep-card-insight">{{ item.insight }}</div>
    <!-- 数据网格 -->
    <div class="vep-card-grid">
      <div
        v-for="(field, idx) in item.data"
        :key="idx"
        class="vep-card-field"
      >
        <span class="vep-card-label">{{ field.label }}</span>
        <template v-if="field.format === 'badge'">
          <span class="vep-card-badge" :class="[`vep-badge--${field.status || 'default'}`]">
            {{ field.value }}
          </span>
        </template>
        <template v-else>
          <span class="vep-card-value">{{ formatValue(field) }}</span>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { CardItem, CardField } from './types'

defineProps<{ item: CardItem }>()

/**
 * 根据 format 类型格式化字段值
 */
function formatValue(field: CardField): string {
  const val = field.value
  switch (field.format) {
    case 'currency': {
      const num = Number(val)
      const formatted = isNaN(num) ? String(val) : num.toLocaleString()
      return `${field.unit || ''}${formatted}`
    }
    case 'percent':
      return `${val}%`
    case 'number': {
      const num = Number(val)
      return isNaN(num) ? String(val) : num.toLocaleString()
    }
    case 'datetime':
    case 'text':
    default:
      return String(val)
  }
}
</script>

<style scoped>
.vep-card {
  margin: 10px 0;
  padding: 16px 20px;
  background: #fafbfc;
  border: 1px solid #e8ecf1;
  border-radius: 10px;
}

.vep-card-title {
  font-size: 15px;
  font-weight: 600;
  color: #1a1a2e;
  margin-bottom: 4px;
}

.vep-card-insight {
  font-size: 13px;
  color: #8c8c8c;
  font-style: italic;
  margin-bottom: 14px;
  line-height: 1.5;
}

.vep-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 14px 20px;
}

.vep-card-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.vep-card-label {
  font-size: 12px;
  color: #a0a4ab;
  line-height: 1.4;
}

.vep-card-value {
  font-size: 15px;
  font-weight: 500;
  color: #1a1a2e;
  line-height: 1.4;
  word-break: break-word;
}

/* Badge 状态标签 */
.vep-card-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  line-height: 1.6;
  width: fit-content;
}

.vep-badge--success {
  background: #eeffdd;
  color: #52c41a;
}

.vep-badge--warning {
  background: #fffbd9;
  color: #faad14;
}

.vep-badge--error {
  background: #ffeeed;
  color: #ff4d4f;
}

.vep-badge--info {
  background: #e6f4ff;
  color: #1677ff;
}

.vep-badge--default {
  background: #fafafa;
  color: #8c8c8c;
}
</style>
