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

export const booksApi = {
  list: (signal?: AbortSignal) => api.get<Book[]>('/books', { signal }),
  get: (id: string) => api.get<Book>(`/books/${encodeURIComponent(id)}`),
  create: (input: CreateBookInput) => api.post<Book>('/books', input),
  update: (id: string, input: UpdateBookInput) =>
    api.patch<Book>(`/books/${encodeURIComponent(id)}`, input),
  remove: (id: string) => api.delete(`/books/${encodeURIComponent(id)}`),
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
