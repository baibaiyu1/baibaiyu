import { onMounted, ref } from 'vue'
import { modelOptions, type KnowledgeModelOption } from '@/api/knowledge'

export function useKnowledgeModelOptions() {
  const models = ref<KnowledgeModelOption[]>([])
  const loading = ref(false)
  onMounted(async () => {
    loading.value = true
    try { models.value = (await modelOptions()).data.data || [] }
    finally { loading.value = false }
  })
  return { models, loading }
}
