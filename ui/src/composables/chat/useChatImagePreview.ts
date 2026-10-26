import { inject, provide, ref, type InjectionKey } from 'vue'
import type { ChatImage } from '@/types'

interface ImagePreview {
  open: (image: ChatImage, images?: ChatImage[]) => void
}
const previewKey: InjectionKey<ImagePreview> = Symbol('chat-image-preview')

/** 预览归属于整个消息列表，图片从工具处移到正文时不会关闭弹窗。 */
export function provideChatImagePreview() {
  const visible = ref(false)
  const images = ref<ChatImage[]>([])
  const currentIndex = ref(0)
  provide(previewKey, { open(image, gallery = [image]) {
    images.value = gallery
    currentIndex.value = Math.max(0, gallery.findIndex(item => item.key === image.key))
    visible.value = true
  } })
  return { visible, images, currentIndex }
}

export function useChatImagePreview() { return inject(previewKey, null) }
