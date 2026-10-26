import type { ManagementConfig } from './platformManagement'
declare global {
  interface Window { __LIGHTRAG_CONFIG__?: ManagementConfig }
}
export function getRuntimeApiPrefix(): string | undefined {
  return window.__LIGHTRAG_CONFIG__?.apiPrefix
}
