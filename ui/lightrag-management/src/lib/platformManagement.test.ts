import { afterEach, describe, expect, test } from 'vitest'
import { managementConfig, managementStorageKey } from './platformManagement'
import { SearchHistoryManager } from '../utils/SearchHistoryManager'

afterEach(() => {
  delete window.__LIGHTRAG_CONFIG__
  localStorage.clear()
})

describe('platform-owned management entry', () => {
  test('uses the saved knowledge base connection and ignores supplied upstream URLs or keys', () => {
    expect(managementConfig('?knowledgeBaseId=42&view=graph&apiPrefix=https://other.test&apiKey=secret')).toEqual({
      apiPrefix: '/api/knowledge/config/42/lightrag', platformManaged: true, managementView: 'graph'
    })
    expect(managementConfig('?knowledgeBaseId=42&view=retrieval')?.managementView).toBe('documents')
  })

  test.each(['', '0', '-1', '1/graph', '1?query', '1.2', '12345678901234567890'])('rejects invalid resource ID %s', id => {
    expect(managementConfig('?knowledgeBaseId=' + encodeURIComponent(id))).toBeUndefined()
  })

  test('isolates preferences and label history between knowledge bases without persisting entity names', () => {
    window.__LIGHTRAG_CONFIG__ = managementConfig('?knowledgeBaseId=42')
    const firstKey = managementStorageKey('preferences')
    SearchHistoryManager.addToHistory('Private entity')
    expect(SearchHistoryManager.getHistoryLabels()).toContain('Private entity')
    expect(localStorage.length).toBe(0)
    window.__LIGHTRAG_CONFIG__ = managementConfig('?knowledgeBaseId=43')
    expect(managementStorageKey('preferences')).not.toBe(firstKey)
    expect(SearchHistoryManager.getHistoryLabels()).not.toContain('Private entity')
    SearchHistoryManager.clearHistory()
    window.__LIGHTRAG_CONFIG__ = managementConfig('?knowledgeBaseId=42')
    SearchHistoryManager.clearHistory()
  })
})
