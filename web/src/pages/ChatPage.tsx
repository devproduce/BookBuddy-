import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { booksApi, type Book } from '../api/books'
import {
  chatApi,
  type Conversation,
  type MessageCitation,
  type SafetyEvent,
  type StreamHandlers,
} from '../api/chat'
import { ApiError } from '../api/client'
import { settingsApi, type CrisisResource } from '../api/settings'
import { useAuth } from '../auth/useAuth'
import { Alert } from '../components/Alert'
import { ConsentGate } from '../components/chat/ConsentGate'
import { MessageBubble } from '../components/chat/MessageBubble'
import { NewChatPanel, type NewChatOptions } from '../components/chat/NewChatPanel'
import { PassagePanel } from '../components/chat/PassagePanel'
import { WhyPanel } from '../components/chat/WhyPanel'
import { isLocalId, toUi, uid, type UiMessage } from '../components/chat/types'
import { errorMessage } from '../components/errorMessage'

const isAbort = (err: unknown) => err instanceof DOMException && err.name === 'AbortError'

function relativeTime(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime()
  const min = Math.round(diff / 60000)
  if (min < 1) return 'just now'
  if (min < 60) return `${min} min ago`
  const h = Math.round(min / 60)
  if (h < 24) return `${h} h ago`
  const d = Math.round(h / 24)
  if (d < 7) return `${d} d ago`
  return new Date(iso).toLocaleDateString()
}

function scopeLabel(c: Conversation, books: Book[]): string {
  if (c.bookScope.mode === 'NONE') return 'No books'
  if (c.bookScope.mode === 'ONE') {
    const b = books.find((x) => x.id === c.bookScope.bookIds[0])
    return b ? b.title : 'One book'
  }
  return 'All books'
}

function localMessage(role: 'USER' | 'ASSISTANT', content: string, conversationId: string): UiMessage {
  const key = uid()
  return {
    key,
    id: `local-${key}`,
    conversationId,
    role,
    content,
    status: 'COMPLETE',
    citations: [],
    memoryIdsUsed: [],
    modelId: null,
    promptVersion: null,
    tokenUsage: null,
    safetyLevel: null,
    language: null,
    inputMode: 'TEXT',
    createdAt: new Date().toISOString(),
    streaming: role === 'ASSISTANT',
  }
}

export function ChatPage() {
  return (
    <ConsentGate>
      <Chat />
    </ConsentGate>
  )
}

