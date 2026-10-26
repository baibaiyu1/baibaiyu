import axios, { type AxiosError } from 'axios'
import { backendBaseUrl, documentFetchTimeoutMessage, healthCheckTimeout, pipelineStatusTimeout, popularLabelsDefaultLimit, searchLabelsDefaultLimit } from '@lightrag/lib/constants'
import type { SupportedFileTypes } from '@lightrag/lib/fileTypes'
import { errorMessage } from '@lightrag/lib/utils'
import { isPlatformManaged, notifyManagementHost } from '@lightrag/lib/platformManagement'
import { AuthenticationRequiredError } from '@lightrag/api/errors'

const axiosInstance = axios.create({ baseURL: backendBaseUrl, headers: { 'Content-Type': 'application/json' } })
const defaultAdapter = axiosInstance.defaults.adapter
axiosInstance.interceptors.request.use(config => {
  if (!isPlatformManaged()) throw new AuthenticationRequiredError()
  config.headers.delete('Authorization')
  config.headers.delete('X-API-Key')
  config.headers.set('X-GMD-LightRAG', 'management')
  return config
})
axiosInstance.interceptors.response.use(response => {
  if (response.data?.code === 401 || response.data?.code === 403) {
    notifyManagementHost('error')
    throw new AuthenticationRequiredError()
  }
  return response
}, (error: AxiosError) => {
  if (error.response?.status === 401 || error.response?.status === 403) notifyManagementHost('error')
  return Promise.reject(error.response ? toHttpRequestError(error.response.status, error.response.statusText, error.response.data, error.config?.url) : error)
})

export type LightragNodeType = {
  id: string
  labels: string[]
  properties: Record<string, any>
}

export type LightragEdgeType = {
  id: string
  source: string
  target: string
  type: string
  properties: Record<string, any>
}

export type LightragGraphType = {
  nodes: LightragNodeType[]
  edges: LightragEdgeType[]
}

export type LightragQueueStatus = {
  available: boolean
  queue_name?: string
  max_async?: number
  max_queue_size?: number
  queued?: number
  running?: number
  in_flight?: number
  worker_count?: number
  initialized?: boolean
  submitted_total?: number
  completed_total?: number
  failed_total?: number
  cancelled_total?: number
  rejected_total?: number
}

export type LightragRoleLLMConfig = {
  binding?: string | null
  model?: string | null
  host?: string | null
  max_async?: number
  timeout?: number
  has_model_kwargs?: boolean
  metadata?: Record<string, any>
}

export type LightragStatus = {
  status: 'healthy'
  working_directory: string
  input_directory: string
  configuration: {
    llm_binding: string
    llm_binding_host: string
    llm_model: string
    embedding_binding: string
    embedding_binding_host: string
    embedding_model: string
    kv_storage: string
    doc_status_storage: string
    graph_storage: string
    vector_storage: string
    workspace?: string
    storage_workspaces?: {
      kv_storage?: string | null
      doc_status_storage?: string | null
      graph_storage?: string | null
      vector_storage?: string | null
    }
    max_graph_nodes?: string
    enable_rerank?: boolean
    rerank_binding?: string | null
    rerank_model?: string | null
    rerank_binding_host?: string | null
    rerank_max_async?: number
    rerank_timeout?: number
    summary_language: string
    force_llm_summary_on_merge: boolean
    max_parallel_insert: number
    max_async: number
    llm_timeout?: number
    embedding_func_max_async: number
    embedding_batch_num: number
    embedding_timeout?: number
    cosine_threshold: number
    min_rerank_score: number
    related_chunk_number: number
    role_llm_config?: Record<string, LightragRoleLLMConfig>
    vlm_process_enable?: boolean
    parser_routing?: string
    mineru?: {
      endpoint: string
      api_mode: 'official' | 'local' | null
      options: {
        language?: string
        enable_table?: boolean
        enable_formula?: boolean
        model_version?: string
        is_ocr?: boolean
        local_backend?: string
        local_parse_method?: string
        local_image_analysis?: boolean
      }
    }
    docling?: {
      endpoint: string
      options: {
        do_ocr?: boolean
        force_ocr?: boolean
        ocr_engine?: string
        ocr_lang?: string
        do_formula_enrichment?: boolean
      }
    }
  }
  update_status?: Record<string, any>
  core_version?: string
  api_version?: string
  webui_available?: boolean
  /** Whether /docs, /redoc and /openapi.json are served. Absent on older
   * backends, which always expose them (see apiDocsCapability in state.ts). */
  api_docs_available?: boolean
  auth_mode?: 'enabled' | 'disabled'
  server_mode?: 'uvicorn' | 'gunicorn'
  workers?: number
  pipeline_busy: boolean
  pipeline_active?: boolean
  pipeline_scanning?: boolean
  pipeline_destructive_busy?: boolean
  pipeline_pending_enqueues?: number
  llm_queue_status?: Record<string, LightragQueueStatus>
  embedding_queue_status?: LightragQueueStatus
  rerank_queue_status?: LightragQueueStatus
  keyed_locks?: {
    process_id: number
    cleanup_performed: {
      mp_cleaned: number
      async_cleaned: number
    }
    current_status: {
      total_mp_locks: number
      pending_mp_cleanup: number
      total_async_locks: number
      pending_async_cleanup: number
    }
  }
  webui_title?: string
  webui_description?: string
  /** Whether answers are labelled as AI-generated in the query UIs. */
  ai_content_notice_enabled?: boolean
}

