import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { booksApi, STATUS_LABEL, type Book } from '../api/books'
import { Alert } from '../components/Alert'
import { errorMessage } from '../components/errorMessage'

function coverHue(title: string): number {
  let h = 0
  for (const ch of title) h = (h * 31 + ch.charCodeAt(0)) % 360
  return h
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function BookCard({ book, onDelete }: { book: Book; onDelete: (b: Book) => void }) {
  const hue = coverHue(book.title)
  return (
    <li className="book">
      <div
        className="book__cover"
        style={{ ['--hue' as string]: hue }}
        aria-hidden="true"
      >
        <span>{book.title.slice(0, 1).toUpperCase()}</span>
      </div>
      <div className="book__body">
        <h3 className="book__title">{book.title}</h3>
        <p className="book__author">{book.author || 'Unknown author'}</p>
        <p className="book__meta">
          {book.pageCount ? `${book.pageCount} pages · ` : ''}
          {formatSize(book.fileSizeBytes)}
        </p>
        <div className="book__foot">
          <span className={`status status--${book.status.toLowerCase()}`}>
            {STATUS_LABEL[book.status]}
          </span>
          <button
            type="button"
            className="link-btn"
            onClick={() => onDelete(book)}
            aria-label={`Remove ${book.title}`}
          >
            Remove
          </button>
        </div>
      </div>
    </li>
  )
}

export function LibraryPage() {
  const [books, setBooks] = useState<Book[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async (signal?: AbortSignal) => {
    try {
      setBooks(await booksApi.list(signal))
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      setError(errorMessage(err))
      setBooks((prev) => prev ?? [])
    }
  }, [])

  useEffect(() => {
    const ctrl = new AbortController()
    void load(ctrl.signal)
    return () => ctrl.abort()
  }, [load])

  async function onDelete(book: Book) {
    if (!window.confirm(`Remove “${book.title}” from your library?`)) return
    try {
      await booksApi.remove(book.id)
      setBooks((prev) => prev?.filter((b) => b.id !== book.id) ?? prev)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  const loading = books === null

  return (
    <div>
      <header className="page-head">
        <div>
          <p className="eyebrow">Your shelf</p>
          <h1 className="page-title">Library</h1>
        </div>
        <Link to="/library/add" className="btn btn--primary">
          Add book
        </Link>
      </header>

      {error && (
        <div className="stack">
          <Alert>{error}</Alert>
          <button type="button" className="btn btn--ghost btn--sm" onClick={() => {
              setError(null)
              void load()
            }}>
            Try again
          </button>
        </div>
      )}

      {loading && (
        <ul className="book-grid" aria-busy="true" aria-label="Loading books">
          {[0, 1, 2].map((i) => (
            <li key={i} className="book book--skeleton" />
          ))}
        </ul>
      )}

      {!loading && books.length === 0 && !error && (
        <div className="empty">
          <svg width="92" height="92" viewBox="0 0 96 96" fill="none" aria-hidden="true">
            <rect x="14" y="22" width="14" height="56" rx="2" stroke="currentColor" strokeWidth="2" />
            <rect x="32" y="14" width="16" height="64" rx="2" stroke="currentColor" strokeWidth="2" />
            <path d="M56 78 52 24l15-3 14 54-25 3Z" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" />
            <path d="M12 82h72" stroke="var(--brass)" strokeWidth="2" strokeLinecap="round" />
          </svg>
          <h2>Your shelf is waiting</h2>
          <p>
            Add your first book and Book Buddy will be ready to talk about it — characters, themes,
            that one confusing chapter.
          </p>
          <Link to="/library/add" className="btn btn--primary btn--lg">
            Add your first book
          </Link>
        </div>
      )}

      {!loading && books.length > 0 && (
        <ul className="book-grid">
          {books.map((b) => (
            <BookCard key={b.id} book={b} onDelete={onDelete} />
          ))}
        </ul>
      )}
    </div>
  )
}
