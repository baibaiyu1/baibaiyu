import { lazy, Suspense, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Toaster } from 'sonner'
import ThemeProvider from '@lightrag/components/ThemeProvider'
import TabVisibilityProvider from '@lightrag/contexts/TabVisibilityProvider'
import ErrorBoundary from '@lightrag/components/ErrorBoundary'
import { useBackendState } from '@lightrag/stores/state'
import { useSettingsStore } from '@lightrag/stores/settings'
import { healthCheckInterval } from '@lightrag/lib/constants'
import { isPlatformManaged, managementView, notifyManagementHost } from '@lightrag/lib/platformManagement'

const DocumentManager = lazy(() => import('@lightrag/features/DocumentManager'))
const GraphViewer = lazy(() => import('@lightrag/features/GraphViewer'))

/** Dedicated entry: native document processing and graph browsing without the admin shell. */
export default function PlatformManagementApp() {
  const { t } = useTranslation()
  const view = managementView()
  const enableHealthCheck = useSettingsStore.use.enableHealthCheck()
  const [ready, setReady] = useState(false)
  const [failed, setFailed] = useState(!isPlatformManaged())

  useEffect(() => {
    if (!isPlatformManaged()) return
    let active = true
    useSettingsStore.getState().setCurrentTab(view === 'graph' ? 'knowledge-graph' : 'documents')
    const check = async () => {
      try {
        const healthy = await useBackendState.getState().check()
        if (!active) return
        setFailed(!healthy)
        if (healthy) setReady(true)
        notifyManagementHost(healthy ? 'ready' : 'error')
      } catch {
        if (active) {
          setFailed(true)
          notifyManagementHost('error')
        }
      }
    }
    void check()
    const timer = enableHealthCheck ? setInterval(() => { void check() }, healthCheckInterval * 1000) : undefined
    return () => {
      active = false
      clearInterval(timer)
    }
  }, [view, enableHealthCheck])

  return (
    <ThemeProvider>
      <TabVisibilityProvider>
        <main className="relative flex h-screen w-screen flex-col overflow-hidden" aria-label={t(view === 'graph' ? 'header.knowledgeGraph' : 'header.documents')}>
          {failed ? (
            <p role="alert" className="m-auto p-6">{t('platformManagement.connectionError', 'Open this page from the platform knowledge base and check the connection settings.')}</p>
          ) : ready ? (
            <ErrorBoundary>
              <Suspense fallback={<p role="status" className="m-auto">{t('common.loading', 'Loading…')}</p>}>
                {view === 'graph' ? <GraphViewer readOnly /> : <DocumentManager />}
              </Suspense>
            </ErrorBoundary>
          ) : <p role="status" className="m-auto">{t('common.loading', 'Loading…')}</p>}
        </main>
        <Toaster richColors />
      </TabVisibilityProvider>
    </ThemeProvider>
  )
}
