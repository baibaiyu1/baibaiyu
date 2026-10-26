import { useResourceSpaceStore } from '@/stores/modules/resourceSpace'

/** 仅创建/导入请求携带目标空间；资源选择器始终查询全部可访问依赖。 */
export function resourceCreationHeaders() {
  const store = useResourceSpaceStore()
  if (!store.canCreate) throw new Error('当前空间没有创建权限')
  return { 'X-Resource-Space': store.creationSpace }
}
