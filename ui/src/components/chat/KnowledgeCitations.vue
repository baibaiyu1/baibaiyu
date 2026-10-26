<script setup lang="ts">
import { FileTextOutlined, DownOutlined } from '@ant-design/icons-vue'
import type { KnowledgeCitationReference } from '@/types/chat'

defineProps<{ citations: KnowledgeCitationReference[] }>()
</script>

<template>
  <section class="knowledge-citations" aria-label="引用文档">
    <div class="knowledge-citations-title">参考文档<span>{{ citations.length }}</span></div>
    <div class="knowledge-citations-grid">
      <details v-for="citation in citations" :id="citation.anchorId" :key="citation.anchorId" class="knowledge-citation">
        <summary :title="citation.filePath">
          <span class="knowledge-citation-number">{{ citation.number }}</span>
          <FileTextOutlined class="knowledge-citation-icon" />
          <span class="knowledge-citation-info">
            <span class="knowledge-citation-file">{{ citation.fileName || citation.filePath }}</span>
            <span class="knowledge-citation-library">{{ citation.knowledgeName }}</span>
          </span>
          <DownOutlined class="knowledge-citation-arrow" />
        </summary>
        <div class="knowledge-citation-detail">
          <div class="knowledge-citation-path">{{ citation.filePath }}</div>
          <div class="knowledge-citation-excerpt">{{ citation.content || '此来源参与知识图谱检索，本次未返回对应的原文片段。' }}</div>
        </div>
      </details>
    </div>
  </section>
</template>

<style scoped>
.knowledge-citations { margin-top: 20px; padding-top: 12px; border-top: 1px solid var(--color-border-extra-light, #e5e7eb); }
.knowledge-citations-title { display: flex; align-items: center; gap: 7px; margin-bottom: 9px; color: var(--color-text-secondary, #666); font-size: 12px; }
.knowledge-citations-title > span { font-variant-numeric: tabular-nums; color: var(--color-text-placeholder, #999); }
.knowledge-citations-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 260px), 1fr)); gap: 8px; align-items: start; }
.knowledge-citation { min-width: 0; border: 1px solid var(--color-border-extra-light, #e5e7eb); border-radius: 9px; background: var(--color-bg-container, #fff); scroll-margin: 24px; }
.knowledge-citation summary { display: flex; align-items: center; gap: 9px; min-height: 58px; padding: 9px 11px; cursor: pointer; list-style: none; }
.knowledge-citation summary::-webkit-details-marker { display: none; }
.knowledge-citation summary::before { display: none; }
.knowledge-citation summary:focus-visible { outline: 2px solid #1677ff; outline-offset: 2px; border-radius: 9px; }
.knowledge-citation-number { display: grid; place-items: center; flex-shrink: 0; width: 21px; height: 21px; background: #edf4ff; border-radius: 5px; color: #1769d2; font-size: 11px; font-weight: 600; }
.knowledge-citation-icon { color: var(--color-text-secondary, #888); font-size: 17px; }
.knowledge-citation-info { display: flex; flex: 1; flex-direction: column; gap: 3px; min-width: 0; }
.knowledge-citation-file { overflow: hidden; color: var(--color-text-primary, #333); font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.knowledge-citation-library { color: var(--color-text-secondary, #888); font-size: 11px; }
.knowledge-citation-arrow { color: #999; font-size: 10px; transition: transform .15s; }
.knowledge-citation[open] .knowledge-citation-arrow { transform: rotate(180deg); }
.knowledge-citation:focus-within { border-color: #91bfff; }
.knowledge-citation-detail { padding: 0 12px 12px; border-top: 1px solid var(--color-border-extra-light, #eee); }
.knowledge-citation-path { margin-top: 8px; color: var(--color-text-secondary, #888); font-size: 11px; overflow-wrap: anywhere; }
.knowledge-citation-excerpt { margin-top: 6px; max-height: 240px; overflow-y: auto; color: var(--color-text-regular, #555); white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; line-height: 1.65; }
</style>
