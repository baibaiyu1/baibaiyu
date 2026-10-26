import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import './i18n'
import PlatformManagementApp from './features/PlatformManagementApp'
createRoot(document.getElementById('root')!).render(<StrictMode><PlatformManagementApp /></StrictMode>)
