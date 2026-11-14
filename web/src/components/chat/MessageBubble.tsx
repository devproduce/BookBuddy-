import { formatPages } from '../../api/books'
import type { MessageCitation, SafetyEvent } from '../../api/chat'
import { CrisisCard } from './CrisisCard'
import type { UiMessage } from './types'

function renderText(m: UiMessage, onCite: (c: MessageCitation) => void) {
  return m.content.split(/(\[\d+\])/g).map((part, i) => {
    const hit = /^\[(\d+)\]$/.exec(part)
    if (!hit) return <span key={i}>{part}</span>
    const cite = m.citations.find((c) => c.index === Number(hit[1]))
    if (!cite) return <span key={i}>{part}</span>
    return (
      <button
        key={i}
        type="button"
        className="cite-btn"
        title={cite.bookTitle}
        aria-label={`Show passage ${cite.index} from ${cite.bookTitle}`}
        onClick={() => onCite(cite)}
      >
        {part}
      </button>
    )
  })
}

export function MessageBubble({
  message,
  fallbackSafety,
  canRegenerate,
  disabled,
  onCite,
  onRetry,
}: {
  message: UiMessage
  /** Resources to show for stored messages flagged by the safety check (live ones carry their own). */
  fallbackSafety: SafetyEvent | null
  canRegenerate: boolean
  disabled: boolean
  onCite: (c: MessageCitation) => void
  onRetry: (m: UiMessage) => void
}) {
  const mine = message.role === 'USER'
  const safety =
    message.safety ??
    (!mine && (message.safetyLevel === 'ACUTE' || message.safetyLevel === 'ELEVATED') && fallbackSafety
      ? { ...fallbackSafety, level: message.safetyLevel }
      : null)
  const failed = message.status === 'FAILED'
  const interrupted = message.status === 'INTERRUPTED' && !message.streaming

  return (
    <li className={`msg msg--${mine ? 'user' : 'assistant'}`}>
      <div className="msg__bubble">
        {safety && <CrisisCard safety={safety} />}
        {message.streaming && !message.content ? (
          <span className="typing" aria-label="Book Buddy is thinking">
            <i />
            <i />
            <i />
          </span>
        ) : (
          <p className="msg__text">
            {renderText(message, onCite)}
            {message.streaming && <span className="caret" aria-hidden="true" />}
          </p>
        )}

        {!mine && message.citations.length > 0 && !message.streaming && (
          <ul className="sources" aria-label="Sources">
            {message.citations.map((c) => (
              <li key={c.index}>
                <button type="button" className="source-chip" onClick={() => onCite(c)}>
                  <b>[{c.index}]</b> {c.bookTitle}
                  {c.pageStart != null ? ` · ${formatPages(c.pageStart, c.pageEnd)}` : ''}
                </button>
              </li>
            ))}
          </ul>
        )}

        {(failed || interrupted) && (
          <div className="msg__status" role="status">
            <span>
              {failed
                ? (message.errorText ?? "I couldn't finish that reply.")
                : message.errorText
                  ? message.errorText
                  : 'This reply was stopped.'}
            </span>
            <button type="button" className="link-btn" onClick={() => onRetry(message)} disabled={disabled}>
              Retry
            </button>
          </div>
        )}

        {!mine && message.status === 'COMPLETE' && canRegenerate && !message.streaming && (
          <div className="msg__actions">
            <button type="button" className="link-btn" onClick={() => onRetry(message)} disabled={disabled}>
              Regenerate
            </button>
          </div>
        )}
      </div>
    </li>
  )
}