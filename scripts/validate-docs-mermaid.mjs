import { displayPath, documentFiles, loadUiPackage, readDocument, report } from './docs-validation.mjs'

const { Window } = await loadUiPackage('happy-dom')
const browserWindow = new Window()
globalThis.window = browserWindow
globalThis.document = browserWindow.document
const { default: mermaid } = await loadUiPackage('mermaid')
mermaid.initialize({ startOnLoad: false, securityLevel: 'strict' })
const { lexer, walkTokens } = await loadUiPackage('marked')
const files = documentFiles()
const errors = []
let checks = 0

for (const file of files) {
  const diagrams = []
  const source = readDocument(file)
  walkTokens(lexer(source), token => {
    if (token.type === 'code' && token.lang?.trim() === 'mermaid') diagrams.push(token)
  })
  for (const diagram of diagrams) {
    checks++
    const line = source.slice(0, source.indexOf(diagram.raw)).split('\n').length
    try { await mermaid.parse(diagram.text) }
    catch (error) { errors.push(`${displayPath(file)}:${line}: ${error.message}`) }
  }
}

report('Mermaid diagrams', files.length, checks, errors)
browserWindow.happyDOM.abort()
