/**
 * 描述：代码块渲染处理器
 *
 * 提供增强的代码块渲染功能，包括：
 * - 语法高亮
 * - 代码复制按钮
 * - 全屏按钮
 * - HTML 代码预览（Tab 切换）
 *
 **/

import hljs from 'highlight.js'
import type { Tokens } from 'marked'
import {
  escapeHtml,
  isCompleteHtml,
  encodeToBase64,
  generateUniqueId,
} from '@/utils/chat/markdown'

/**
 * 代码块渲染选项
 */
export interface CodeRenderOptions {
  /**
   * 代码内容
   */
  text: string

  /**
   * 语言
   */
  lang?: string

  /**
   * 是否转义 HTML
   * @default true
   */
  escape?: boolean
}

/**
 * 渲染代码高亮
 *
 * @param code 代码内容
 * @param lang 语言
 * @returns 高亮后的 HTML
 */
function highlightCode(code: string, lang?: string): string {
  if (lang && hljs.getLanguage(lang)) {
    return hljs.highlight(code, { language: lang }).value
  }
  return hljs.highlightAuto(code).value
}

/**
 * 生成代码块按钮组
 *
 * 交互靠 data-md-action 交给 dom-utils.ts 的点击委托处理。
 * **不要改回内联 onclick** —— 那会迫使 DOMPurify 白名单长期放行 onclick，
 * 而 AI 生成的 markdown 走同一条净化管线，等于开放任意内联事件处理器（主文档 v-html，无沙箱）。
 *
 * @returns 按钮组 HTML
 */
function generateButtonGroup(): string {
  const copyBtn = `<button class="md-code-btn md-code-copy-btn" data-md-action="copy-code">复制</button>`
  const fullscreenBtn = `<button class="md-code-btn" title="全屏" data-md-action="toggle-fullscreen">全屏</button>`
  return `<div class="md-code-btn-group">${copyBtn}${fullscreenBtn}</div>`
}

/**
 * HTML 预览 iframe 的沙箱策略
 *
 * ⚠️ 安全红线：绝不能加回 allow-same-origin。
 *
 * 该 iframe 的 srcdoc 由 AI 生成的 HTML 注入（见 utils/dom-utils.ts 的 decodeFromBase64 赋值）。
 * allow-scripts 与 allow-same-origin 同时开启时，预览页与主应用**同源**，沙箱等同于不存在：
 * 页面脚本可以直接读取 localStorage 中的登录令牌，并以当前用户身份调用内部接口。
 * 触发路径是提示词注入——智能体只要读到被污染的网页/知识库内容即可中招。
 *
 * 只保留 allow-scripts：预览页拿到不透明源（opaque origin），自身脚本照常运行、
 * 外链资源照常加载，但读不到任何凭据、也带不出凭据调内部接口。
 */
const IFRAME_SANDBOX = 'allow-scripts'

/**
 * 渲染 HTML 代码块（带预览 Tab）
 *
 * @param text 代码内容
 * @param lang 语言
 * @param highlighted 高亮后的代码
 * @param showPreviewFirst 是否优先显示预览
 * @returns HTML 字符串
 */
function renderHtmlCodeBlock(
  text: string,
  lang: string,
  highlighted: string,
  showPreviewFirst: boolean = false
): string {
  const id = generateUniqueId('code')
  const rawHtmlBase64 = encodeToBase64(text)
  const btnGroup = generateButtonGroup()

  // 根据 showPreviewFirst 决定默认显示的 Tab
  const codeTabClass = showPreviewFirst ? 'md-code-tab' : 'md-code-tab active'
  const previewTabClass = showPreviewFirst ? 'md-code-tab active' : 'md-code-tab'
  const codeViewClass = showPreviewFirst ? 'md-code-view hidden' : 'md-code-view'
  const previewClass = showPreviewFirst ? 'md-code-preview' : 'md-code-preview hidden'

  // 当默认显示预览时，标记 iframe 需要自动加载（绕过 DOMPurify 对 srcdoc 的清理）
  const autoPreviewAttr = showPreviewFirst ? ' data-auto-preview="true"' : ''

  return `<div class="md-code-block md-code-block-html" id="${id}" data-raw-html="${rawHtmlBase64}">
    <div class="md-code-header">
      <div class="md-code-tabs">
        <button class="${codeTabClass}" data-md-action="toggle-html-view" data-md-mode="code">代码</button>
        <button class="${previewTabClass}" data-md-action="toggle-html-view" data-md-mode="preview">视图</button>
      </div>
      ${btnGroup}
    </div>
    <div class="${codeViewClass}">
      <pre><code class="hljs language-${escapeHtml(lang)}">${highlighted}</code></pre>
    </div>
    <div class="${previewClass}">
      <iframe class="md-code-iframe" sandbox="${IFRAME_SANDBOX}"${autoPreviewAttr}></iframe>
    </div>
  </div>`
}

/**
 * 渲染普通代码块
 *
 * @param text 代码内容
 * @param lang 语言
 * @param highlighted 高亮后的代码
 * @returns HTML 字符串
 */
function renderNormalCodeBlock(text: string, lang: string, highlighted: string): string {
  const id = generateUniqueId('code')
  const btnGroup = generateButtonGroup()

  return `<div class="md-code-block" id="${id}">
    <div class="md-code-header">
      <span class="md-code-lang">${escapeHtml(lang)}</span>
      ${btnGroup}
    </div>
    <pre><code class="hljs language-${escapeHtml(lang)}">${highlighted}</code></pre>
  </div>`
}

/**
 * 代码块渲染处理器
 *
 * @param token 代码块 Token
 * @returns 渲染后的 HTML
 */
export function codeHandler(token: Tokens.Code): string {
  const { text, lang } = token
  const language = lang || 'text'

  // mermaid代码处理
  if (lang === 'mermaid') {
    return `<pre class="mermaid">${escapeHtml(text)}</pre>`
  }

  // vep代码处理：跳过语法高亮，保持 JSON 完整性
  if (lang === 'vep') {
    return `<pre class="vep">${escapeHtml(text)}</pre>`
  }

  const highlighted = highlightCode(text, lang)

  // 判断是否为完整的 HTML 代码
  const isHtml = (lang === 'html' || lang === 'htm') && isCompleteHtml(text)

  if (isHtml) {
    // 完整HTML优先显示预览效果
    return renderHtmlCodeBlock(text, language, highlighted, false)
  }

  return renderNormalCodeBlock(text, language, highlighted)
}
