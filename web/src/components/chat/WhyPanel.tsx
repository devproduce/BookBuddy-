import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { formatPages } from '../../api/books'
import type { MessageCitation } from '../../api/chat'
import { categoryLabel, memoryApi, type Memory } from '../../api/memory'
import { Alert } from '../Alert'
import { errorMessage } from '../errorMessage'
import type { UiMessage } from './types'

/**
 * "Why this answer": what Book Buddy drew on for one reply. Lists the memories that were in the prompt (with a quick
 * way to forget one) and the book passages it cited.
 */
export function WhyPanel({
  message,
  onClose,
  onCite,
}: {
  message: UiMessage
  onClose: () => void
  onCite: (c: MessageCitation) => void
}) {
  const ids = message.memoryIdsUsed
  const idsKey = ids.join(',')
  const [memories, setMemories] = useState<Memory[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)

  useEffect(() => {
    if (!idsKey) {
      setMemories([])
      return
    }
    const ctrl = new AbortController()
    setMemories(null)
    setError(null)
    memoryApi
      .list({ ids: idsKey.split(','), status: 'ALL' }, ctrl.signal)
      .then((found) => {
        if (!ctrl.signal.aborted) setMemories(found)
      })
      .catch((err) => {
        if (!ctrl.signal.aborted) setError(errorMessage(err))
      })
    return () => ctrl.abort()
  }, [idsKey])

  const forget = useCallback(async (m: Memory) => {
    if (!window.confirm('Forget this memory? Book Buddy will stop using it.')) return
    setBusyId(m.id)
    setError(null)
    try {
      await memoryApi.remove(m.id)
      setMemories((ms) => (ms ? ms.filter((x) => x.id !== m.id) : ms))
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusyId(null)
    }
  }, [])

  const removed = memories ? Math.max(0, ids.length - memories.length) : 0
  const citations = message.citations

  return (
    <aside className="passage why" role="dialog" aria-label="Why this answer">
      <header className="passage__head">
        <div>
          <p className="passage__eyebrow">Why this answer</p>
          <h3 className="passage__title">What shaped this reply</h3>
        </div>
        <button type="button" className="btn btn--sm btn--ghost" onClick={onClose} aria-label="Close panel">
          Close
        </button>
      </header>

      <Alert>{error}</Alert>

      <section className="why__section">
        <h4 className="why__heading">Memories used</h4>
        {memories === null ? (
          <p className="hint">Loading…</p>
        ) : memories.length === 0 ? (
          <p className="hint">
            {ids.length === 0
              ? "This reply didn't use anything Book Buddy remembers about you."
              : 'The memories behind this reply have since been removed.'}
          </p>
        ) : (
          <ul className="why__list">
            {memories.map((m) => (
              <li key={m.id} className="why__item">
                <p className="why__text">{m.content}</p>
                <p className="why__meta">
                  <span className="tag">{categoryLabel(m.category)}</span>
                  <span className={`tag ${m.classification === 'INFERRED' && !m.userVerified ? 'tag--warn' : ''}`}>
                    {m.userVerified ? 'Confirmed' : m.classification === 'EXPLICIT' ? 'You said' : 'Guess'}
                  </span>
                  {m.status !== 'ACTIVE' && <span className="tag">{m.status.toLowerCase()}</span>}
                  <button
                    type="button"
                    className="link-btn"
                    disabled={busyId === m.id}
                    onClick={() => void forget(m)}
                  >
                    Forget
                  </button>
                </p>
              </li>
            ))}
          </ul>
        )}
        {removed > 0 && memories && memories.length > 0 && (
          <p className="hint">
            {removed} other {removed === 1 ? 'memory was' : 'memories were'} used but no longer exist.
          </p>
        )}
        <Link to="/memory" className="link-btn passage__open">
          Manage all memories
        </Link>
      </section>

      <section className="why__section">
        <h4 className="why__heading">Book passages</h4>
        {citations.length === 0 ? (
          <p className="hint">No passages from your books were quoted in this reply.</p>
        ) : (
          <ul className="why__list">
            {citations.map((c) => (
              <li key={c.index} className="why__item">
                <button type="button" className="source-chip" onClick={() => onCite(c)}>
                  <b>[{c.index}]</b> {c.bookTitle}
                  {c.pageStart != null ? ` · ${formatPages(c.pageStart, c.pageEnd)}` : ''}
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </aside>
  )
}
