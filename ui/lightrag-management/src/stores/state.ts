import { create } from 'zustand'
import { createSelectors } from '@lightrag/lib/utils'
import { checkHealth, type LightragStatus } from '@lightrag/api/lightrag'
import { useSettingsStore } from './settings'

interface BackendState {
  health: boolean
  message: string | null
  messageTitle: string | null
  status: LightragStatus | null
  lastCheckTime: number
  pipelineBusy: boolean
  pipelineActive: boolean
  check: () => Promise<boolean>
  setErrorMessage: (message: string, title: string) => void
}
let generation = 0
const store = create<BackendState>()((set, get) => ({
  health: true, message: null, messageTitle: null, status: null,
  lastCheckTime: 0, pipelineBusy: false, pipelineActive: false,
  check: async () => {
    const current = ++generation
    const status = await checkHealth()
    if (current !== generation) return get().health
    if (status.status !== 'healthy') {
      set({ health: false, message: status.message, status: null, lastCheckTime: Date.now() })
      return false
    }
    const maxNodes = Number(status.configuration?.max_graph_nodes)
    if (Number.isSafeInteger(maxNodes) && maxNodes > 0) {
      const settings = useSettingsStore.getState()
      settings.setBackendMaxGraphNodes(maxNodes)
      if (settings.graphMaxNodes > maxNodes) settings.setGraphMaxNodes(maxNodes, true)
    }
    set({ health: true, status, message: null, messageTitle: null, lastCheckTime: Date.now(),
      pipelineBusy: status.pipeline_busy, pipelineActive: status.pipeline_active ?? status.pipeline_busy })
    return true
  },
  setErrorMessage: (message, messageTitle) => set({ health: false, message, messageTitle })
}))
export const useBackendState = createSelectors(store)