export type DocActionResponse = {
  status: 'success' | 'partial_success' | 'failure'
  message: string
  track_id?: string
}

export type ScanResponse = {
  status: 'scanning_started' | 'scanning_skipped_pipeline_busy'
  message: string
  track_id: string
}

export type ReprocessFailedResponse = {
  status: 'reprocessing_started'
  message: string
  track_id: string
}

export type DeleteDocResponse = {
  status: 'deletion_started' | 'busy' | 'not_allowed'
  message: string
  doc_id: string
}

export type DocStatus =
  | 'pending'
  | 'parsing'
  | 'analyzing'
  | 'processing'
  | 'preprocessed'
  | 'processed'
  | 'failed'

export type DocStatusResponse = {
  id: string
  content_summary: string
  content_length: number
  status: DocStatus
  created_at: string
  updated_at: string
  track_id?: string
  chunks_count?: number
  error_msg?: string
  metadata?: Record<string, any>
  file_path: string
}

export type TrackStatusResponse = {
  track_id: string
  documents: DocStatusResponse[]
  total_count: number
  status_summary: Record<string, number>
}

export type DocumentsRequest = {
  status_filter?: DocStatus | null
  status_filters?: DocStatus[] | null
  page: number
  page_size: number
  sort_field: 'created_at' | 'updated_at' | 'id' | 'file_path'
  sort_direction: 'asc' | 'desc'
}

export type PaginationInfo = {
  page: number
  page_size: number
  total_count: number
  total_pages: number
  has_next: boolean
  has_prev: boolean
}

export type PaginatedDocsResponse = {
  documents: DocStatusResponse[]
  pagination: PaginationInfo
  status_counts: Record<string, number>
}

export type PipelineStatusResponse = {
  busy: boolean
  job_name: string
  job_start?: string
  docs: number
  batchs: number
  cur_batch: number
  cancellation_requested?: boolean
  latest_message: string
  history_messages?: string[]
  update_status?: Record<string, any>
}

export type HttpRequestError = Error & { status?: number }
export const toHttpRequestError = (
  status: number,
  statusText: string,
  data: unknown,
  url?: string
): HttpRequestError => {
  const error = new Error(
    `${status} ${statusText}\n${JSON.stringify(data)}\n${url}`
  ) as HttpRequestError
  error.status = status
  return error
}

export const queryGraphs = async (
  label: string,
  maxDepth: number,
  maxNodes: number
): Promise<LightragGraphType> => {
  const response = await axiosInstance.get(`/graphs?label=${encodeURIComponent(label)}&max_depth=${maxDepth}&max_nodes=${maxNodes}`)
  return response.data
}

export const getGraphLabels = async (): Promise<string[]> => {
  const response = await axiosInstance.get('/graph/label/list')
  return response.data
}

export const getPopularLabels = async (limit: number = popularLabelsDefaultLimit): Promise<string[]> => {
  const response = await axiosInstance.get(`/graph/label/popular?limit=${limit}`)
  return response.data
}

export const searchLabels = async (query: string, limit: number = searchLabelsDefaultLimit): Promise<string[]> => {
  const response = await axiosInstance.get(`/graph/label/search?q=${encodeURIComponent(query)}&limit=${limit}`)
  return response.data
}

