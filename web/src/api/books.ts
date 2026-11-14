import { api } from './client'

export type BookStatus =
  | 'QUEUED'
  | 'EXTRACTING'
  | 'CHUNKING'
  | 'EMBEDDING'
  | 'COMPLETED'
  | 'FAILED'
  | 'NEEDS_OCR'

export interface Book {
  id: string
  title: string
  author: string | null
  pageCount: number | null
  language: string | null
  languageConfidence: number | null
  contentHash: string
  fileSizeBytes: number
  status: BookStatus
  extractionQuality: number | null
  failureReason: string | null
  chunkCount: number
  chunkingVersion: string | null
  embeddingVersion: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateBookInput {
  title: string
  author?: string
  /** 64-char hex SHA-256 */
  contentHash: string
  fileSizeBytes: number
  pageCount?: number
}

export interface UpdateBookInput {
  title?: string
  author?: string
}

export type IngestionStage =
  | 'QUEUED'
  | 'EXTRACTING'
  | 'CHUNKING'
  | 'EMBEDDING'
  | 'COMPLETED'
  | 'FAILED'
  | 'NEEDS_OCR'
  | 'CANCELLED'

export interface BookProcessingStatus {
  bookId: string
  status: BookStatus
  stage: IngestionStage
  /** 0-100 */
  progress: number
  message: string | null
  failureReason: string | null
  chunkCount: number
  pageCount: number | null
  jobId: string | null
  /** True while the PDF is stored server-side waiting to be processed. */
  fileUploaded: boolean
  updatedAt: string | null
}

export interface BookChunk {
  id: string
  chunkIndex: number
  content: string
  pageStart: number | null
  pageEnd: number | null
  chapterTitle: string | null
  language: string | null
  tokenCount: number
  sectionType: 'BODY' | 'FRONT_MATTER' | 'BACK_MATTER' | null
}

export interface ChunkPage {
  items: BookChunk[]
  page: number
  size: number
  totalPages: number
  totalElements: number
}

export interface Citation {
  chunkId: string
  bookId: string
  bookTitle: string
  pageStart: number | null
  pageEnd: number | null
}

export interface AskResult {
  answer: string
  /** citations[n - 1] is the passage referenced as [n] in the answer. */
  citations: Citation[]
  usedChunks: number
}

const bookPath = (id: string) => `/books/${encodeURIComponent(id)}`

export const booksApi = {
  list: (signal?: AbortSignal) => api.get<Book[]>('/books', { signal }),
  get: (id: string) => api.get<Book>(bookPath(id)),
  create: (input: CreateBookInput) => api.post<Book>('/books', input),
  update: (id: string, input: UpdateBookInput) => api.patch<Book>(bookPath(id), input),
  remove: (id: string) => api.delete(bookPath(id)),

  /** Uploads the PDF; the server keeps it only until processing finishes. */
  uploadFile: (id: string, file: File) => {
    const form = new FormData()
    form.append('file', file, file.name)
    return api.upload<BookProcessingStatus>(`${bookPath(id)}/file`, form)
  },
  process: (id: string) => api.post<BookProcessingStatus>(`${bookPath(id)}/process`),
  cancel: (id: string) => api.post<BookProcessingStatus>(`${bookPath(id)}/cancel`),
  status: (id: string, signal?: AbortSignal) =>
    api.get<BookProcessingStatus>(`${bookPath(id)}/status`, { signal }),
  chunks: (id: string, page = 0, size = 5, signal?: AbortSignal) =>
    api.get<ChunkPage>(`${bookPath(id)}/chunks?page=${page}&size=${size}`, { signal }),
}

export const ragApi = {
  ask: (question: string, bookIds?: string[], signal?: AbortSignal) =>
    api.post<AskResult>('/rag/ask', { question, bookIds }, { signal }),
}

/** True once the book will not change on its own (no job is working on it). */
export function isTerminalStatus(s: Pick<BookProcessingStatus, 'status' | 'stage'>): boolean {
  return (
    s.stage === 'COMPLETED' ||
    s.stage === 'FAILED' ||
    s.stage === 'NEEDS_OCR' ||
    s.stage === 'CANCELLED' ||
    s.status === 'COMPLETED' ||
    s.status === 'FAILED' ||
    s.status === 'NEEDS_OCR'
  )
}

const sleep = (ms: number, signal?: AbortSignal) =>
  new Promise<void>((resolve, reject) => {
    const t = setTimeout(resolve, ms)
    signal?.addEventListener(
      'abort',
      () => {
        clearTimeout(t)
        reject(new DOMException('Aborted', 'AbortError'))
      },
      { once: true },
    )
  })

/** Polls /status until processing reaches a terminal state. Rejects with AbortError when cancelled. */
export async function pollUntilDone(
  id: string,
  onUpdate: (s: BookProcessingStatus) => void,
  signal?: AbortSignal,
  intervalMs = 1500,
): Promise<BookProcessingStatus> {
  for (;;) {
    const s = await booksApi.status(id, signal)
    onUpdate(s)
    if (isTerminalStatus(s)) return s
    await sleep(intervalMs, signal)
  }
}

/** Hex SHA-256 of a file, computed in the browser (the file never leaves the device for hashing). */
export async function sha256File(file: Blob): Promise<string> {
  if (!globalThis.crypto?.subtle) {
    throw new Error(
      'Your browser cannot hash files on an insecure connection. Open Book Buddy via https:// or http://localhost.',
    )
  }
  const digest = await globalThis.crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('')
}

export function formatPages(start: number | null, end: number | null): string {
  if (start == null) return ''
  return end == null || end === start ? `p. ${start}` : `pp. ${start}\u2013${end}`
}

export const STAGE_LABEL: Record<IngestionStage, string> = {
  QUEUED: 'Queued',
  EXTRACTING: 'Reading pages',
  CHUNKING: 'Preparing text',
  EMBEDDING: 'Indexing passages',
  COMPLETED: 'Ready',
  FAILED: 'Failed',
  NEEDS_OCR: 'Needs OCR',
  CANCELLED: 'Cancelled',
}

/** Hex SHA-256 of a string. Used to demo-generate a contentHash from the title. */
export async function sha256Hex(input: string): Promise<string> {
  const bytes = new TextEncoder().encode(input)
  if (globalThis.crypto?.subtle) {
    const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes)
    return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('')
  }
  // Insecure-context fallback (plain http on a LAN IP): not cryptographic, demo only.
  let out = ''
  let h = 0x811c9dc5
  for (let i = 0; out.length < 64; i++) {
    h ^= bytes[i % Math.max(bytes.length, 1)] ?? 0
    h = Math.imul(h, 0x01000193) >>> 0
    out += h.toString(16).padStart(8, '0')
  }
  return out.slice(0, 64)
}

export const STATUS_LABEL: Record<BookStatus, string> = {
  QUEUED: 'Queued',
  EXTRACTING: 'Reading pages',
  CHUNKING: 'Preparing',
  EMBEDDING: 'Indexing',
  COMPLETED: 'Ready',
  FAILED: 'Failed',
  NEEDS_OCR: 'Needs OCR',
}
