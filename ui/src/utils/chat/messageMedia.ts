import type { ChatArtifactVO, ChatImage, DisplayMessage, KnowledgeCitationReference } from '@/types'
import { artifactImage, isImageFile, referencedImageKeys, toolImage } from './image'
import { parseToolResult } from './toolResult'
import { citationLookup, citedReferenceIds } from './citation'

export interface MessageMedia {
  images: ChatImage[]
  files: ChatArtifactVO[]
  references: ChatImage[]
  citations: KnowledgeCitationReference[]
  citationReferences: KnowledgeCitationReference[]
  deferKnowledgeImages: boolean
}

/** 知识库候选只供正文引用；其他工具的图片和文件仍跟随来源消息。 */
export function messageMedia(messages: DisplayMessage[], sessionId: string | null, runActive = false): Map<string, MessageMedia> {
  const media = new Map<string, MessageMedia>()
  let turn: DisplayMessage[] = []
  function collect(active = false) {
    const pending = active || turn.some(message => message.isStreaming)
    const available = new Map<string, ChatImage>()
    const owners = new Map<string, string>()
    const referenced = new Map<string, Set<string>>()
    const files = new Map<string, string>()
    const sources = new Map<string, KnowledgeCitationReference>()
    let citationNumber = 0
    let lastAnswer: string | undefined
    for (const message of turn) {
      const result = message.role === 'tool' ? parseToolResult(message.content) : null
      const entry: MessageMedia = { images: [], files: [], references: [], citations: [], citationReferences: [],
        deferKnowledgeImages: pending }
      for (const citation of Array.isArray(result?.citations) ? result.citations : []) {
        if (!citation || typeof citation.knowledgeId !== 'string' || typeof citation.filePath !== 'string' || !citation.filePath) continue
        const key = `${citation.knowledgeId}:${citation.filePath.replace(/\\/g, '/')}`
        const previous = sources.get(key)
        if (previous) {
          if (citation.content && !previous.content.includes(citation.content)) previous.content = `${previous.content}\n\n${citation.content}`.trim().slice(0, 6000)
        } else sources.set(key, { ...citation, content: citation.content || '', number: 0, cited: false,
          anchorId: `citation-${turn[0]?.id}-${sources.size + 1}` })
      }
      const artifacts = [...new Map([...(message.artifacts ?? []), ...(result?.artifacts ?? [])]
        .map(artifact => [artifact.filePath, { ...artifact, sessionId: artifact.sessionId || sessionId || '',
          id: artifact.id ?? artifact.filePath }])).values()]
      const snapshotSources = new Set((result?.images ?? []).filter(image => image.filePath && !image.error).map(image => image.sourcePath))
      for (const artifact of artifacts) {
        if (snapshotSources.has(artifact.filePath)) continue
        if (isImageFile(artifact.extension)) entry.images.push(artifactImage({ ...artifact,
          sourceTool: artifact.sourceTool ?? result?.name ?? null }, sessionId))
        else {
          const previous = files.get(artifact.filePath)
          if (previous) media.get(previous)!.files = media.get(previous)!.files.filter(file => file.filePath !== artifact.filePath)
          entry.files.push(artifact)
          files.set(artifact.filePath, message.id)
        }
      }
      for (const [index, image] of (result?.images ?? []).entries()) {
        entry.images.push(toolImage({ ...image, sourceTool: result!.name }, sessionId, index))
      }
      for (const image of entry.images) {
        const previous = owners.get(image.key)
        if (previous && previous !== message.id) media.get(previous)!.images = media.get(previous)!.images.filter(item => item.key !== image.key)
        available.set(image.key, image)
        owners.set(image.key, message.id)
      }
      entry.images = [...new Map(entry.images.map(image => [image.key, image])).values()]
      entry.references = [...available.values()]
      if (message.role === 'assistant') {
        const references = [...sources.values()]
        if (!pending) entry.citationReferences = references
        const lookup = citationLookup(references)
        for (const id of citedReferenceIds(message.content)) {
          const source = lookup.get(id)
          if (source && !source.cited) { source.cited = true; source.number = ++citationNumber }
        }
        if (message.content.trim()) lastAnswer = message.id
        for (const key of referencedImageKeys(message.content, entry.references)) {
          const owner = owners.get(key)
          if (!owner) continue
          const keys = referenced.get(owner) ?? new Set<string>()
          keys.add(key)
          referenced.set(owner, keys)
        }
      }
      media.set(message.id, entry)
    }
    for (const source of sources.values()) if (!source.number) source.number = ++citationNumber
    const cited = [...sources.values()].filter(source => source.cited)
    // 检索命中不等于回答引用；整轮结束后只展示正文实际使用的文档。
    const cards = pending ? [] : cited.sort((a, b) => a.number - b.number)
    const destination = lastAnswer
    if (destination && media.has(destination)) media.get(destination)!.citations = cards
    for (const message of turn) {
      const entry = media.get(message.id)!
      entry.images = entry.images.filter(image => image.sourceTool !== 'retrieve_knowledge'
        && !referenced.get(message.id)?.has(image.key))
    }
  }
  for (const message of messages) {
    if (message.role === 'user' && turn.length) { collect(); turn = [] }
    turn.push(message)
  }
  collect(runActive)
  return media
}
