import { afterEach } from 'vitest'
import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { managementConfig } from '../lib/platformManagement'

window.__LIGHTRAG_CONFIG__ = managementConfig(window.location.search)
afterEach(cleanup)
