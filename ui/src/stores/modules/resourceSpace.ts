import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { listSpaces, type ResourceSpace } from '@/api/resource'

/** 五类资源共用工作范围。身份、角色、会话范围均由原有服务决定。 */
export const useResourceSpaceStore = defineStore('resourceSpace', () => {
  const selectedSpace = ref('personal')
  const spaces = ref<ResourceSpace[]>([])
  const loaded = ref(false)
  let pending: Promise<void> | null = null
  let generation = 0
  const creationSpace = computed(() => selectedSpace.value === 'all' ? 'personal' : selectedSpace.value)
  const canCreate = computed(() => creationSpace.value === 'personal'
    || spaces.value.some(item => item.key === creationSpace.value && item.canCreate))
  const creationLabel = computed(() => spaces.value.find(item => item.key === creationSpace.value)?.name || '个人空间')

  async function load(force = false) {
    if (loaded.value && !force) return
    if (pending) return pending
    const version = generation
    pending = (async () => {
      try {
        const response = await listSpaces()
        if (version !== generation) return
        spaces.value = response.data.data || []
        loaded.value = true
        if (selectedSpace.value !== 'all' && !spaces.value.some(item => item.key === selectedSpace.value)) {
          selectedSpace.value = 'personal'
        }
      } finally {
        if (version === generation) pending = null
      }
    })()
    return pending
  }

  function reset() {
    generation++
    selectedSpace.value = 'personal'
    spaces.value = []
    loaded.value = false
    pending = null
  }

  return { selectedSpace, spaces, loaded, creationSpace, creationLabel, canCreate, load, reset }
})
