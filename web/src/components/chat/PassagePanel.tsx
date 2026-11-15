import { Link } from 'react-router-dom'
import type { MessageCitation } from '../../api/chat'
import { formatPages } from '../../api/books'
import { usePanelFocus } from './usePanelFocus'

/** Shows the passage behind a [n] citation. */
export function PassagePanel({
  citation,
  onClose,
}: {
  citation: MessageCitation
  onClose: () => void
}) {
  const pages = formatPages(citation.pageStart, citation.pageEnd)
  const [panelRef, onPanelKeyDown] = usePanelFocus<HTMLElement>(onClose)
  return (
    <aside
      className="passage"
      role="dialog"
      aria-labelledby="passage-title"
      tabIndex={-1}
      ref={panelRef}
      onKeyDown={onPanelKeyDown}
    >
      <header className="passage__head">
        <div>
          <p className="passage__eyebrow">
            Source [{citation.index}]{pages ? ` · ${pages}` : ''}
          </p>
          <h3 id="passage-title" className="passage__title">{citation.bookTitle}</h3>
        </div>
        <button type="button" className="btn btn--sm btn--ghost" onClick={onClose} aria-label="Close passage">
          Close
        </button>
      </header>
      <blockquote className="passage__text">{citation.excerpt}</blockquote>
      <Link to={`/library/${citation.bookId}`} className="link-btn passage__open">
        Open book
      </Link>
    </aside>
  )
}