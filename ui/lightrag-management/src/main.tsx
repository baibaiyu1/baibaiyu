import { managementConfig } from './lib/platformManagement'
// Configure the connection before native modules create their HTTP client and stores.
window.__LIGHTRAG_CONFIG__ = managementConfig(window.location.search)
void import('./mount')
