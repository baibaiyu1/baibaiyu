/**
 * 工作空间文件预览：类型判定
 *
 * 把扩展名收敛成「渲染器种类」，供 WorkspaceFileViewer 分发。
 * 与 WorkspaceFilePreview.vue 的 mediaType 判定保持同一份扩展名口径，
 * 但**多分出了 code / html / markdown / json / table 这几类**——
 * 旧预览器把它们一律当纯文本 `<pre>` 渲染，这正是本次升级要解决的问题。
 */

/** 渲染器种类 */
export type FileKind =
  | 'image'
  | 'audio'
  | 'video'
  | 'html'
  | 'markdown'
  | 'json'
  | 'table'
  | 'code'
  | 'text'
  | 'unsupported'

/** 图片 */
const IMAGE_EXTS = ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'svg', 'ico']

/** 音频 */
const AUDIO_EXTS = ['mp3', 'wav', 'ogg', 'm4a', 'flac', 'aac', 'wma', 'mpeg']

/** 视频 */
const VIDEO_EXTS = ['mp4', 'webm', 'mov', 'mkv', 'avi', 'flv', 'm3u8']

/** 结构化数据，走表格渲染 */
const TABLE_EXTS = ['csv', 'tsv']

/**
 * 有明确语法高亮的源码扩展名
 * 不含 html / md / json —— 它们各有专门的渲染器
 */
const CODE_EXTS = [
  'java', 'py', 'js', 'mjs', 'cjs', 'ts', 'tsx', 'jsx', 'go', 'rs', 'c', 'cpp', 'cc', 'h', 'hpp',
  'cs', 'rb', 'php', 'swift', 'kt', 'scala', 'clj', 'lua', 'r', 'sql', 'sh', 'bash', 'zsh',
  'bat', 'cmd', 'ps1', 'psm1', 'graphql', 'gql', 'proto', 'dart', 'pl', 'vue', 'svelte', 'astro',
  'css', 'scss', 'less', 'sass', 'xml', 'yaml', 'yml', 'toml', 'ini', 'cfg', 'conf',
  'properties', 'env', 'editorconfig', 'dockerfile', 'makefile', 'cmake', 'gradle'
]

/** 其余按纯文本渲染 */
const TEXT_EXTS = ['txt', 'log', 'rst', 'tex', 'asciidoc']

/** 扩展名 → hljs 语言名。hljs 语言名与扩展名不一致的在此映射 */
const CODE_LANG_ALIAS: Record<string, string> = {
  py: 'python',
  js: 'javascript',
  mjs: 'javascript',
  cjs: 'javascript',
  ts: 'typescript',
  tsx: 'typescript',
  jsx: 'javascript',
  rb: 'ruby',
  rs: 'rust',
  kt: 'kotlin',
  sh: 'bash',
  zsh: 'bash',
  ps1: 'powershell',
  psm1: 'powershell',
  bat: 'dos',
  cmd: 'dos',
  h: 'c',
  hpp: 'cpp',
  cc: 'cpp',
  yml: 'yaml',
  gql: 'graphql',
  dockerfile: 'dockerfile',
  makefile: 'makefile',
  conf: 'ini',
  cfg: 'ini',
  properties: 'properties',
  env: 'bash'
}

/**
 * 归一化扩展名
 *
 * @param extension 原始扩展名（可带点号，可大写）
 * @returns 小写、不含点号的扩展名
 */
export function normalizeExtension(extension?: string): string {
  return (extension ?? '').replace(/^\./, '').toLowerCase()
}

/**
 * 判定文件应使用哪种渲染器
 *
 * @param extension 文件扩展名
 * @returns 渲染器种类
 */
export function resolveFileKind(extension?: string): FileKind {
  const ext = normalizeExtension(extension)
  if (!ext) return 'unsupported'

  if (IMAGE_EXTS.includes(ext)) return 'image'
  if (AUDIO_EXTS.includes(ext)) return 'audio'
  if (VIDEO_EXTS.includes(ext)) return 'video'
  if (ext === 'html' || ext === 'htm') return 'html'
  if (ext === 'md' || ext === 'markdown') return 'markdown'
  if (ext === 'json') return 'json'
  if (TABLE_EXTS.includes(ext)) return 'table'
  if (CODE_EXTS.includes(ext)) return 'code'
  if (TEXT_EXTS.includes(ext)) return 'text'

  return 'unsupported'
}

/**
 * 取扩展名对应的 hljs 语言名
 *
 * @param extension 文件扩展名
 * @returns hljs 语言名；未知则返回空串（由调用方决定回退策略）
 */
export function resolveCodeLanguage(extension?: string): string {
  const ext = normalizeExtension(extension)
  if (!ext) return ''
  return CODE_LANG_ALIAS[ext] ?? ext
}

/**
 * 该种类是否属于「媒体」——媒体仍走全屏沉浸式 Modal，不进右栏
 *
 * @param kind 渲染器种类
 */
export function isMediaKind(kind: FileKind): boolean {
  return kind === 'image' || kind === 'audio' || kind === 'video'
}

/**
 * 该种类是否以文本方式读取内容
 *
 * @param kind 渲染器种类
 */
export function isTextBasedKind(kind: FileKind): boolean {
  return kind !== 'image' && kind !== 'audio' && kind !== 'video' && kind !== 'unsupported'
}
