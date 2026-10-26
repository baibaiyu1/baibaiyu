<script lang="ts">
import { defineComponent, h, type PropType, type VNodeChild } from 'vue'
import ChatImageCard from '@/components/chat/ChatImage.vue'
import { resolveChatImage } from '@/utils/chat/image'
import { citationTextParts } from '@/utils/chat/citation'
import type { ChatImage, KnowledgeCitationReference } from '@/types'

/** 净化后的 HTML 转为 Vue 节点，让正文里的图片在流式更新时保留组件和缩略图。 */
export default defineComponent({
  props: {
    html: { type: String, required: true },
    images: { type: Array as PropType<ChatImage[]>, default: () => [] },
    citations: { type: Array as PropType<KnowledgeCitationReference[]>, default: () => [] },
    deferKnowledgeImages: Boolean,
  },
  setup(props) {
    return () => {
      const template = document.createElement('template')
      template.innerHTML = props.html
      const occurrences = new Map<string, number>()
      const previewImages = [...new Map([...template.content.querySelectorAll('img, [data-image-ref]')].map(element => {
        const image = resolveChatImage(element.getAttribute('data-image-ref') || element.getAttribute('src') || '', props.images,
          element.getAttribute('data-image-alt') || element.getAttribute('alt') || '')
        return image?.source && !image.error && !(props.deferKnowledgeImages && image.sourceTool === 'retrieve_knowledge')
          ? [image.key, image] as const : null
      }).filter((entry): entry is readonly [string, ChatImage] => !!entry)).values()]
      function openCitation(event: MouseEvent, citation: KnowledgeCitationReference) {
        const turn = (event.currentTarget as HTMLElement).closest('.chat-message-turn')
        const card = turn?.querySelector<HTMLDetailsElement>(`#${CSS.escape(citation.anchorId)}`)
        if (!card) return
        card.open = true
        card.scrollIntoView({ block: 'nearest', behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })
        card.querySelector('summary')?.focus({ preventScroll: true })
      }
      function isImageOnly(child: Node): boolean {
        if (!(child instanceof Element)) return false
        if (child.matches('img, figure.md-figure, [data-image-ref]')) return true
        return child.tagName === 'P' && !!child.querySelector('img, [data-image-ref]')
          && [...child.childNodes].every(item => item.nodeType === Node.TEXT_NODE ? !item.textContent?.trim()
            : item instanceof Element && item.matches('img, figure.md-figure, [data-image-ref]'))
      }
      function children(parent: ParentNode, plain = false): VNodeChild[] {
        const result: VNodeChild[] = []
        let row: Node[] = []
        const flush = () => {
          if (row.length) result.push(h('div', { class: 'md-image-row' }, row.map(item => node(item, plain))))
          row = []
        }
        for (const child of parent.childNodes) {
          if (isImageOnly(child)) row.push(child)
          else if (row.length && (child.nodeType === Node.TEXT_NODE && !child.textContent?.trim()
            || child instanceof Element && child.tagName === 'P' && !child.childNodes.length)) continue
          else { flush(); result.push(node(child, plain)) }
        }
        flush()
        return result
      }
      function node(child: Node, plain = false): VNodeChild {
        if (child.nodeType === Node.TEXT_NODE) {
          const text = child.textContent ?? ''
          if (plain) return text
          return citationTextParts(text, props.citations).map(part => typeof part === 'string' ? part
            : h('sup', { class: 'md-citation' }, h('button', { type: 'button', class: 'md-citation-link',
              title: part.fileName, 'aria-label': `引用 ${part.number}：${part.fileName}`,
              onClick: (event: MouseEvent) => openCitation(event, part) }, String(part.number))))
        }
        if (!(child instanceof Element)) return null
        const imageElement = child.matches('figure.md-figure') ? child.querySelector('img') : child
        if (imageElement?.matches('img, [data-image-ref]')) {
          const href = imageElement.getAttribute('data-image-ref') || imageElement.getAttribute('src') || ''
          const title = imageElement.getAttribute('data-image-alt') || imageElement.getAttribute('alt') || ''
          const image = resolveChatImage(href, props.images, title)
          if (props.deferKnowledgeImages && image?.sourceTool === 'retrieve_knowledge') return null
          if (image) {
            const occurrence = occurrences.get(image.key) ?? 0
            occurrences.set(image.key, occurrence + 1)
            return h(ChatImageCard, { key: `${image.key}:${occurrence}`, image, inline: true, previewImages })
          }
          return h('span', { class: 'md-image-invalid' }, `[${title || '图片'}：地址无效，未渲染]`)
        }
        const attributes = Object.fromEntries([...child.attributes].map(attribute => [attribute.name, attribute.value]))
        const imageParagraph = child.tagName === 'P' && child.querySelector('img, [data-image-ref]')
        const literal = plain || child.matches('code, pre, a, .katex, .mermaid')
        return h(imageParagraph ? 'div' : child.tagName.toLowerCase(), attributes, children(child, literal))
      }
      return h('div', { class: 'markdown-html' }, children(template.content))
    }
  },
})
</script>

<style>
.markdown-html .md-citation { display: inline; margin-left: 2px; vertical-align: super; line-height: 0; }
.markdown-html .md-citation-link { min-width: 16px; padding: 2px 4px; border: 0; border-radius: 4px; background: #edf4ff; color: #1769d2; font: 600 10px/1.25 system-ui; cursor: pointer; }
.markdown-html .md-citation-link:hover { background: #d9eaff; }
.markdown-html .md-citation-link:focus-visible { outline: 2px solid #1677ff; outline-offset: 2px; }
.markdown-html .md-image-row { display: flex; flex-wrap: wrap; align-items: flex-start; gap: 8px; margin: 8px 0 12px; }
.markdown-html .md-image-row > div { display: contents; }
.markdown-html .md-image-row .chat-image { margin: 0; }
</style>
