import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { createSelectors } from '@lightrag/lib/utils'
import { defaultQueryLabel } from '@lightrag/lib/queryDefaults'
import { managementStorageKey } from '@lightrag/lib/platformManagement'
type Theme = 'dark' | 'light' | 'system'
type Language = 'en' | 'zh'
type Tab = 'documents' | 'knowledge-graph'

interface SettingsState {
  // Document manager settings
  showFileName: boolean
  setShowFileName: (show: boolean) => void

  documentsPageSize: number
  setDocumentsPageSize: (size: number) => void

  // Graph viewer settings
  showPropertyPanel: boolean
  showNodeSearchBar: boolean
  showLegend: boolean
  setShowLegend: (show: boolean) => void

  showNodeLabel: boolean
  enableNodeDrag: boolean

  showEdgeLabel: boolean
  enableHideUnselectedEdges: boolean
  enableEdgeEvents: boolean

  minEdgeSize: number
  setMinEdgeSize: (size: number) => void

  maxEdgeSize: number
  setMaxEdgeSize: (size: number) => void

  graphQueryMaxDepth: number
  setGraphQueryMaxDepth: (depth: number) => void

  graphMaxNodes: number
  setGraphMaxNodes: (nodes: number, triggerRefresh?: boolean) => void

  backendMaxGraphNodes: number | null
  setBackendMaxGraphNodes: (maxNodes: number | null) => void

  // Graph query
  queryLabel: string
  setQueryLabel: (queryLabel: string) => void

  // App settings
  theme: Theme
  setTheme: (theme: Theme) => void

  language: Language
  setLanguage: (lang: Language) => void

  enableHealthCheck: boolean
  setEnableHealthCheck: (enable: boolean) => void

  currentTab: Tab
  setCurrentTab: (tab: Tab) => void

  // Search label dropdown refresh trigger (non-persistent, runtime only)
  searchLabelDropdownRefreshTrigger: number
  triggerSearchLabelDropdownRefresh: () => void
}


const useSettingsStoreBase = create<SettingsState>()(persist(
(set) => ({
      theme: 'light',
      language: 'zh',
      showPropertyPanel: true,
      showNodeSearchBar: true,
      showLegend: false,

      showNodeLabel: true,
      enableNodeDrag: true,

      showEdgeLabel: false,
      enableHideUnselectedEdges: true,
      enableEdgeEvents: false,

      minEdgeSize: 1,
      maxEdgeSize: 1,

      graphQueryMaxDepth: 3,
      graphMaxNodes: 1000,
      backendMaxGraphNodes: null,

      queryLabel: defaultQueryLabel,

      enableHealthCheck: true,

      currentTab: 'documents',
      showFileName: false,
      documentsPageSize: 10,

      setTheme: (theme: Theme) => set({ theme }),

      setLanguage: (language: Language) => set({ language }),

      setQueryLabel: (queryLabel: string) =>
        set({
          queryLabel
        }),

      setGraphQueryMaxDepth: (depth: number) => set({ graphQueryMaxDepth: depth }),

      setGraphMaxNodes: (nodes: number, triggerRefresh: boolean = false) => {
        const state = useSettingsStore.getState();
        if (state.graphMaxNodes === nodes) {
          return;
        }

        if (triggerRefresh) {
          const currentLabel = state.queryLabel;
          // Atomically update both the node count and the query label to trigger a refresh.
          set({ graphMaxNodes: nodes, queryLabel: '' });

          // Restore the label after a short delay.
          setTimeout(() => {
            set({ queryLabel: currentLabel });
          }, 300);
        } else {
          set({ graphMaxNodes: nodes });
        }
      },

      setBackendMaxGraphNodes: (maxNodes: number | null) => set({ backendMaxGraphNodes: maxNodes }),

      setMinEdgeSize: (size: number) => set({ minEdgeSize: size }),

      setMaxEdgeSize: (size: number) => set({ maxEdgeSize: size }),

      setEnableHealthCheck: (enable: boolean) => set({ enableHealthCheck: enable }),

      setCurrentTab: (tab: Tab) => set({ currentTab: tab }),

      setShowFileName: (show: boolean) => set({ showFileName: show }),
      setShowLegend: (show: boolean) => set({ showLegend: show }),
      setDocumentsPageSize: (size: number) => set({ documentsPageSize: size }),

      // Search label dropdown refresh trigger (not persisted)
      searchLabelDropdownRefreshTrigger: 0,
      triggerSearchLabelDropdownRefresh: () =>
        set((state) => ({
          searchLabelDropdownRefreshTrigger: state.searchLabelDropdownRefreshTrigger + 1
        }))
    }),
{ name: managementStorageKey('preferences'), partialize: state => {
  const { queryLabel: _label, currentTab: _tab, backendMaxGraphNodes: _max, searchLabelDropdownRefreshTrigger: _refresh, ...preferences } = state
  return preferences
} }
))
const useSettingsStore = createSelectors(useSettingsStoreBase)
export { useSettingsStore, type Theme }
