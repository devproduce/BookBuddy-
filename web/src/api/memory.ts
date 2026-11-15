import { API_BASE_URL, ApiError, api, tokenStore } from './client'

export type MemoryCategory =
  | 'ABOUT_ME'
  | 'INTERESTS'
  | 'PREFERENCES'
  | 'GOALS'
  | 'EXPERIENCES'
  | 'OBSERVED_PATTERNS'
  | 'EMOTIONAL_CONTEXT'

export type MemoryClassification = 'EXPLICIT' | 'INFERRED'
export type MemorySensitivity = 'NORMAL' | 'SENSITIVE'
export type MemoryStatus = 'ACTIVE' | 'ARCHIVED' | 'EXPIRED' | 'DELETED'

export interface Memory {
  id: string
  category: MemoryCategory
  content: string
  classification: MemoryClassification
  confidence: number
  importance: number
  sensitivity: MemorySensitivity
  userVerified: boolean
  pinned: boolean
  status: MemoryStatus
  supersededBy: string | null
  sourceConversationId: string | null
  expiresAt: string | null
  createdAt: string
  updatedAt: string
  lastUsedAt: string | null
}

export interface MemoryProfile {
  text: string
  estimatedTokens: number
  tokenBudget: number
  items: Memory[]
}

export interface MemoryJob {
  jobId: string
  type: string
  status: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED'
  attempts: number
  lastError: string | null
  result: string | null
  notBefore: string | null
  createdAt: string
  finishedAt: string | null
}

export interface DeletionReport {
  books: number
  conversations: number
  messages: number
  memories: number
}

/** Display order and wording of the memory groups. */
export const MEMORY_CATEGORIES: {
  id: MemoryCategory
  label: string
  hint: string
}[] = [
  { id: 'ABOUT_ME', label: 'About me', hint: 'Who you are, in your own words.' },
  { id: 'INTERESTS', label: 'Interests', hint: 'Genres, topics and authors you enjoy.' },
  { id: 'PREFERENCES', label: 'Preferences', hint: 'How you like to read and be talked to.' },
  { id: 'GOALS', label: 'Goals', hint: 'What you are aiming for.' },
  { id: 'EXPERIENCES', label: 'Experiences', hint: 'Things you have read, done or been through.' },
  { id: 'OBSERVED_PATTERNS', label: 'Observed patterns', hint: 'Things Book Buddy noticed. Confirm or remove them.' },
  { id: 'EMOTIONAL_CONTEXT', label: 'Emotional context', hint: 'How you have been feeling lately. Fades after about two weeks.' },
]

export const categoryLabel = (c: MemoryCategory) => MEMORY_CATEGORIES.find((x) => x.id === c)?.label ?? c

export interface MemoryInput {
  category: MemoryCategory
  content: string
  pinned?: boolean
}

export interface MemoryPatch {
  category?: MemoryCategory
  content?: string
  pinned?: boolean
  sensitive?: boolean
}

export const memoryApi = {
  profile: (signal?: AbortSignal) => api.get<MemoryProfile>('/memory/profile', { signal }),

  list: (opts: { category?: MemoryCategory; status?: MemoryStatus | 'ALL'; ids?: string[] } = {}, signal?: AbortSignal) => {
    const q = new URLSearchParams()
    if (opts.category) q.set('category', opts.category)
    if (opts.status) q.set('status', opts.status)
    if (opts.ids?.length) q.set('ids', opts.ids.join(','))
    const qs = q.toString()
    return api.get<Memory[]>(`/memory${qs ? `?${qs}` : ''}`, { signal })
  },

  create: (input: MemoryInput) => api.post<Memory>('/memory', input),
  update: (id: string, patch: MemoryPatch) => api.patch<Memory>(`/memory/${encodeURIComponent(id)}`, patch),
  remove: (id: string) => api.delete(`/memory/${encodeURIComponent(id)}`),
  confirm: (id: string) => api.post<Memory>(`/memory/${encodeURIComponent(id)}/confirm`),
  clear: () => api.delete('/memory'),

  rebuild: () => api.post<MemoryJob>('/memory/rebuild'),
  rebuildStatus: (jobId: string, signal?: AbortSignal) =>
    api.get<MemoryJob>(`/memory/rebuild/${encodeURIComponent(jobId)}`, { signal }),
}

export const accountApi = {
  /** Deletes every book, conversation and memory. The account itself stays. */
  deleteAllData: () => api.delete<DeletionReport>('/account/data'),

  /** Downloads the full JSON export through an authenticated fetch (a plain link cannot send the token). */
  async downloadExport(): Promise<void> {
    const token = tokenStore.get()
    let res: Response
    try {
      res = await fetch(`${API_BASE_URL}/export`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      })
    } catch {
      throw new ApiError(0, {
        code: 'NETWORK_ERROR',
        message: "Can't reach Book Buddy right now. Check your connection and try again.",
        retryable: true,
      })
    }
    if (!res.ok) {
      throw new ApiError(res.status, {
        code: `HTTP_${res.status}`,
        message: 'The export could not be created. Please try again.',
        retryable: res.status >= 500,
      })
    }
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `bookbuddy-export-${new Date().toISOString().slice(0, 10)}.json`
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 10_000)
  },
}
