/**
 * 描述：图片渲染处理器
 *
 * 提供增强的图片渲染功能：
 * - 懒加载
 * - 点击预览
 * - 图片标题（figcaption）
 * - 拦截无效地址（模型会凭空编造相对路径，如 ![快照](snapshot)）
 *
 **/

import type { Tokens } from 'marked'
import { escapeHtml } from '@/utils/chat/markdown'

/**
 * 判断图片地址是否可渲染。
 *
 * 模型常把工具返回的图片用 markdown 再"贴"一次，地址却是凭空的相对路径
 * （例如 ![快照](snapshot)、![](xxx_snapshot.jpg)）。这类地址会被浏览器按当前站点
 * 解析成 SPA 路由，最终渲染成一张没有任何线索的裂图。
 * 这里只放行带协议的地址、data/blob 以及站点绝对路径。
 */
function isRenderableImageSrc(href: string): boolean {
  const src = (href || '').trim()
  if (!src) return false
  if (/^(https?:|data:|blob:)/i.test(src)) return true
  // 站点绝对路径（/assets/a.png）；裸相对路径（snapshot、a.jpg）一律不放行
  return src.startsWith('/') && !src.startsWith('//')
}

/**
 * 图片渲染处理器
 *
 * @param token 图片 Token
 * @returns 渲染后的 HTML
 */
export function imageHandler(token: Tokens.Image): string {
  const { href, title, text } = token
  const titleAttr = title ? ` title="${escapeHtml(title)}"` : ''
  const altAttr = text ? ` alt="${escapeHtml(text)}"` : ''

  // 无效地址不渲染成图片，退化为一行提示
  if (!isRenderableImageSrc(href)) {
    const label = text ? escapeHtml(text) : '图片'
    return `<span class="md-image-invalid" data-image-ref="${escapeHtml(href)}" data-image-alt="${escapeHtml(text || '')}"${titleAttr}>[${label}：地址无效，未渲染]</span>`
  }

  return `<figure class="md-figure">
    <img src="${escapeHtml(href)}"${altAttr}${titleAttr} loading="lazy" class="md-image" data-md-action="preview-image" />
    ${text ? `<figcaption class="md-figcaption">${escapeHtml(text)}</figcaption>` : ''}
  </figure>`
}
