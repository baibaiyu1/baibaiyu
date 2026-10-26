import { afterEach, beforeEach, describe, expect, test } from 'vitest'
import { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { __setAxiosAdapterForTests, deleteDocuments, queryGraphs, scanNewDocuments, uploadDocument } from './lightrag'
import { managementConfig } from '../lib/platformManagement'

let requests: InternalAxiosRequestConfig[] = []
beforeEach(() => {
  requests = []
  window.__LIGHTRAG_CONFIG__ = managementConfig('?knowledgeBaseId=42')
  __setAxiosAdapterForTests(async (config: InternalAxiosRequestConfig) => {
    requests.push(config)
    return { config, status: 200, statusText: 'OK', headers: {}, data: { status: 'success', message: 'accepted' } }
  })
})
afterEach(() => {
  __setAxiosAdapterForTests(undefined)
  delete window.__LIGHTRAG_CONFIG__
})

describe('platform LightRAG API connection', () => {
  test('uploads multipart files through the platform connection without upstream credentials', async () => {
    const file = new File(['native upload'], 'example.txt', { type: 'text/plain' })
    await expect(uploadDocument(file)).resolves.toEqual({ status: 'success', message: 'accepted' })
    expect(requests).toHaveLength(1)
    expect(requests[0].baseURL).toBe('/api/knowledge/config/42/lightrag')
    expect(requests[0].url).toBe('/documents/upload')
    expect(requests[0].data.get('file')).toBe(file)
    expect(requests[0].headers.get('X-GMD-LightRAG')).toBe('management')
    expect(requests[0].headers.has('Authorization')).toBe(false)
    expect(requests[0].headers.has('X-API-Key')).toBe(false)
  })

  test('preserves the delete body and graph parameters', async () => {
    await deleteDocuments(['doc-1'], true, true)
    expect(JSON.parse(requests[0].data)).toEqual({ doc_ids: ['doc-1'], delete_file: true, delete_llm_cache: true })
    await queryGraphs('Entity / A', 3, 500)
    expect(requests[1].method).toBe('get')
    expect(requests[1].url).toBe('/graphs?label=Entity%20%2F%20A&max_depth=3&max_nodes=500')
  })

  test.each([401, 403, 503])('does not repeat a document write on HTTP %s', async status => {
    __setAxiosAdapterForTests(async (config: InternalAxiosRequestConfig) => {
      requests.push(config)
      throw new AxiosError('upstream failure', 'ERR_BAD_RESPONSE', config, undefined,
        { config, status, statusText: 'failed', data: {}, headers: {} })
    })
    await expect(scanNewDocuments()).rejects.toThrow()
    expect(requests).toHaveLength(1)
  })

  test('rejects platform authentication envelopes and missing connection config', async () => {
    __setAxiosAdapterForTests(async (config: InternalAxiosRequestConfig) => {
      requests.push(config)
      return { config, status: 200, statusText: 'OK', data: { code: 401 }, headers: {} }
    })
    await expect(scanNewDocuments()).rejects.toThrow()
    delete window.__LIGHTRAG_CONFIG__
    await expect(scanNewDocuments()).rejects.toThrow()
    expect(requests).toHaveLength(1)
  })
})
