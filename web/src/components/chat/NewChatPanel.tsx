import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import type { Book } from '../../api/books'
import type { ScopeMode } from '../../api/chat'

export interface NewChatOptions {
  incognito: boolean
  scope: { mode: ScopeMode; bookIds: string[] }
}

/** Start-of-chat form: incognito toggle, book scope selector and the first message. */
export function NewChatPanel({
  books,
  busy,
  onStart,
}: {
  books: Book[]
  busy: boolean
  onStart: (text: string, options: NewChatOptions) => void
}) {
  const [text, setText] = useState('')
  const [incognito, setIncognito] = useState(false)
  const [mode, setMode] = useState<ScopeMode>('ALL')
  const [bookId, setBookId] = useState('')

  const ready = books.filter((b) => b.status === 'COMPLETED')
  const chosenBook = bookId || ready[0]?.id || ''
  const scopeInvalid = mode === 'ONE' && !chosenBook

  function submit(e: FormEvent) {
    e.preventDefault()
    const t = text.trim()
    if (!t || busy || scopeInvalid) return
    onStart(t, {
      incognito,
      scope: { mode, bookIds: mode === 'ONE' ? [chosenBook] : [] },
    })
  }

  return (
    <form className="newchat" onSubmit={submit}>
      <header>
        <p className="eyebrow">New conversation</p>
        <h1 className="newchat__title">What’s on your mind about your reading?</h1>
        <p className="lede">A chapter that stuck with you, a question you can’t shake, or simply how the day feels.</p>
      </header>

      <div className="newchat__options">
        <label className="field">
          <span className="field__label">Books to look in</span>
          <select value={mode} onChange={(e) => setMode(e.target.value as ScopeMode)}>
            <option value="ALL">All my books</option>
            <option value="ONE" disabled={ready.length === 0}>
              One book
            </option>
            <option value="NONE">No books — just chat</option>
          </select>
        </label>

        {mode === 'ONE' && (
          <label className="field">
            <span className="field__label">Which book</span>
            <select value={chosenBook} onChange={(e) => setBookId(e.target.value)}>
              {ready.map((b) => (
                <option key={b.id} value={b.id}>
                  {b.title}
                </option>
              ))}
            </select>
          </label>
        )}

        <label className="toggle-row toggle-row--live">
          <div>
            <strong>Incognito</strong>
            <p className="panel__note">Won't be remembered or used to personalise future chats.</p>
          </div>
          <span className="switch switch--live">
            <input
              type="checkbox"
              checked={incognito}
              onChange={(e) => setIncognito(e.target.checked)}
              aria-label="Incognito conversation"
            />
            <span className="switch__track" />
          </span>
        </label>
      </div>

      {ready.length === 0 && mode !== 'NONE' && (
        <p className="hint">
          None of your books are ready yet. <Link to="/library/add">Add a book</Link> to ask about it, or choose
          “No books”.
        </p>
      )}

      <div className="composer composer--new">
        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="Say hello, or ask about a chapter…"
          maxLength={4000}
          rows={3}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              e.currentTarget.form?.requestSubmit()
            }
          }}
          aria-label="Your message"
        />
        <button type="submit" className="btn btn--primary" disabled={busy || !text.trim() || scopeInvalid}>
          Start chat
        </button>
      </div>
    </form>
  )
}