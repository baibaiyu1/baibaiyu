import { createApp, h, ref } from 'vue'
import WorkspaceFilePreview from '@/components/workspace/WorkspaceFilePreview.vue'
import type { ChatImage } from '@/types'

/** 为直接使用 Markdown HTML 的调用方保留入口，预览统一复用工作区组件。 */
export const ImagePreviewApi = {
  open(options: { url: string; title?: string }): void {
    const container = document.createElement('div')
    document.body.appendChild(container)
    const image: ChatImage = { key: options.url, source: { kind: 'url', url: options.url },
      fileName: options.title || '图片', extension: null, sizeBytes: null }
    const visible = ref(true)
    const app = createApp({ render: () => h(WorkspaceFilePreview, {
      visible: visible.value, image, fileNode: null, sessionId: null,
      'onUpdate:visible': (value: boolean) => {
        visible.value = value
        if (!value) queueMicrotask(() => { app.unmount(); container.remove() })
      },
    }) })
    app.mount(container)
  },
}
