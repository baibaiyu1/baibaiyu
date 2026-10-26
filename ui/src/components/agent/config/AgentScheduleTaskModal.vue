<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * 智能体编排页 —— 新增 / 编辑定时任务弹窗
 *
 * 版式参考「青云Agent平台」的新建定时任务弹窗：任务名称 + 输入内容 + 触发类型（周期 / 一次性）
 * + 执行策略（六段选择器 / 执行时刻 + Quartz Cron + 常用模板）+ 结果推送 + 状态。
 *
 * 设计取舍：
 * - **触发类型不额外落库**：一次性任务用 Quartz 的 7 段表达式（末段「年」）表达，触发一次即 COMPLETE，
 *   因此段数就能推导触发类型，避免为它加表字段；
 * - 任务名称 / 输入内容 / 推送目标沿用既有 `dataMap` JSON 约定（与 Automation 编辑器同源，
 *   运行期由 `AgentScheduler` 读取 `AgentJobWrapper.bizId` / `userPrompt`），同时写入旧字段
 *   `agentId` / `input` 兼容历史数据；
 * - 结果推送当前只做「选择 + 保存 + 列表展示」，真正的推送执行未接入后端。
 *
 * @component
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import type { Dayjs } from 'dayjs'
import { CloseOutlined, PlusOutlined, SwapOutlined } from '@ant-design/icons-vue'
import type { JobInfo } from '@/types'
import * as jobApi from '@/api/job'
import * as workflowApi from '@/api/workflow'
import AgentResourcePicker, { type AgentPickerItem } from '@/components/agent/studio/AgentResourcePicker.vue'
import {
  DEFAULT_CRON,
  buildCronFromParts,
  buildOnceCron,
  cronPresetGroups,
  describeCron,
  dayOptions,
  hourOptions,
  isValidCron,
  isOnceCron,
  minuteOptions,
  monthOptions,
  parseCronToParts,
  parseOnceTime,
  secondOptions,
  weekOptions,
  type CronParts,
  type ScheduleTriggerType
} from './scheduleCron'

/**
 * Props 定义
 */
const props = withDefaults(defineProps<{
  /** 是否打开 */
  open: boolean
  /** 所属智能体 ID */
  agentId: string
  /** 所属智能体名称（写入 dataMap.bizName，便于自动化列表复用展示） */
  agentName?: string
  /** 编辑时的任务（null / undefined = 新增） */
  task?: JobInfo | null
}>(), {
  agentName: '',
  task: null
})

/**
 * Emits 定义
 */
const emit = defineEmits<{
  'update:open': [value: boolean]
  /** 保存成功 */
  saved: []
}>()

/** 智能体定时任务的执行类（与后端 AgentScheduler 保持一致） */
const AGENT_JOB_CLASS = 'com.gmd.faq.server.main.scheduler.scheduler.AgentScheduler'

/** 通知渠道（后端 Channel 实体，type 为 WECOM / DINGTALK / FEISHU / EMAIL） */
interface ChannelOption {
  id: string
  name: string
  type?: string
  remark?: string
}

/** 渠道类型中文名 */
const channelTypeLabels: Record<string, string> = {
  WECOM: '企业微信',
  DINGTALK: '钉钉',
  FEISHU: '飞书',
  EMAIL: '邮箱'
}

const formRef = ref()
const saving = ref(false)
const isEdit = computed(() => !!props.task?.id)

/** 表单数据 */
const formData = reactive({
  name: '',
  input: '',
  triggerType: 'CRON' as ScheduleTriggerType,
  cron: DEFAULT_CRON,
  onceTime: dayjs().add(1, 'hour').startOf('minute') as Dayjs | null,
  pushChannelIds: [] as string[],
  enabled: true
})

/** 六段选择器 */
const cronParts = ref<CronParts>(parseCronToParts(DEFAULT_CRON))

/**
 * 弹窗内容区限高内滚（标题与底部「取消 / 保存」始终可见）
 *
 * 不加这层限高时，整窗高度由内容撑开、超过视口后由 antd 的 `.ant-modal-wrap` 滚动整窗，
 * 结果标题与按钮都会被滚出屏幕。这里按视口算一个上限（扣掉顶部偏移 100 + 头部 ~55 + 底部 ~65 + 边距），
 * 让滚动只发生在表单区。
 */
const modalBodyStyle = {
  maxHeight: 'calc(100vh - 260px)',
  overflowY: 'auto' as const
}

/**
 * Cron 校验：周期任务是 6 段，一次性任务是 7 段且执行时刻必须晚于当前时间
 */
