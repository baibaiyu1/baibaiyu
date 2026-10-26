import { Marked } from 'marked'
import * as workspaceApi from '@/api/workspace'
import * as attachApi from '@/api/attach'
import type { ChatArtifactVO, ChatImage, ChatImageSource, ToolResultImage, UploadedFileItem } from '@/types'

const IMAGE_EXTENSIONS = new Set(['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'svg', 'ico'])
const markdown = new Marked()

interface CachedImage {
  promise: Promise<string>
  users: number
  url?: string
  timer?: ReturnType<typeof setTimeout>
  disposed?: boolean
}
const loadedImages = new Map<string, CachedImage>()

/** 按鉴权来源共享在用的图片，跨消息移动时复用，最后一个使用方离开后释放。 */
export function acquireImageUrl(source: ChatImageSource): { url: Promise<string>; release: () => void } {
  if (source.kind === 'url') return { url: Promise.resolve(source.url), release() {} }
  const key = JSON.stringify(source)
  let cached = loadedImages.get(key)
  if (!cached) {
    const entry: CachedImage = { users: 0, promise: Promise.resolve('') }
    entry.promise = imageBlob(source).then(blob => {
      if (!blob?.size || blob.type.includes('application/json')) throw new Error('图片不可用')
      entry.url = URL.createObjectURL(blob)
      if (entry.disposed) URL.revokeObjectURL(entry.url)
      return entry.url
    }).catch(error => {
      if (loadedImages.get(key) === entry) loadedImages.delete(key)
      throw error
    })
    loadedImages.set(key, entry)
    cached = entry
  }
  const entry = cached
  clearTimeout(entry.timer)
  entry.users++
  return { url: entry.promise, release() {
    if (--entry.users) return
    entry.timer = setTimeout(() => {
      if (entry.users) return
      if (loadedImages.get(key) === entry) loadedImages.delete(key)
      entry.disposed = true
      if (entry.url) URL.revokeObjectURL(entry.url)
    }, 0)
  } }
}

export function isImageFile(extension?: string | null): boolean {
  return IMAGE_EXTENSIONS.has((extension ?? '').toLowerCase())
}

export function toolImage(image: ToolResultImage, sessionId: string | null, index: number): ChatImage {
  return {
    key: image.filePath ?? `error:${index}:${image.fileName}`,
    source: image.filePath && sessionId ? { kind: 'workspace', sessionId, path: image.filePath } : null,
    fileName: image.fileName, extension: image.extension, sizeBytes: image.sizeBytes,
    sourceTool: image.sourceTool, error: image.error,
    aliases: [image.filePath, image.sourcePath, image.fileName].filter((value): value is string => !!value),
  }
}

export function artifactImage(artifact: ChatArtifactVO, sessionId: string | null): ChatImage {
  return {
    key: artifact.filePath,
    source: { kind: 'workspace', sessionId: sessionId ?? String(artifact.sessionId), path: artifact.filePath },
    fileName: artifact.fileName, extension: artifact.extension, sizeBytes: artifact.sizeBytes,
    sourceTool: artifact.sourceTool, aliases: [artifact.filePath, artifact.fileName],
  }
}

export function attachmentImages(files: UploadedFileItem[]): ChatImage[] {
  return files.filter(file => isImageFile(file.extension || file.name.split('.').pop())).map(file => ({
    key: `attachment:${file.id}`, source: { kind: 'attachment', id: file.id },
    fileName: file.name, extension: file.extension || file.name.split('.').pop() || '', sizeBytes: null,
  }))
}

function referencePath(value: string): string {
  let path = value.trim()
  try { path = decodeURIComponent(path) } catch { /* Preserve malformed legacy references. */ }
  return path.replace(/\\/g, '/').replace(/^\.\//, '')
}

/** 只把已知引用转换成工作区读取，任意外部地址不会携带登录凭据。 */
export function resolveChatImage(href: string, images: ChatImage[] = [], title = ''): ChatImage | null {
  const path = referencePath(href)
  const matches = images.filter(image => image.aliases?.some(alias => referencePath(alias) === path))
  if (matches.length === 1) return matches[0]!
  if (/^(https?:|data:image\/|blob:)/i.test(href) || (href.startsWith('/') && !href.startsWith('//'))) {
    return { key: `url:${href}`, source: { kind: 'url', url: href }, fileName: title || '图片',
      extension: null, sizeBytes: null }
  }
  return null
}

/** 使用 Markdown 解析器识别引用，代码示例中的图片语法不会影响真实图片的位置。 */
export function referencedImageKeys(content: string, images: ChatImage[]): Set<string> {
  const keys = new Set<string>()
  markdown.walkTokens(markdown.lexer(content), token => {
    if (token.type !== 'image') return
    const image = resolveChatImage(token.href, images, token.text)
    if (image) keys.add(image.key)
  })
  return keys
}

export async function imageBlob(source: ChatImageSource): Promise<Blob> {
  if (source.kind === 'url') {
    const response = await fetch(source.url, { credentials: 'omit' })
    if (!response.ok) throw new Error('图片下载失败')
    return response.blob()
  }
  const response = source.kind === 'workspace'
    ? await workspaceApi.download(source.sessionId, source.path)
    : await attachApi.download(source.id)
  return response.data as Blob
}

export async function downloadImage(source: ChatImageSource, name: string) {
  const blob = await imageBlob(source)
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = name
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
