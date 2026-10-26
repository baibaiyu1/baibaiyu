import { existsSync } from 'node:fs'
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path'
import { contentRoot, displayPath, documentFiles, loadUiPackage, readDocument, report, root } from './docs-validation.mjs'

const { lexer, walkTokens } = await loadUiPackage('marked')
const routes = new Set([...readDocument(resolve(root, 'ui/src/doc/router.ts')).matchAll(/path:\s*['"]([^'"]+)['"]/g)].map(match => match[1]))
const files = documentFiles()
const errors = []
let checks = 0

for (const file of files) {
  const source = readDocument(file)
  const inManual = !relative(contentRoot, file).startsWith(`..${sep}`) && !isAbsolute(relative(contentRoot, file))
  function check(href, image = false) {
    if (!href || /^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(href)) return
    checks++
    const location = `${displayPath(file)}: ${href}`
    if (href.startsWith('#/')) {
      const route = href.slice(1).split(/[?#]/, 1)[0]
      if (!routes.has(route)) errors.push(`${location} (unknown document route)`)
      return
    }
    if (href.startsWith('#')) return
    const [encodedPath] = href.split(/[?#]/, 1)
    let path
    try { path = decodeURIComponent(encodedPath) } catch { errors.push(`${location} (invalid URL encoding)`); return }
    if (inManual && !image && /\.md$/i.test(path)) {
      errors.push(`${location} (cross-page links in the manual must use hash routes)`)
      return
    }
    const target = path.startsWith('/') ? resolve(root, `.${path}`) : resolve(dirname(file), path)
    if (!existsSync(target)) errors.push(`${location} (missing ${displayPath(target)})`)
  }
  walkTokens(lexer(source), token => {
    if (token.type === 'link' || token.type === 'image') check(token.href, token.type === 'image')
    if (token.type === 'html') {
      for (const match of token.raw.matchAll(/\b(href|src)\s*=\s*["']([^"']+)["']/gi)) check(match[2], match[1].toLowerCase() === 'src')
    }
  })
}

report('Document links', files.length, checks, errors)
