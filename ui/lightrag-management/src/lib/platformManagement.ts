export type ManagementConfig = { apiPrefix: string; platformManaged: true; managementView: 'documents' | 'graph' }

/** Only a saved resource ID selects a connection; addresses and credentials stay on Console. */
export function managementConfig(search: string): ManagementConfig | undefined {
  const params = new URLSearchParams(search)
  const id = params.get('knowledgeBaseId') ?? ''
  if (!/^[1-9]\d{0,18}$/.test(id)) return undefined
  return { apiPrefix: '/api/knowledge/config/' + id + '/lightrag', platformManaged: true,
    managementView: params.get('view') === 'graph' ? 'graph' : 'documents' }
}
export function isPlatformManaged(): boolean {
  return typeof window !== 'undefined' && window.__LIGHTRAG_CONFIG__?.platformManaged === true
}
export function managementView(): 'documents' | 'graph' {
  return window.__LIGHTRAG_CONFIG__?.managementView === 'graph' ? 'graph' : 'documents'
}
export function managementStorageKey(name: string): string {
  return 'gmd:' + (window.__LIGHTRAG_CONFIG__?.apiPrefix ?? 'lightrag') + ':' + name
}
export function notifyManagementHost(type: 'ready' | 'error'): void {
  if (isPlatformManaged() && window.parent !== window) {
    window.parent.postMessage({ type: 'lightrag-management-' + type }, window.location.origin)
  }
}
