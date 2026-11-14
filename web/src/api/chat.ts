import { api, openEventStream } from './client'
import type { CrisisResource } from './settings'

export type ScopeMode = 'ALL' | 'ONE' | 'NONE'
export type RiskLevel = 'NONE' | 'ELEVATED' | 'ACUTE'
export type MessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM'
export type MessageStatus = 'COMPLETE' | 'INTERRUPTED' | 'FAILED'

export interface BookScope {
  mode: ScopeMode
  bookIds: string[]
}

export interface Conversation {
  id: string
  title: string
  incognito: boolean
  bookScope: BookScope
  messageCount: number
  hasSummary: boolean
  createdAt: string
  updatedAt: string
}

export interface MessageCitation {
  /** 1-based; matches the [n] marker in the reply text. */
  index: number
  chunkId: string
  bookId: string
  bookTitle: string
  pageStart: number | null
  pageEnd: number | null
  excerpt: string
}

export interface TokenUsage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
  estimated: boolean
}

export interface ChatMessage {
  id: string
  conversationId: string
  role: MessageRole
  content: string
  status: MessageStatus
  citations: MessageCitation[]
  memoryIdsUsed: string[]
  modelId: string | null
  promptVersion: string | null
  tokenUsage: TokenUsage | null
  safetyLevel: RiskLevel | null
  language: string | null
  inputMode: 'TEXT' | 'VOICE'
  createdAt: string
}

export interface CursorPage<T> {
  items: T[]
  nextCursor: string | null
}

export interface CreateConversationInput {
  title?: string
  incognito?: boolean
  bookScope?: { mode: ScopeMode; bookIds?: string[] }
}

export interface SafetyEvent {
  level: 'ELEVATED' | 'ACUTE'
  resources: CrisisResource[]
}

export interface StreamMeta {
  messageId: string
  userMessageId: string
  conversationId: string
}

export interface StreamDone {
  messageId: string
  status: MessageStatus
  /** Authoritative final text (quote-verified); replaces the streamed tokens. */
  content: string
  usage: TokenUsage | null
  modelId: string | null
}

export interface StreamError {
  code: string
  message: string
  retryable: boolean
  messageId: string | null
}

export interface StreamHandlers {
  onMeta?: (meta: StreamMeta) => void
  onToken?: (text: string) => void
  onCitations?: (citations: MessageCitation[]) => void
  onSafety?: (safety: SafetyEvent) => void
  onDone?: (done: StreamDone) => void
  onError?: (error: StreamError) => void
}

interface SseFrame {
  event: string
  data: string
}

/** Splits an SSE byte stream into frames. Comment lines (": keepalive") are dropped. */
export async function* readSse(body: ReadableStream<Uint8Array>): AsyncGenerator<SseFrame> {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
      let sep: number
      while ((sep = buffer.indexOf('\n\n')) >= 0) {
        const raw = buffer.slice(0, sep)
        buffer = buffer.slice(sep + 2)
        const frame = parseFrame(raw)
        if (frame) yield frame
      }
    }
    const tail = parseFrame(buffer)
    if (tail) yield tail
  } finally {
    reader.releaseLock()
  }
}

function parseFrame(raw: string): SseFrame | null {
  let event = 'message'
  const data: string[] = []
  for (const line of raw.split('\n')) {
    if (!line || line.startsWith(':')) continue
    const colon = line.indexOf(':')
    const field = colon < 0 ? line : line.slice(0, colon)
    const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
    if (field === 'event') event = value
    else if (field === 'data') data.push(value)
  }
  return data.length ? { event, data: data.join('\n') } : null
}

/**
 * Posts to an SSE endpoint and dispatches events to {@code handlers}. Aborting {@code signal} closes the connection,
 * which makes the server cancel the upstream model call. Resolves when the stream ends; throws ApiError for HTTP
 * errors (e.g. 403 CONSENT_REQUIRED) and AbortError when aborted.
 */
async function consume(
  path: string,
  body: unknown,
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const res = await openEventStream(path, body, signal)
  if (!res.body) return
  for await (const frame of readSse(res.body)) {
    let payload: unknown
    try {
      payload = JSON.parse(frame.data)
    } catch {
      continue
    }
    switch (frame.event) {
      case 'meta':
        handlers.onMeta?.(payload as StreamMeta)
        break
      case 'token':
        handlers.onToken?.((payload as { text: string }).text)
        break
      case 'citations':
        handlers.onCitations?.((payload as { citations: MessageCitation[] }).citations)
        break
      case 'safety':
        handlers.onSafety?.(payload as SafetyEvent)
        break
      case 'done':
        handlers.onDone?.(payload as StreamDone)
        break
      case 'error':
        handlers.onError?.(payload as StreamError)
        break
    }
  }
}

const base = (id: string) => `/conversations/${encodeURIComponent(id)}`

export const chatApi = {
  list: (limit = 30, cursor?: string | null, signal?: AbortSignal) => {
    const q = new URLSearchParams({ limit: String(limit) })
    if (cursor) q.set('cursor', cursor)
    return api.get<CursorPage<Conversation>>(`/conversations?${q}`, { signal })
  },
  create: (input: CreateConversationInput) => api.post<Conversation>('/conversations', input),
  get: (id: string) => api.get<Conversation>(base(id)),
  rename: (id: string, title: string) => api.patch<Conversation>(base(id), { title }),
  setIncognito: (id: string, incognito: boolean) => api.patch<Conversation>(base(id), { incognito }),
  remove: (id: string) => api.delete(base(id)),
  messages: (id: string, limit = 50, cursor?: string | null, signal?: AbortSignal) => {
    const q = new URLSearchParams({ limit: String(limit) })
    if (cursor) q.set('cursor', cursor)
    return api.get<CursorPage<ChatMessage>>(`${base(id)}/messages?${q}`, { signal })
  },
  streamMessage: (
    id: string,
    content: string,
    handlers: StreamHandlers,
    signal?: AbortSignal,
  ) => consume(`${base(id)}/messages/stream`, { content }, handlers, signal),
  regenerate: (id: string, messageId: string, handlers: StreamHandlers, signal?: AbortSignal) =>
    consume(`${base(id)}/messages/${encodeURIComponent(messageId)}/regenerate`, undefined, handlers, signal),
}