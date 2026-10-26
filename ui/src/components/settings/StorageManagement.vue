<script setup lang="ts">
/**
 * 存储管理聚合组件 — 聚合会话文件、存储配置、附件管理与文件日志
 *
 */
import { ref } from 'vue'
import { useAccountStore } from '@/stores'
import StorageConfig from './StorageConfig.vue'
import FileManager from './FileManager.vue'
import FileLog from './FileLog.vue'
import SessionFile from '@/views/SessionFile/index.vue'

const props = withDefaults(defineProps<{
  /** 设置页展示会话文件；旧运维存储页保留独立用量表。 */
  showSessionFiles?: boolean
}>(), {
  showSessionFiles: true
})

/** 当前激活的Tab */
const activeTab = ref(props.showSessionFiles ? 'sessions' : 'config')
const accountStore = useAccountStore()
</script>

<template>
  <div class="storage-management">
    <ATabs v-model:activeKey="activeTab">
      <ATabPane v-if="props.showSessionFiles" key="sessions" tab="会话文件">
        <SessionFile embedded />
      </ATabPane>
      <ATabPane v-if="accountStore.hasPerm('system:storage:list')" key="config" tab="存储配置">
        <StorageConfig />
      </ATabPane>
      <ATabPane v-if="accountStore.hasPerm('system:storage:list')" key="files" tab="附件管理">
        <FileManager />
      </ATabPane>
      <ATabPane v-if="accountStore.hasPerm('system:storage:list')" key="logs" tab="文件日志">
        <FileLog />
      </ATabPane>
    </ATabs>
  </div>
</template>
<style lang="scss" scoped>
@use '@/styles/modules/_settings.scss' as *;

.storage-management {
  height: 100%;
  min-width: 0;
  overflow-y: auto;
}
</style>