async function validateCron() {
  const cron = (formData.cron || '').trim()
  if (!cron) {
    throw new Error(formData.triggerType === 'ONCE' ? '请选择执行时间' : '请输入 Cron 表达式')
  }
  if (!isValidCron(cron)) {
    throw new Error('Cron 表达式需要 6 段（秒 分 时 日 月 周）或 7 段（末段为年）')
  }
  if (formData.triggerType === 'ONCE') {
    const time = parseOnceTime(cron)
    if (!time) throw new Error('一次性任务需要精确到「年」的 7 段表达式')
    if (!time.isAfter(dayjs())) throw new Error('执行时间必须晚于当前时间')
  }
}

const formRules = {
  name: [{ required: true, message: '请输入任务名称', trigger: 'blur' }],
  input: [{ required: true, message: '请输入输入内容', trigger: 'blur' }],
  cron: [{ validator: validateCron, trigger: 'change' }]
}

/** 表达式描述（每秒随表单变化） */
const cronText = computed(() => describeCron(formData.cron))

/* ===================== 触发类型 / 执行策略 ===================== */

/**
 * 切换触发类型：周期 ←→ 一次性，两个方向都要把表达式改写成对应形态
 * @param value 触发类型
 */
function handleTriggerTypeChange(value: string | number) {
  formData.triggerType = value as ScheduleTriggerType
  if (formData.triggerType === 'ONCE') {
    const time = formData.onceTime || dayjs().add(1, 'hour').startOf('minute')
    formData.onceTime = time
    formData.cron = buildOnceCron(time)
  } else {
    formData.cron = buildCronFromParts(cronParts.value)
  }
}

/**
 * 执行时刻变化（一次性任务）
 * @param value 执行时刻
 */
function handleOnceTimeChange(value: unknown) {
  const time = value as Dayjs | null
  if (!time) return
  formData.cron = buildOnceCron(time)
}

/**
 * 六段选择器变化 → 回写表达式
 */
function handlePartsChange() {
  if (formData.triggerType !== 'CRON') return
  formData.cron = buildCronFromParts(cronParts.value)
}

/**
 * 手工编辑表达式 → 解析回选择器（7 段自动切到「一次性任务」）
 */
function handleCronInputChange() {
  const cron = (formData.cron || '').trim()
  if (!cron) return
  cronParts.value = parseCronToParts(cron)
  if (isOnceCron(cron)) {
    formData.triggerType = 'ONCE'
    formData.onceTime = parseOnceTime(cron)
  }
}

/**
 * 应用常用模板
 * @param cron 模板表达式
 */
function applyPreset(cron: string) {
  formData.triggerType = 'CRON'
  formData.cron = cron
  cronParts.value = parseCronToParts(cron)
}

/** 禁用「今天之前」的日期（一次性任务的执行时刻必须在将来） */
function disabledOnceDate(current: Dayjs): boolean {
  return current && current < dayjs().startOf('day')
}

/* ===================== 结果推送（通知渠道） ===================== */

const channelPickerOpen = ref(false)
const channels = ref<ChannelOption[]>([])
const channelsLoaded = ref(false)

/**
 * 加载可选通知渠道（只在第一次打开选择器时拉取）
 */
async function loadChannels() {
  if (channelsLoaded.value) return
  try {
    const response = await workflowApi.enabledChannels()
    channels.value = (response.data.data || []) as unknown as ChannelOption[]
    channelsLoaded.value = true
  } catch (e) {
    console.error('加载通知渠道失败:', e)
  }
}

/** 选择器候选项 */
const channelItems = computed<AgentPickerItem[]>(() => channels.value.map(channel => ({
  id: String(channel.id),
  name: channel.name,
  desc: channel.remark,
  badge: channelTypeLabels[channel.type || ''] || channel.type
})))

/** 已选推送目标（保持选择顺序；渠道被删除时回落到 id 展示） */
const pushChannels = computed(() => formData.pushChannelIds.map(id => {
  const found = channels.value.find(item => String(item.id) === id)
  return found || { id, name: `已失效渠道 #${id}`, type: undefined as string | undefined }
}))

function openChannelPicker() {
  void loadChannels()
  channelPickerOpen.value = true
}

/**
 * 确认推送目标
 * @param ids 选中的渠道 id
 */
function handleChannelConfirm(ids: string[]) {
  formData.pushChannelIds = ids.map(id => String(id))
}

/**
 * 移除单个推送目标
 * @param id 渠道 id
 */
function removePushChannel(id: string) {
  formData.pushChannelIds = formData.pushChannelIds.filter(item => item !== id)
}

/* ===================== 表单初始化 / 提交 ===================== */

/**
 * 解析任务上的 dataMap（容错：非 JSON 时按空对象处理）
 * @param dataMap 任务的 dataMap 字符串
 */
