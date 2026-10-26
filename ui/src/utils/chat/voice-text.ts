export type ChatSpeechEvent =
  | { type: 'start' | 'finish' | 'cancel'; sessionId: string; runId: string; replay?: boolean }
  | { type: 'text'; sessionId: string; runId: string; messageId: string; text: string; delta: string; final: boolean; replay: boolean }

/** Incremental Markdown reader. Unfinished links/fences stay buffered across SSE chunks. */
export class VoiceTextBuffer {
  private source = ''
  private cursor = 0
  private clause = ''
  private fence = ''
  private lineStart = true
  private first = true
  private mutedThrough = 0

  update(text: string, final = false, audible = true): string[] {
    // A rewritten message cannot retract audio already played. Wait for the next message.
    if (!text.startsWith(this.source)) return []
    this.source = text
    if (!audible) this.mutedThrough = text.length
    const chunks: string[] = []
    const emit = () => {
      const value = this.clause.replace(/\s+/g, ' ').trim()
      this.clause = ''
      if (value && /[\p{L}\p{N}]/u.test(value)) {
        if (audible) chunks.push(value)
        this.first = false
      }
    }
    const append = (value: string) => {
      for (const char of value) {
        this.clause += char
        if (/[。！？!?，,；;\n]/.test(char) || this.clause.length >= (this.first ? 12 : 28)) emit()
      }
    }
    while (this.cursor < text.length) {
      const tail = text.slice(this.cursor)
      if (!final && tail === '!') break
      if (this.fence) {
        const end = tail.indexOf(this.fence)
        if (end < 0) {
          // Retain a possible partial closing fence for the next update.
          this.cursor = final ? text.length : Math.max(this.cursor, text.length - this.fence.length + 1)
          break
        }
        this.cursor += end + this.fence.length
        this.fence = ''
        continue
      }
      if (tail[0] === '`' || (this.lineStart && tail[0] === '~')) {
        const marker = tail.match(/^(`+|~+)/)![0]
        if (!final && marker.length === tail.length) break
        if (marker.length >= 3) {
          emit()
          this.fence = marker
          this.cursor += marker.length
          continue
        }
        if (marker[0] === '`') {
          const end = text.indexOf(marker, this.cursor + marker.length)
          if (end < 0 && !final) break
          this.cursor = end < 0 ? text.length : end + marker.length
          continue
        }
      }
      if (tail[0] === '[' || tail.startsWith('![')) {
        const open = tail[0] === '!' ? 2 : 1
        const close = tail.indexOf(']', open)
        if (close < 0 || (close === tail.length - 1 && !final)) break
        let end = close + 1
        if (tail[end] === '(') {
          let depth = 1
          end++
          while (end < tail.length && depth) {
            if (tail[end] === '(') depth++
            if (tail[end] === ')') depth--
            end++
          }
          if (depth && !final) break
        }
        if (open === 1 && this.cursor >= this.mutedThrough) append(tail.slice(open, close).replace(/[*_~]/g, ''))
        this.cursor += end
        this.lineStart = false
        continue
      }
      if (/^(?:https?:\/\/|www\.)/i.test(tail)) {
        const end = tail.search(/[\s。！？，；<>]/)
        if (end < 0 && !final) break
        this.cursor += end < 0 ? tail.length : end
        continue
      }
      if (!final && /^(?:h|ht|htt|http|https|https?:|https?:\/|w|ww|www)$/i.test(tail)) break
      if (tail[0] === '<' && /^<(?:[a-zA-Z/!]|$)/.test(tail)) {
        const end = tail.indexOf('>')
        if (end < 0 && !final) break
        this.cursor += end < 0 ? tail.length : end + 1
        continue
      }
      if (this.lineStart) {
        if (!final && /^[ \t]*\d+(?:[.)][ \t]*)?$/.test(tail)) break
        const prefix = tail.match(/^(?:[ \t]*[#>*+\-]+[ \t]*|[ \t]*\d+[.)][ \t]+)/)?.[0]
        if (prefix) { this.cursor += prefix.length; continue }
      }
      const char = text[this.cursor++]!
      if (!/[*_~|]/.test(char)) append(char)
      this.lineStart = char === '\n' || (this.lineStart && /\s/.test(char))
    }
    if (final) emit()
    // A replay/offline prefix must never be carried into the next audible clause.
    if (!audible) this.clause = ''
    return chunks
  }

  finish(audible = true): string[] { return this.update(this.source, true, audible) }
}