function Chat() {
  const { conversationId } = useParams()
  const activeId = conversationId
  const navigate = useNavigate()
  const { me } = useAuth()

  const [conversations, setConversations] = useState<Conversation[]>([])
  const [listCursor, setListCursor] = useState<string | null>(null)
  const [listLoading, setListLoading] = useState(true)
  const [books, setBooks] = useState<Book[]>([])
  const [helplines, setHelplines] = useState<CrisisResource[]>([])

  const [activeConv, setActiveConv] = useState<Conversation | null>(null)
  const [messages, setMessages] = useState<UiMessage[]>([])
  const [olderCursor, setOlderCursor] = useState<string | null>(null)
  const [threadLoading, setThreadLoading] = useState(false)
  const [notFound, setNotFound] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [draft, setDraft] = useState('')
  const [passage, setPassage] = useState<MessageCitation | null>(null)
  const [whyKey, setWhyKey] = useState<string | null>(null)

  const abortRef = useRef<AbortController | null>(null)
  /** Conversation whose messages are currently held in state. */
  const threadIdRef = useRef<string | null>(null)
  /** Set when we create a conversation locally, so the open-conversation effect leaves its state alone. */
  const skipLoadRef = useRef<string | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const stickToBottom = useRef(true)

  // ---- sidebar data -------------------------------------------------------------------------------------
  const refreshList = useCallback(async () => {
    try {
      const page = await chatApi.list(30)
      setConversations(page.items)
      setListCursor(page.nextCursor)
    } catch (err) {
      if (!isAbort(err)) setError(errorMessage(err))
    } finally {
      setListLoading(false)
    }
  }, [])

  useEffect(() => {
    void refreshList()
    booksApi
      .list()
      .then(setBooks)
      .catch(() => undefined)
    settingsApi
      .get()
      .then((s) => setHelplines(s.crisisResources))
      .catch(() => undefined)
  }, [refreshList])

  async function loadMoreConversations() {
    if (!listCursor) return
    try {
      const page = await chatApi.list(30, listCursor)
      setConversations((prev) => [...prev, ...page.items])
      setListCursor(page.nextCursor)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  // ---- open a conversation ------------------------------------------------------------------------------
  useEffect(() => {
    if (skipLoadRef.current && skipLoadRef.current !== activeId) skipLoadRef.current = null
    // a conversation we just created already holds its (streaming) messages in state: do not reload it
    if (activeId && skipLoadRef.current === activeId) return

    abortRef.current?.abort()
    threadIdRef.current = activeId ?? null
    setPassage(null)
    setWhyKey(null)
    setError(null)
    setNotFound(false)
    setMessages([])
    setOlderCursor(null)
    setActiveConv(null)
    if (!activeId) {
      setThreadLoading(false)
      return
    }

    const ctrl = new AbortController()
    setThreadLoading(true)
    stickToBottom.current = true
    Promise.all([chatApi.get(activeId), chatApi.messages(activeId, 50, null, ctrl.signal)])
      .then(([conv, page]) => {
        if (ctrl.signal.aborted) return
        setActiveConv(conv)
        setMessages(page.items.map(toUi))
        setOlderCursor(page.nextCursor)
      })
      .catch((err) => {
        if (isAbort(err) || ctrl.signal.aborted) return
        if (err instanceof ApiError && err.status === 404) setNotFound(true)
        else setError(errorMessage(err))
      })
      .finally(() => {
        if (!ctrl.signal.aborted) setThreadLoading(false)
      })
    return () => ctrl.abort()
  }, [activeId])
  // stop any stream when leaving the page
  useEffect(() => () => abortRef.current?.abort(), [])

  async function loadOlder() {
    if (!activeId || !olderCursor) return
    try {
      const page = await chatApi.messages(activeId, 50, olderCursor)
      setMessages((prev) => [...page.items.map(toUi), ...prev])
      setOlderCursor(page.nextCursor)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  // keep the newest message in view while streaming, unless the reader scrolled up
  useEffect(() => {
    const el = scrollRef.current
    if (el && stickToBottom.current) el.scrollTop = el.scrollHeight
  }, [messages, threadLoading])

  function onScroll() {
    const el = scrollRef.current
    if (!el) return
    stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80
  }

  // ---- streaming ----------------------------------------------------------------------------------------
  const run = useCallback(
    async (
      convId: string,
      assistantKey: string,
      start: (handlers: StreamHandlers, signal: AbortSignal) => Promise<void>,
      userKey?: string,
    ) => {
      const ctrl = new AbortController()
      abortRef.current = ctrl
      setBusy(true)
      setError(null)
      stickToBottom.current = true

      const patch = (change: Partial<UiMessage> | ((m: UiMessage) => Partial<UiMessage>)) =>
        setMessages((ms) =>
          ms.map((m) =>
            m.key === assistantKey ? { ...m, ...(typeof change === 'function' ? change(m) : change) } : m,
          ),
        )
      let finished = false

      try {
        await start(
          {
            onMeta: (meta) => {
              patch({ id: meta.messageId })
              if (userKey) {
                setMessages((ms) => ms.map((m) => (m.key === userKey ? { ...m, id: meta.userMessageId } : m)))
              }
            },
            onToken: (text) => patch((m) => ({ content: m.content + text })),
            onCitations: (citations) => patch({ citations }),
            onSafety: (safety: SafetyEvent) => patch({ safety, safetyLevel: safety.level }),
            onMemory: (memoryIds) => patch({ memoryIdsUsed: memoryIds }),
            onDone: (done) => {
              finished = true
              patch({
                id: done.messageId,
                content: done.content,
                status: done.status,
                tokenUsage: done.usage,
                modelId: done.modelId,
                streaming: false,
                errorText: null,
              })
            },
            onError: (e) => {
              finished = true
              patch((m) => ({
                id: e.messageId ?? m.id,
                status: m.content ? 'INTERRUPTED' : 'FAILED',
                streaming: false,
                errorText: e.message,
              }))
            },
          },
          ctrl.signal,
        )
        if (!finished) patch({ streaming: false, status: 'INTERRUPTED' })
      } catch (err) {
        if (isAbort(err)) {
          patch({ streaming: false, status: 'INTERRUPTED', errorText: null })
        } else if (err instanceof ApiError && err.code === 'CONSENT_REQUIRED') {
          setMessages((ms) => ms.filter((m) => m.key !== assistantKey && m.key !== userKey))
          await me() // flips the consent gate back on
        } else {
          patch((m) => ({
            status: m.content ? 'INTERRUPTED' : 'FAILED',
            streaming: false,
            errorText: errorMessage(err),
          }))
        }
      } finally {
        if (abortRef.current === ctrl) abortRef.current = null
        setBusy(false)
        void refreshList()
        chatApi
          .get(convId)
          .then((c) => {
            if (threadIdRef.current === convId) setActiveConv(c)
          })
          .catch(() => undefined)
      }
    },
    [me, refreshList],
  )

  function sendTo(convId: string, text: string) {
    const content = text.trim()
    if (!content) return
    const user = localMessage('USER', content, convId)
    user.streaming = false
    const assistant = localMessage('ASSISTANT', '', convId)
    setMessages((ms) => [...ms, user, assistant])
    void run(convId, assistant.key, (h, s) => chatApi.streamMessage(convId, content, h, s), user.key)
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (!activeId || busy || !draft.trim()) return
    const text = draft
    setDraft('')
    sendTo(activeId, text)
  }

  async function startNew(text: string, options: NewChatOptions) {
    setBusy(true)
    setError(null)
    try {
      const created = await chatApi.create({
        incognito: options.incognito,
        bookScope: { mode: options.scope.mode, bookIds: options.scope.bookIds },
      })
      threadIdRef.current = created.id
      skipLoadRef.current = created.id
      setActiveConv(created)
      setMessages([])
      setOlderCursor(null)
      setConversations((prev) => [created, ...prev])
      navigate(`/chat/${created.id}`)
      sendTo(created.id, text)
    } catch (err) {
      setBusy(false)
      setError(errorMessage(err))
    }
  }

  function retry(message: UiMessage) {
    if (!activeId || busy) return
    const idx = messages.findIndex((m) => m.key === message.key)
    if (idx < 0) return
    if (isLocalId(message.id)) {
      // the server never saw this turn: send the question again
      const question = messages[idx - 1]
      if (!question || question.role !== 'USER') return
      setMessages((ms) => ms.filter((m) => m.key !== message.key && m.key !== question.key))
      sendTo(activeId, question.content)
      return
    }
    setPassage(null)
    patchLocal(message.key, {
      content: '',
      citations: [],
      safety: null,
      status: 'COMPLETE',
      streaming: true,
      errorText: null,
    })
    void run(activeId, message.key, (h, s) => chatApi.regenerate(activeId, message.id, h, s))
  }

  function patchLocal(key: string, change: Partial<UiMessage>) {
    setMessages((ms) => ms.map((m) => (m.key === key ? { ...m, ...change } : m)))
  }

  function stop() {
    abortRef.current?.abort()
  }

  // ---- conversation actions -----------------------------------------------------------------------------
  async function renameActive() {
    if (!activeConv) return
    const title = window.prompt('Rename conversation', activeConv.title)?.trim()
    if (!title || title === activeConv.title) return
    try {
      const updated = await chatApi.rename(activeConv.id, title)
      setActiveConv(updated)
      setConversations((prev) => prev.map((c) => (c.id === updated.id ? updated : c)))
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  async function deleteActive() {
    if (!activeConv) return
    if (!window.confirm(`Delete "${activeConv.title}"? This can't be undone.`)) return
    stop()
    try {
      await chatApi.remove(activeConv.id)
      setConversations((prev) => prev.filter((c) => c.id !== activeConv.id))
      threadIdRef.current = null
      navigate('/chat')
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  const fallbackSafety: SafetyEvent | null = useMemo(
    () => (helplines.length ? { level: 'ACUTE', resources: helplines } : null),
    [helplines],
  )
  const lastAssistantKey = useMemo(() => {
    for (let i = messages.length - 1; i >= 0; i--) if (messages[i].role === 'ASSISTANT') return messages[i].key
    return null
  }, [messages])

  const inThread = Boolean(activeId)
  const whyMessage = whyKey ? (messages.find((m) => m.key === whyKey) ?? null) : null

  /** Opening a passage replaces the "why" drawer; closing the passage brings it back. */
  function openPassage(c: MessageCitation) {
    setPassage(c)
  }

  return (
    <div className={`chat${inThread ? ' chat--thread' : ''}`}>
      {/* ---- conversation list ---- */}
      <aside className="chat__list" aria-label="Conversations">
        <div className="chat__list-head">
          <h2>Conversations</h2>
          <Link to="/chat" className="btn btn--sm btn--primary">
            New chat
          </Link>
        </div>
        {listLoading ? (
          <p className="hint">Loading…</p>
        ) : conversations.length === 0 ? (
          <p className="hint">No conversations yet. Start one!</p>
        ) : (
          <ul className="conv-list">
            {conversations.map((c) => (
              <li key={c.id}>
                <Link
                  to={`/chat/${c.id}`}
                  className={`conv${c.id === activeId ? ' is-active' : ''}`}
                  aria-current={c.id === activeId ? 'page' : undefined}
                >
                  <span className="conv__title">{c.title}</span>
                  <span className="conv__meta">
                    {c.incognito && <span className="conv__badge">Incognito</span>}
                    {relativeTime(c.updatedAt)}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
        {listCursor && (
          <button type="button" className="link-btn" onClick={loadMoreConversations}>
            Show older
          </button>
        )}
      </aside>

      {/* ---- thread / new chat ---- */}
      <section className="chat__main">
        {!inThread ? (
          <div className="chat__scroll chat__scroll--new">
            <Alert>{error}</Alert>
            <NewChatPanel books={books} busy={busy} onStart={startNew} />
          </div>
        ) : notFound ? (
          <div className="empty">
            <h2>Conversation not found</h2>
            <p>It may have been deleted.</p>
            <Link to="/chat" className="btn btn--primary">
              Start a new chat
            </Link>
          </div>
        ) : (
          <>
            <header className="chat__head">
              <Link to="/chat" className="back chat__back">
                ← Conversations
              </Link>
              <div className="chat__titlebox">
                <h1 className="chat__title">{activeConv?.title ?? 'Conversation'}</h1>
                {activeConv && (
                  <p className="chat__sub">
                    {activeConv.incognito && <span className="tag">Incognito</span>}
                    <span>{scopeLabel(activeConv, books)}</span>
                  </p>
                )}
              </div>
              <div className="chat__tools">
                <button type="button" className="btn btn--sm btn--ghost" onClick={renameActive} disabled={!activeConv}>
                  Rename
                </button>
                <button type="button" className="btn btn--sm btn--danger" onClick={deleteActive} disabled={!activeConv}>
                  Delete
                </button>
              </div>
            </header>

            <div className="chat__scroll" ref={scrollRef} onScroll={onScroll}>
              {threadLoading && <p className="hint">Loading messages…</p>}
              {olderCursor && (
                <button type="button" className="link-btn chat__older" onClick={loadOlder}>
                  Load earlier messages
                </button>
              )}
              {!threadLoading && messages.length === 0 && (
                <p className="hint chat__empty">Say something to begin.</p>
              )}
              <ul className="msgs" aria-live="polite">
                {messages.map((m) => (
                  <MessageBubble
                    key={m.key}
                    message={m}
                    fallbackSafety={fallbackSafety}
                    canRegenerate={m.key === lastAssistantKey}
                    disabled={busy}
                    onCite={openPassage}
                    onWhy={(m) => {
                      setPassage(null)
                      setWhyKey(m.key)
                    }}
                    onRetry={retry}
                  />
                ))}
              </ul>
            </div>

            {error && (
              <div className="chat__alert">
                <Alert>{error}</Alert>
              </div>
            )}

            <form className="composer" onSubmit={onSubmit}>
              <textarea
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                placeholder="Message Book Buddy…"
                rows={1}
                maxLength={4000}
                aria-label="Your message"
                disabled={threadLoading || notFound}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                    e.preventDefault()
                    e.currentTarget.form?.requestSubmit()
                  }
                }}
              />
              {busy ? (
                <button type="button" className="btn btn--ghost composer__btn" onClick={stop}>
                  Stop
                </button>
              ) : (
                <button type="submit" className="btn btn--primary composer__btn" disabled={!draft.trim()}>
                  Send
                </button>
              )}
            </form>
            <p className="chat__fineprint">
              Book Buddy is an AI and can make mistakes. It isn't a substitute for professional help.
            </p>
          </>
        )}
      </section>

      {passage && <PassagePanel citation={passage} onClose={() => setPassage(null)} />}
      {whyMessage && !passage && (
        <WhyPanel message={whyMessage} onClose={() => setWhyKey(null)} onCite={openPassage} />
      )}
    </div>
  )
}
