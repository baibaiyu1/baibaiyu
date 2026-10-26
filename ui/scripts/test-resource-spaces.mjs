import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { transformWithEsbuild } from 'vite'
import * as vue from 'vue'
import * as pinia from 'pinia'

let moduleVersion = 0
const response = records => ({ data: { data: { records, total: records.length } } })
const deferred = () => {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}

async function load(path, context) {
  globalThis.__resourceSpaceTest = { ...context, vue, pinia }
  const source = readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8')
  const result = await transformWithEsbuild(source, path, { loader: 'ts', target: 'esnext' })
  const code = result.code
    .replace(/import\s+\{\s*defineStore\s*\}\s+from\s+['"]pinia['"];?/g, 'const { defineStore } = globalThis.__resourceSpaceTest.pinia;')
    .replace(/import\s+\{([^}]+)\}\s+from\s+['"]vue['"];?/g, 'const {$1} = globalThis.__resourceSpaceTest.vue;')
    .replace(/import\s+\{\s*useResourceSpaceStore\s*\}\s+from\s+['"][^'"]+['"];?/g, 'const useResourceSpaceStore = () => globalThis.__resourceSpaceTest.space;')
    .replace(/import\s+\{\s*listSpaces\s*\}\s+from\s+['"][^'"]+['"];?/g, 'const listSpaces = (...args) => globalThis.__resourceSpaceTest.listSpaces(...args);')
    .replace(/import\s+\*\s+as\s+(\w+)\s+from\s+['"][^'"]+['"];?/g, 'const $1 = globalThis.__resourceSpaceTest.api;')
    .replace(/import\s+\{\s*message\s*\}\s+from\s+['"]ant-design-vue['"];?/g, 'const message = { success() {}, error() {} };')
    .replace(/import\s+\{\s*mergeResourceVisibility\s*\}\s+from\s+['"][^'"]+['"];?/g, 'const mergeResourceVisibility = async () => {};')
    .replace(/import\s+\{\s*McpActivationStatus\s*\}\s+from\s+['"]@\/types['"];?/g, 'const McpActivationStatus = {};')
  return import(`data:text/javascript;base64,${Buffer.from(`${code}\n// ${moduleVersion++}`).toString('base64')}`)
}

test('space discovery ignores a previous login response after reset', async () => {
  pinia.setActivePinia(pinia.createPinia())
  const request = deferred()
  const { useResourceSpaceStore } = await load('stores/modules/resourceSpace.ts', { listSpaces: () => request.promise })
  const store = useResourceSpaceStore()
  const loading = store.load()
  store.reset()
  request.resolve({ data: { data: [{ key: 'department:old', name: '上个账号的部门', canCreate: true }] } })
  await loading
  assert.deepEqual(store.spaces, [])
  assert.equal(store.selectedSpace, 'personal')
  assert.equal(store.loaded, false)
})

test('platform viewers cannot create there and aggregate creation targets personal space', async () => {
  pinia.setActivePinia(pinia.createPinia())
  const { useResourceSpaceStore } = await load('stores/modules/resourceSpace.ts', {
    listSpaces: async () => ({ data: { data: [
      { key: 'personal', name: '个人空间', canCreate: true },
      { key: 'platform', name: '平台公共', canCreate: false }
    ] } })
  })
  const store = useResourceSpaceStore()
  await store.load()
  store.selectedSpace = 'platform'
  assert.equal(store.canCreate, false)
  const { resourceCreationHeaders } = await load('utils/resourceSpace.ts', { space: store })
  assert.throws(resourceCreationHeaders, /没有创建权限/)
  store.selectedSpace = 'all'
  assert.deepEqual(resourceCreationHeaders(), { 'X-Resource-Space': 'personal' })
})

for (const name of ['agent', 'tool', 'skill', 'knowledge', 'mcp']) {
  test(`${name}: changing space during a request cannot restore the previous space's records`, async () => {
    pinia.setActivePinia(pinia.createPinia())
    const pending = []
    const space = vue.reactive({ selectedSpace: 'personal' })
    const api = { page(query) { const request = deferred(); pending.push({ query, ...request }); return request.promise } }
    const exports = await load(`stores/modules/${name}.ts`, { space, api })
    const store = exports[`use${name[0].toUpperCase()}${name.slice(1)}Store`]()
    const first = store.fetchPage(1)
    assert.equal(pending[0].query.space, 'personal')
    space.selectedSpace = 'department:2'
    const second = store.resetAndFetch()
    assert.equal(pending[1].query.space, 'department:2')
    pending[1].resolve(response([{ id: 'department-resource' }]))
    await second
    assert.deepEqual(store.list.map(item => item.id), ['department-resource'])
    pending[0].resolve(response([{ id: 'old-personal-resource' }]))
    await first
    assert.deepEqual(store.list.map(item => item.id), ['department-resource'])
    assert.equal(store.loading, false)
  })
}