export const checkHealth = async (): Promise<
  LightragStatus | { status: 'error'; message: string }
> => {
  try {
    // Explicit timeout: the axios instance sets none, so a backend whose
    // event loop is blocked would leave this request hanging and the health
    // state would neither succeed nor fail. Kept below healthCheckInterval
    // so probes cannot pile up.
    const response = await axiosInstance.get('/health', {
      timeout: healthCheckTimeout * 1000
    })
    return response.data
  } catch (error) {
    return {
      status: 'error',
      message: errorMessage(error)
    }
  }
}

export const getSupportedFileTypes = async (signal?: AbortSignal): Promise<SupportedFileTypes> => {
  const response = await axiosInstance.get('/documents/supported_file_types', { signal })
  return response.data
}

export const scanNewDocuments = async (): Promise<ScanResponse> => {
  const response = await axiosInstance.post('/documents/scan')
  return response.data
}

export const reprocessFailedDocuments = async (): Promise<ReprocessFailedResponse> => {
  const response = await axiosInstance.post('/documents/reprocess_failed')
  return response.data
}

export const insertText = async (text: string): Promise<DocActionResponse> => {
  const response = await axiosInstance.post('/documents/text', { text })
  return response.data
}

export const insertTexts = async (texts: string[]): Promise<DocActionResponse> => {
  const response = await axiosInstance.post('/documents/texts', { texts })
  return response.data
}

export const uploadDocument = async (
  file: File,
  onUploadProgress?: (percentCompleted: number) => void
): Promise<DocActionResponse> => {
  const formData = new FormData()
  formData.append('file', file)

  const response = await axiosInstance.post('/documents/upload', formData, {
    headers: {
      'Content-Type': 'multipart/form-data'
    },
    // prettier-ignore
    onUploadProgress:
      onUploadProgress !== undefined
        ? (progressEvent) => {
          const percentCompleted = Math.round((progressEvent.loaded * 100) / progressEvent.total!)
          onUploadProgress(percentCompleted)
        }
        : undefined
  })
  return response.data
}

export const clearDocuments = async (
  clearLlmCache: boolean = false
): Promise<DocActionResponse> => {
  const response = await axiosInstance.delete('/documents', {
    params: { clear_llm_cache: clearLlmCache }
  })
  return response.data
}

export const deleteDocuments = async (
  docIds: string[],
  deleteFile: boolean = false,
  deleteLLMCache: boolean = false
): Promise<DeleteDocResponse> => {
  const response = await axiosInstance.delete('/documents/delete_document', {
    data: { doc_ids: docIds, delete_file: deleteFile, delete_llm_cache: deleteLLMCache }
  })
  return response.data
}

export const getPipelineStatus = async (): Promise<PipelineStatusResponse> => {
  const response = await axiosInstance.get('/documents/pipeline_status', {
    timeout: pipelineStatusTimeout * 1000
  })
  return response.data
}

export const cancelPipeline = async (): Promise<{
  status: 'cancellation_requested' | 'not_busy'
  message: string
}> => {
  const response = await axiosInstance.post('/documents/cancel_pipeline')
  return response.data
}

export const getTrackStatus = async (trackId: string): Promise<TrackStatusResponse> => {
  const response = await axiosInstance.get(`/documents/track_status/${encodeURIComponent(trackId)}`)
  return response.data
}

type InFlightPaginatedDocumentRequest = {
  controller: AbortController
  promise: Promise<PaginatedDocsResponse>
  subscriberCount: number
}

const getPaginatedDocumentsRequestKey = (request: DocumentsRequest): string =>
  JSON.stringify(request)

// Deduplicate in-flight paginated document requests with identical parameters.
// This prevents duplicate backend calls caused by overlapping timers/effects or
// React StrictMode double-mount behavior in development.
const inFlightPaginatedDocumentRequests = new Map<
  string,
  InFlightPaginatedDocumentRequest
>()

const releasePaginatedDocumentSubscriber = (
  requestKey: string,
  requestEntry: InFlightPaginatedDocumentRequest,
  abortIfLastSubscriber: boolean
): void => {
  requestEntry.subscriberCount = Math.max(0, requestEntry.subscriberCount - 1)

  if (requestEntry.subscriberCount !== 0) {
    return
  }

  if (inFlightPaginatedDocumentRequests.get(requestKey) === requestEntry) {
    inFlightPaginatedDocumentRequests.delete(requestKey)
  }

  if (abortIfLastSubscriber) {
    requestEntry.controller.abort()
  }
}