function parseDataMap(dataMap?: string): Record<string, unknown> {
  if (!dataMap) return {}
  try {
    const parsed = JSON.parse(dataMap)
    return parsed && typeof parsed === 'object' ? parsed as Record<string, unknown> : {}
  } catch {
    return {}
  }
}

/**
 * 用被测任务 / 默认值初始化表单
 */
function initForm() {
  const job = props.task
  const dataMap = parseDataMap(job?.dataMap)
  const cron = job?.cron || DEFAULT_CRON

  formData.name = String(dataMap.jobName || '')
  // 新格式 userPrompt 优先，兼容旧格式 input
  formData.input = String(dataMap.userPrompt || dataMap.input || '')
  formData.enabled = job ? job.enabled !== false : true
  formData.cron = cron
  formData.triggerType = dataMap.triggerType === 'ONCE' || isOnceCron(cron) ? 'ONCE' : 'CRON'
  formData.onceTime = parseOnceTime(cron) || dayjs().add(1, 'hour').startOf('minute')
  cronParts.value = parseCronToParts(cron)
  formData.pushChannelIds = Array.isArray(dataMap.pushChannelIds)
    ? (dataMap.pushChannelIds as unknown[]).map(id => String(id))
    : []
  // 推送目标需要渠道名回显
  void loadChannels()
  formRef.value?.clearValidate?.()
}

/**
 * 保存任务
 */
async function handleSubmit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }

  saving.value = true
  try {
    const input = formData.input
    const dataMap = {
      jobName: formData.name,
      bizName: props.agentName || '',
      bizId: props.agentId,
      type: 'AGENT',
      userPrompt: input,
      // 兼容旧字段（历史任务读的是 agentId / input）
      agentId: props.agentId,
      input,
      triggerType: formData.triggerType,
      pushChannelIds: formData.pushChannelIds
    }

    const job: JobInfo = {
      id: props.task?.id,
      type: 'AGENT',
      bizId: props.agentId,
      cron: formData.cron.trim(),
      jobClass: AGENT_JOB_CLASS,
      dataMap: JSON.stringify(dataMap),
      enabled: formData.enabled
    }

    if (isEdit.value) {
      await jobApi.update(job)
      message.success('定时任务更新成功')
    } else {
      await jobApi.add(job)
      message.success('定时任务新增成功')
    }
    emit('saved')
    emit('update:open', false)
  } catch (e) {
    console.error('保存定时任务失败:', e)
  } finally {
    saving.value = false
  }
}

/**
 * 取消 / 关闭
 */
function handleCancel() {
  emit('update:open', false)
}

// 每次打开都按当前任务重置表单
watch(() => props.open, (open) => {
  if (open) initForm()
})

onMounted(() => {
  if (props.open) initForm()
})
</script>

