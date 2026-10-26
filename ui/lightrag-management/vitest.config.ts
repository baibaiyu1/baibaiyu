import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  resolve: { alias: { '@lightrag': fileURLToPath(new URL('./src', import.meta.url)) } },
  esbuild: { jsx: 'automatic' },
  test: {
    include: ['lightrag-management/src/**/*.test.{ts,tsx}'],
    environment: 'happy-dom',
    environmentOptions: { happyDOM: { url: 'http://localhost/web/lightrag-management/index.html?knowledgeBaseId=42' } },
    setupFiles: ['lightrag-management/src/test/setup.ts']
  }
})