const subscribeToPaginatedDocumentsRequest = (
  request: DocumentsRequest
): {
  requestKey: string
  requestEntry: InFlightPaginatedDocumentRequest
  release: (abortIfLastSubscriber: boolean) => void
} => {
  const requestKey = getPaginatedDocumentsRequestKey(request)
  let requestEntry = inFlightPaginatedDocumentRequests.get(requestKey)

  if (!requestEntry) {
    const controller = new AbortController()
    requestEntry = {
      controller,
      subscriberCount: 0,
      promise: paginatedDocumentsPost(request, controller)
        .finally(() => {
          if (inFlightPaginatedDocumentRequests.get(requestKey) === requestEntry) {
            inFlightPaginatedDocumentRequests.delete(requestKey)
          }
        })
    }
    inFlightPaginatedDocumentRequests.set(requestKey, requestEntry)
  }

  requestEntry.subscriberCount += 1

  let released = false
  const release = (abortIfLastSubscriber: boolean): void => {
    if (released) {
      return
    }
    released = true
    releasePaginatedDocumentSubscriber(
      requestKey,
      requestEntry,
      abortIfLastSubscriber
    )
  }

  return {
    requestKey,
    requestEntry,
    release
  }
}

const defaultPaginatedDocumentsPost = async (
  request: DocumentsRequest,
  controller: AbortController
): Promise<PaginatedDocsResponse> => {
  const response = await axiosInstance.post('/documents/paginated', request, {
    signal: controller.signal
  })
  return response.data
}

let paginatedDocumentsPost = defaultPaginatedDocumentsPost

export const abortDocumentsPaginated = (request: DocumentsRequest): void => {
  const requestKey = getPaginatedDocumentsRequestKey(request)
  const inFlightRequest = inFlightPaginatedDocumentRequests.get(requestKey)

  if (!inFlightRequest) {
    return
  }

  inFlightPaginatedDocumentRequests.delete(requestKey)
  inFlightRequest.controller.abort()
}

export const __resetPaginatedDocumentRequestsForTests = (): void => {
  for (const { controller } of inFlightPaginatedDocumentRequests.values()) {
    controller.abort()
  }
  inFlightPaginatedDocumentRequests.clear()
  paginatedDocumentsPost = defaultPaginatedDocumentsPost
}

export const __setPaginatedDocumentsPostForTests = (
  post: typeof defaultPaginatedDocumentsPost
): void => {
  paginatedDocumentsPost = post
}

/**
 * Swap the axios adapter so a test can drive a response (or an HTTP failure)
 * through the real request/response interceptors. Pass undefined to restore.
 */
export const __setAxiosAdapterForTests = (adapter: any): void => {
  axiosInstance.defaults.adapter = adapter ?? defaultAdapter
}

/**
 * Get documents with pagination support
 * @param request The pagination request parameters
 * @returns Promise with paginated documents response
 */
export const getDocumentsPaginated = async (request: DocumentsRequest): Promise<PaginatedDocsResponse> => {
  const { requestEntry, release } = subscribeToPaginatedDocumentsRequest(request)

  try {
    return await requestEntry.promise
  } finally {
    release(false)
  }
}

export const getDocumentsPaginatedWithTimeout = (
  request: DocumentsRequest,
  timeoutMs: number = 30000,
  errorMsg: string = documentFetchTimeoutMessage
): Promise<PaginatedDocsResponse> => {
  const { requestEntry, release } = subscribeToPaginatedDocumentsRequest(request)

  return new Promise<PaginatedDocsResponse>((resolve, reject) => {
    let timedOut = false
    const timeoutId = setTimeout(() => {
      timedOut = true
      release(true)
      reject(new Error(errorMsg))
    }, timeoutMs)

    requestEntry.promise
      .then(response => {
        if (timedOut) {
          return
        }
        clearTimeout(timeoutId)
        release(false)
        resolve(response)
      })
      .catch(error => {
        if (timedOut) {
          return
        }
        clearTimeout(timeoutId)
        release(false)
        reject(error)
      })
  })
}