<template>
  <a-modal
    :open="open"
    :width="640"
    :title="isEdit ? '编辑定时任务' : '新增定时任务'"
    :body-style="modalBodyStyle"
    :mask-closable="false"
    :destroy-on-close="true"
    wrap-class-name="agsched-task-modal"
    @cancel="handleCancel"
  >
    <AForm ref="formRef" :model="formData" :rules="formRules" layout="vertical" class="agsched-form">
      <!-- 任务名称 -->
      <AFormItem label="任务名称" name="name">
        <AInput
          v-model:value="formData.name"
          placeholder="请输入任务名称，用于区分不同任务"
          :maxlength="50"
          show-count
        />
      </AFormItem>

      <!-- 输入内容 -->
      <AFormItem label="输入内容" name="input">
        <ATextarea
          v-model:value="formData.input"
          placeholder="请输入定时任务触发时发送给智能体的消息内容"
          :rows="4"
          :maxlength="500"
          show-count
        />
      </AFormItem>

      <!-- 触发类型 -->
      <AFormItem label="触发类型">
        <ASegmented
          :value="formData.triggerType"
          :options="[
            { label: '周期任务', value: 'CRON' },
            { label: '一次性任务', value: 'ONCE' }
          ]"
          @change="handleTriggerTypeChange"
        />
      </AFormItem>

      <!-- 执行策略 -->
      <AFormItem label="执行策略" name="cron" required>
        <!-- 周期任务：六段选择器 -->
        <div v-if="formData.triggerType === 'CRON'" class="agsched-form__cron">
          <div class="agsched-form__cron-cell">
            <span class="agsched-form__cron-label">秒</span>
            <ASelect v-model:value="cronParts.second" :options="secondOptions" @change="handlePartsChange" />
          </div>
          <div class="agsched-form__cron-cell">
            <span class="agsched-form__cron-label">分</span>
            <ASelect v-model:value="cronParts.minute" :options="minuteOptions" @change="handlePartsChange" />
          </div>
          <div class="agsched-form__cron-cell">
            <span class="agsched-form__cron-label">时</span>
            <ASelect v-model:value="cronParts.hour" :options="hourOptions" @change="handlePartsChange" />
          </div>
          <div class="agsched-form__cron-cell">
            <span class="agsched-form__cron-label">日</span>
            <ASelect v-model:value="cronParts.day" :options="dayOptions" @change="handlePartsChange" />
          </div>
          <div class="agsched-form__cron-cell">
            <span class="agsched-form__cron-label">月</span>
            <ASelect v-model:value="cronParts.month" :options="monthOptions" @change="handlePartsChange" />
          </div>
          <div class="agsched-form__cron-cell">
            <span class="agsched-form__cron-label">周</span>
            <ASelect v-model:value="cronParts.week" :options="weekOptions" @change="handlePartsChange" />
          </div>
        </div>

        <!-- 一次性任务：指定执行时刻 -->
        <div v-else class="agsched-form__once">
          <ADatePicker
            v-model:value="formData.onceTime"
            show-time
            format="YYYY-MM-DD HH:mm:ss"
            placeholder="请选择执行时刻"
            :disabled-date="disabledOnceDate"
            style="width: 100%"
            @change="handleOnceTimeChange"
          />
          <div class="agsched-form__hint">一次性任务到点执行一次后自动结束，不会重复触发。</div>
        </div>

        <!-- Quartz Cron -->
        <div class="agsched-form__cron-field">
          <span class="agsched-form__cron-title">Quartz Cron</span>
          <AInput
            v-model:value="formData.cron"
            placeholder="0 0 * * * ?"
            @change="handleCronInputChange"
          />
          <div class="agsched-form__cron-desc">{{ cronText }}</div>
        </div>
      </AFormItem>

      <!-- 常用模板 -->
      <div class="agsched-form__presets">
        <span class="agsched-form__presets-title">常用模板：</span>
        <div v-for="group in cronPresetGroups" :key="group.label" class="agsched-form__preset-row">
          <span class="agsched-form__preset-label">{{ group.label }}</span>
          <div class="agsched-form__preset-chips">
            <button
              v-for="preset in group.presets"
              :key="preset.cron"
              type="button"
              class="agsched-form__chip"
              :class="{ 'is-active': formData.cron.trim() === preset.cron }"
              @click="applyPreset(preset.cron)"
            >
              {{ preset.label }}
            </button>
          </div>
        </div>
      </div>

      <!-- 结果推送 -->
      <AFormItem label="结果推送（可选）" class="agsched-form__push">
        <div class="agsched-form__hint">任务结果始终保存为内部会话，还可推送到以下目标。</div>

        <div v-if="pushChannels.length" class="agsched-form__picked">
          <span v-for="channel in pushChannels" :key="channel.id" class="agsched-form__pill">
            <span class="agsched-form__pill-text">{{ channel.name }}</span>
            <em v-if="channel.type">{{ channelTypeLabels[channel.type] || channel.type }}</em>
            <button type="button" title="移除" @click="removePushChannel(channel.id)">
              <CloseOutlined />
            </button>
          </span>
          <button type="button" class="agsched-form__pick-inline" @click="openChannelPicker">
            <SwapOutlined />重新选择
          </button>
        </div>

        <button v-else type="button" class="agsched-form__pick" @click="openChannelPicker">
          <PlusOutlined />选择推送目标
        </button>
      </AFormItem>

      <!-- 状态 -->
      <AFormItem label="状态">
        <ASwitch v-model:checked="formData.enabled" />
      </AFormItem>
    </AForm>

    <template #footer>
      <AButton @click="handleCancel">取消</AButton>
      <AButton type="primary" :loading="saving" @click="handleSubmit">保存</AButton>
    </template>

    <!-- 推送目标选择：复用编排页的资源选择弹窗 -->
    <AgentResourcePicker
      v-if="channelPickerOpen"
      :open="true"
      title="选择推送目标"
      description="任务执行完成后，把结果推送到选中的通知渠道（可多选）。"
      :items="channelItems"
      :selected-ids="formData.pushChannelIds"
      search-placeholder="搜索渠道名称 / 描述"
      empty-text="暂无通知渠道，请先到「渠道配置」中添加"
      @update:open="(value: boolean) => { if (!value) channelPickerOpen = false }"
      @confirm="handleChannelConfirm"
    />
  </a-modal>
</template>

<style scoped lang="scss">
@use '@/styles/agent/schedule.scss' as *;
</style>
