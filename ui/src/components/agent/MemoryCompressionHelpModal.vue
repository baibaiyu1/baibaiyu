<script setup lang="ts">
/** 记忆压缩说明文档弹窗。 */
defineProps<{
  open: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
}>()
</script>

<template>
  <AModal
    :open="open"
    title="记忆压缩配置说明"
    :footer="null"
    width="860px"
    style="top: 0"
    @cancel="emit('update:open', false)"
  >
    <div class="compression-doc">
      <AAlert
        type="info"
        show-icon
        message="压缩只作用于当前【工作上下文】"
        description="对话页面的消息记录保持不变。每次推理前检查送入模型的工作上下文，并在达到阈值时用摘要替换较早内容。"
      />

      <h3>一、什么时候触发压缩</h3>
      <p>
        每次推理前，系统都会计算当前消息数和 token 数。满足下面任意一个条件就进入压缩流程：
      </p>
      <div class="formula">消息数 ≥ [消息阈值]，或 token 数 ≥ [最大Token数 × Token比率]</div>
      <p>
        <b>触发并不等于一定能压缩。</b>没有可安全摘要的历史、或摘要没有减少上下文占用时，本轮会跳过压缩。
      </p>

      <h3>二、摘要与保留规则</h3>
      <ol>
        <li>将较早消息及已有摘要合并为一条新的摘要，记录任务目标、已完成工作和待办事项。</li>
        <li>原样保留最近 N 条消息；为保留完整工具调用与结果，实际数量可能更多。</li>
        <li>任务列表单独保存，摘要后仍会在推理时提醒智能体。</li>
      </ol>

      <h3>三、配置项如何影响算法</h3>
      <div class="config-table">
        <div class="config-row config-head"><span>配置项</span><span>作用与建议</span></div>
        <div class="config-row"><code>最大Token数</code><span>模型上下文窗口的预算上限，应与实际模型一致。</span></div>
        <div class="config-row"><code>Token比率</code><span>Token 触发比例。0.70-0.80 通常较平衡，越低越早压缩。</span></div>
        <div class="config-row"><code>消息阈值</code><span>消息数触发阈值。短消息很多的 Agent 应降低；工具密集型 Agent 可适当提高。</span></div>
        <div class="config-row"><code>保留最近消息数</code><span>建议为消息阈值的 10%-30%，常见范围为 4-18；设置过大可能没有可摘要的历史。</span></div>
      </div>

      <h3>四、配置检查要点</h3>
      <ul>
        <li>保留最近消息数应小于消息阈值，给历史摘要留出空间。</li>
        <li>上下文窗口发生变化后，应重新使用“智能配置”。</li>
        <li>摘要会损失部分细节，并需要额外一次模型调用。旧版的卸载、预览和当前轮压缩比参数已停止使用。</li>
        <li>圆环显示工作上下文的综合压缩压力，不代表已经压缩的比例。</li>
      </ul>
    </div>
  </AModal>
</template>

<style scoped lang="scss">
.compression-doc { color: var(--color-text); line-height: 1.7; }
.compression-doc h3 { margin: 20px 0 8px; font-size: 16px; }
.compression-doc p { margin: 6px 0; }
.compression-doc ol, .compression-doc ul { padding-left: 22px; margin: 8px 0; }
.formula { padding: 10px 14px; margin: 10px 0; background: #f5f7fa; border-left: 3px solid #1677ff; }
.config-table { border: 1px solid #e5e7eb; border-radius: 6px; overflow: hidden; }
.config-row { display: grid; grid-template-columns: 230px 1fr; gap: 16px; padding: 9px 12px; border-top: 1px solid #eef0f2; }
.config-row:first-child { border-top: 0; }
.config-head { font-weight: 600; background: #f7f8fa; }
.config-row code { color: #0958d9; }
@media (max-width: 700px) { .config-row { grid-template-columns: 1fr; gap: 2px; } }
</style>
