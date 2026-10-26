/** Use native English translations to keep component assertions deterministic. */
import type { ReactElement, ReactNode } from 'react'
import { render, type RenderOptions, type RenderResult } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import { createInstance, type i18n as I18n } from 'i18next'
import { initReactI18next } from 'react-i18next'

import en from '@lightrag/locales/en.json'

const createTestI18n = (): I18n => {
  const instance = createInstance()
  instance.use(initReactI18next).init({
    lng: 'en',
    fallbackLng: 'en',
    resources: { en: { translation: en } },
    interpolation: { escapeValue: false }
  })
  return instance
}

const testI18n = createTestI18n()

const Providers = ({ children }: { children: ReactNode }) => (
  <I18nextProvider i18n={testI18n}>{children}</I18nextProvider>
)

export const renderWithProviders = (
  ui: ReactElement,
  options?: Omit<RenderOptions, 'wrapper'>
): RenderResult => render(ui, { wrapper: Providers, ...options })

export { testI18n }
