import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, relative, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

export const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
export const contentRoot = resolve(root, 'ui/src/doc/content')
const requireUi = createRequire(resolve(root, 'ui/package.json'))

export async function loadUiPackage(name) {
  return import(pathToFileURL(requireUi.resolve(name)).href)
}

export function displayPath(path) {
  return relative(root, path).replaceAll('\\', '/')
}

export function documentFiles() {
  const args = process.argv.slice(2)
  const targets = []
  for (let index = 0; index < args.length; index++) {
    if (args[index] !== '--docs' || !args[index + 1] || args[index + 1].startsWith('--')) {
      throw new Error('Usage: node scripts/validate-docs-*.mjs [--docs <file-or-directory>]')
    }
    targets.push(resolve(root, args[++index]))
  }
  const files = new Set()
  function collect(path) {
    if (!existsSync(path)) throw new Error(`Document path does not exist: ${displayPath(path)}`)
    if (statSync(path).isDirectory()) {
      for (const entry of readdirSync(path, { withFileTypes: true })) {
        if (!entry.name.startsWith('.') && entry.name !== 'node_modules') collect(resolve(path, entry.name))
      }
    } else if (/\.md$/i.test(path)) files.add(path)
  }
  for (const target of targets.length ? targets : [contentRoot]) collect(target)
  if (!files.size) throw new Error('No Markdown documents found')
  return [...files].sort()
}

export function readDocument(path) {
  return readFileSync(path, 'utf8')
}

export function report(label, files, checks, errors) {
  for (const error of errors) console.error(error)
  console.log(`${label}: ${files} documents, ${checks} checks, ${errors.length} errors`)
  if (errors.length) process.exitCode = 1
}
