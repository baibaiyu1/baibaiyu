import { afterEach, beforeEach, describe, expect, test } from 'vitest'
import { cleanup, screen } from '@testing-library/react'
import { RawGraph, useGraphStore } from '@lightrag/stores/graph'
import { useSettingsStore } from '@lightrag/stores/settings'
import { useBackendState } from '@lightrag/stores/state'
import { renderWithProviders } from '@lightrag/test/render'
import PropertiesView from './PropertiesView'

const originalSettings = useSettingsStore.getState()
const originalBackend = useBackendState.getState()

beforeEach(() => {
  const graph = new RawGraph()
  graph.nodes = [{ id: 'entity', labels: ['Entity'], properties: { entity_id: 'entity', description: 'Native evidence', entity_type: 'equipment' },
    x: 0, y: 0, size: 5, color: '#000', degree: 0 }]
  graph.nodeIdMap = { entity: 0 }
  graph.edges = [{ id: 'relation', dynamicId: 'relation', source: 'entity', target: 'target', properties: { description: 'Related equipment', keywords: 'links' } }]
  graph.edgeDynamicIdMap = { relation: 0 }
  useSettingsStore.setState({ queryLabel: 'entity' })
  useBackendState.setState({ pipelineBusy: false })
  useGraphStore.setState({ rawGraph: graph, graphDataFetchAttempted: true, selectedNode: 'entity', focusedNode: null, selectedEdge: null, focusedEdge: null })
})

afterEach(() => {
  cleanup()
  useGraphStore.getState().reset()
  useSettingsStore.setState(originalSettings)
  useBackendState.setState(originalBackend)
})

describe('read-only graph properties', () => {
  test('keeps entity details and navigation while removing the native property editors', () => {
    const { container } = renderWithProviders(<PropertiesView readOnly />)
    expect(screen.getByText('Native evidence')).toBeVisible()
    expect(screen.getAllByRole('button')).toHaveLength(2)
    expect(container.querySelectorAll('.lucide-pencil')).toHaveLength(0)
  })

  test('graph properties are read only by default', () => {
    const { container } = renderWithProviders(<PropertiesView />)
    expect(screen.getByText('Native evidence')).toBeVisible()
    expect(container.querySelectorAll('.lucide-pencil')).toHaveLength(0)
  })

  test('relation details also stay readable without editing', () => {
    useGraphStore.setState({ selectedNode: null, selectedEdge: 'relation' })
    const { container } = renderWithProviders(<PropertiesView readOnly />)
    expect(screen.getByText('Related equipment')).toBeVisible()
    expect(container.querySelectorAll('.lucide-pencil')).toHaveLength(0)
  })
})
