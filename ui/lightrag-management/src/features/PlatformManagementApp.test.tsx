import { afterEach, beforeEach, describe, expect, test } from 'vitest'
import { cleanup, screen, waitFor } from '@testing-library/react'
import { __setAxiosAdapterForTests, __resetPaginatedDocumentRequestsForTests, scanNewDocuments } from '@lightrag/api/lightrag'
import { useSettingsStore } from '@lightrag/stores/settings'
import { useBackendState } from '@lightrag/stores/state'
import { renderWithProviders } from '@lightrag/test/render'
import PlatformManagementApp from './PlatformManagementApp'

const initialSettings = useSettingsStore.getState()
const initialBackend = useBackendState.getState()
const calls: { url: string; authorization: unknown; key: unknown; marker: unknown }[] = []
let expired = false

beforeEach(() => {
  calls.length = 0
  expired = false
  window.__LIGHTRAG_CONFIG__ = { apiPrefix: '/api/knowledge/config/42/lightrag', platformManaged: true, managementView: 'documents' }
  localStorage.setItem('LIGHTRAG-API-TOKEN', 'unrelated-native-token')
  useSettingsStore.setState({ currentTab: 'knowledge-graph' })
  __setAxiosAdapterForTests(async (config: any) => {
    calls.push({ url: config.url, authorization: config.headers.get('Authorization'), key: config.headers.get('X-API-Key'), marker: config.headers.get('X-GMD-LightRAG') })
    let data: unknown = {}
    if (expired) data = { code: 401, msg: 'Platform login expired' }
    else if (config.url === '/health') data = { status: 'healthy', pipeline_busy: false }
    else if (config.url === '/documents/scan') data = { status: 'scanning_started', message: 'queued', track_id: 'track-1' }
    else if (config.url === '/documents/paginated') data = {
      documents: [], status_counts: {},
      pagination: { page: 1, page_size: 10, total_count: 0, total_pages: 0, has_next: false, has_prev: false }
    }
    return { data, status: 200, statusText: 'OK', headers: {}, config }
  })
})

afterEach(() => {
  cleanup()
  __resetPaginatedDocumentRequestsForTests()
  __setAxiosAdapterForTests(undefined)
  useSettingsStore.setState(initialSettings)
  useBackendState.setState(initialBackend)
  localStorage.removeItem('LIGHTRAG-API-TOKEN')
  delete window.__LIGHTRAG_CONFIG__
})

describe('platform management entry', () => {
  test('renders the native document manager without the standalone navigation or stored native credentials', async () => {
    renderWithProviders(<PlatformManagementApp />)
    expect(await screen.findByRole('button', { name: /Upload/i }, { timeout: 10000 })).toBeVisible()
    await waitFor(() => expect(calls.some(call => call.url === '/documents/paginated')).toBe(true))
    expect(screen.queryAllByRole('tab')).toHaveLength(0)
    expect(screen.queryAllByRole('link')).toHaveLength(0)
    expect(calls.every(call => !call.authorization && !call.key && call.marker === 'management')).toBe(true)
    expect(useSettingsStore.getState().currentTab).toBe('documents')
    expect(localStorage.getItem('LIGHTRAG-API-TOKEN')).toBe('unrelated-native-token')
  }, 15000)

  test('a direct visit without host configuration cannot open document management', () => {
    delete window.__LIGHTRAG_CONFIG__
    renderWithProviders(<PlatformManagementApp />)
    expect(screen.getByRole('alert')).toBeVisible()
    expect(calls).toHaveLength(0)
    expect(screen.queryAllByRole('button', { name: /Upload/i })).toHaveLength(0)
  })

  test('an expired platform login shows a connection error without refreshing a LightRAG guest identity', async () => {
    expired = true
    renderWithProviders(<PlatformManagementApp />)
    expect(await screen.findByRole('alert')).toBeVisible()
    expect(calls.map(call => call.url)).toEqual(['/health'])
    expect(localStorage.getItem('LIGHTRAG-API-TOKEN')).toBe('unrelated-native-token')
  })

  test('document actions use the management header and the native API response', async () => {
    await expect(scanNewDocuments()).resolves.toEqual({ status: 'scanning_started', message: 'queued', track_id: 'track-1' })
    expect(calls).toEqual([{ url: '/documents/scan', authorization: undefined, key: undefined, marker: 'management' }])
  })
})
