<!-- eslint-disable vue-multi-word-component-names -->
<script setup lang="ts">
/**
 * MCP 服务发布信息弹窗
 *
 * 展示平台工具以 MCP 协议对外发布的接入信息：端点地址、鉴权方式、已发布工具数量与客户端配置示例。
 * 发布范围与后端一致：本租户内「已启用 + 本组织可见 + 未开启人工确认」的工具。
 *
 * @component
 */
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { CheckOutlined, CopyOutlined } from '@ant-design/icons-vue'
import * as toolApi from '@/api/tool'
import type { ToolVO } from '@/types'

const props = defineProps<{
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
}>()

const loading = ref(false)
const publishedCount = ref(0)
const publishedTools = ref<ToolVO[]>([])
const copiedKey = ref('')

/** 对外端点（经网关：/api + runtime 的 /runtime/mcp） */
const endpoint = computed(() => `${window.location.origin}/api/runtime/mcp`)

/** 客户端配置示例（Claude Desktop / MCP Inspector 通用 http 形态） */
const clientConfig = computed(() => JSON.stringify(
  {
    mcpServers: {
      'gmd-faq-agent-platform': {
        type: 'http',
        url: endpoint.value,
        headers: { Authorization: 'sk-在此填入你的 API Key' }
      }
    }
  },
  null,
  2
))

/** 直接调用示例 */
const curlExample = computed(() =>
  `curl -X POST '${endpoint.value}' \\\n` +
  `  -H 'Authorization: sk-你的APIKey' \\\n` +
  `  -H 'Content-Type: application/json' \\\n` +
  `  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'`
)

watch(() => props.visible, async (visible) => {
  if (!visible) return
  copiedKey.value = ''
  await loadPublishedTools()
})

/**
 * 统计会被发布的工具（与后端 /runtime/mcp 的筛选条件对齐）
 */
async function loadPublishedTools() {
  loading.value = true
  try {
    const res = await toolApi.page({ page: 1, size: 100, enabled: true, scope: 'public' })
    const records = res.data.data?.records || []
    publishedTools.value = records.filter((item) => !item.needConfirm)
    publishedCount.value = publishedTools.value.length
  } catch (e) {
    console.error('[MCP 发布] 加载已发布工具失败', e)
    publishedTools.value = []
    publishedCount.value = 0
  } finally {
    loading.value = false
  }
}

async function copy(text: string, key: string) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const input = document.createElement('textarea')
      input.value = text
      document.body.appendChild(input)
      input.select()
      document.execCommand('copy')
      document.body.removeChild(input)
    }
    copiedKey.value = key
    message.success('已复制')
    window.setTimeout(() => {
      if (copiedKey.value === key) copiedKey.value = ''
    }, 2000)
  } catch (e) {
    console.error('[MCP 发布] 复制失败', e)
    message.error('复制失败，请手动选择复制')
  }
}

function close() {
  emit('update:visible', false)
}
</script>

<template>
  <AModal
    :open="visible"
    title="发布为 MCP 服务"
    width="720px"
    :footer="null"
    @cancel="close"
  >
    <div class="mcp-publish-tip">
      平台把本租户的工具以 <b>MCP（Model Context Protocol）</b> 协议对外发布，
      第三方 MCP 客户端（Claude Desktop、IDE 插件、MCP Inspector 等）可直接列出并调用这些工具。
      发布范围：<b>已启用 + 本组织可见 + 未开启人工确认</b>的工具。
    </div>

    <div class="mcp-publish-block">
      <div class="mcp-publish-label">
        <span>服务端点</span>
        <AButton size="small" type="text" @click="copy(endpoint, 'endpoint')">
          <template #icon>
            <CheckOutlined v-if="copiedKey === 'endpoint'" />
            <CopyOutlined v-else />
          </template>
        </AButton>
      </div>
      <code class="mcp-publish-code">{{ endpoint }}</code>
      <div class="mcp-publish-hint">POST + JSON-RPC 2.0；请求头带 <code>Accept: text/event-stream</code> 时按 SSE 返回。</div>
    </div>

    <div class="mcp-publish-block">
      <div class="mcp-publish-label"><span>鉴权方式</span></div>
      <div class="mcp-publish-hint">
        复用平台 API Key：请求头 <code>Authorization: sk-xxx</code>，租户由该 Key 归属决定。
        可在「系统设置 → API Keys」中创建。请勿在客户端代码中明文暴露 Key。
      </div>
    </div>

    <div class="mcp-publish-block">
      <div class="mcp-publish-label">
        <span>已发布工具（{{ publishedCount }}）</span>
        <AButton size="small" type="text" :loading="loading" @click="loadPublishedTools">刷新</AButton>
      </div>
      <div v-if="!publishedCount && !loading" class="mcp-publish-hint">
        暂无可发布的工具：请先创建/启用工具，并确保其可见性为「本组织公开」。
      </div>
      <div v-else class="mcp-publish-tools">
        <span v-for="item in publishedTools" :key="String(item.id)" class="mcp-publish-tool">
          {{ item.toolId }}
        </span>
        <span v-if="publishedTools.length >= 100" class="mcp-publish-hint">（仅展示前 100 个）</span>
      </div>
    </div>

    <div class="mcp-publish-block">
      <div class="mcp-publish-label">
        <span>客户端配置示例</span>
        <AButton size="small" type="text" @click="copy(clientConfig, 'config')">
          <template #icon>
            <CheckOutlined v-if="copiedKey === 'config'" />
            <CopyOutlined v-else />
          </template>
        </AButton>
      </div>
      <pre class="mcp-publish-pre">{{ clientConfig }}</pre>
    </div>

    <div class="mcp-publish-block">
      <div class="mcp-publish-label">
        <span>调用示例</span>
        <AButton size="small" type="text" @click="copy(curlExample, 'curl')">
          <template #icon>
            <CheckOutlined v-if="copiedKey === 'curl'" />
            <CopyOutlined v-else />
          </template>
        </AButton>
      </div>
      <pre class="mcp-publish-pre">{{ curlExample }}</pre>
    </div>

    <div class="mcp-publish-methods">
      已实现方法：<code>initialize</code> · <code>notifications/initialized</code> · <code>ping</code> ·
      <code>tools/list</code> · <code>tools/call</code>；<code>GET</code> 返回 405（无服务端推送流），<code>DELETE</code> 返回 204。
    </div>

    <div class="mcp-publish-actions">
      <AButton type="primary" @click="close">知道了</AButton>
    </div>
  </AModal>
</template>

<style scoped lang="scss">
.mcp-publish-tip {
  padding: 10px 12px;
  color: #5b6a86;
  border: 1px solid #e4ebf7;
  border-radius: 8px;
  background: #f7faff;
  font-size: 12px;
  line-height: 1.8;

  b {
    color: #2b3445;
  }
}

.mcp-publish-block {
  margin-top: 14px;
}

.mcp-publish-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
  color: #2b3445;
  font-size: 13px;
  font-weight: 600;
}

.mcp-publish-code {
  display: block;
  padding: 8px 10px;
  color: #4449d0;
  border: 1px solid #eef1f6;
  border-radius: 6px;
  background: #fafcff;
  font-size: 12px;
  word-break: break-all;
}

.mcp-publish-hint {
  margin-top: 6px;
  color: #8c98a8;
  font-size: 12px;
  line-height: 1.7;

  code {
    padding: 1px 4px;
    border-radius: 3px;
    background: #eef2f9;
  }
}

.mcp-publish-tools {
  display: flex;
  max-height: 110px;
  flex-wrap: wrap;
  gap: 6px;
  overflow-y: auto;
}

.mcp-publish-tool {
  padding: 3px 8px;
  color: #5268e9;
  border-radius: 6px;
  background: #eef1ff;
  font-size: 12px;
}

.mcp-publish-pre {
  margin: 0;
  padding: 10px 12px;
  overflow-x: auto;
  color: #d8e0f0;
  border-radius: 8px;
  background: #202837;
  font-family: Consolas, monospace;
  font-size: 11px;
  line-height: 1.6;
}

.mcp-publish-methods {
  margin-top: 14px;
  color: #8c98a8;
  font-size: 12px;
  line-height: 1.7;

  code {
    padding: 1px 4px;
    color: #5268e9;
    border-radius: 3px;
    background: #eef1ff;
  }
}

.mcp-publish-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
