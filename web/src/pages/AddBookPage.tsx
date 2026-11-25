import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  booksApi,
  pollUntilDone,
  sha256File,
  STAGE_LABEL,
  type BookProcessingStatus,
} from '../api/books'
import { ApiError } from '../api/client'
import { Alert } from '../components/Alert'
import { errorMessage } from '../components/errorMessage'

type Phase = 'idle' | 'hashing' | 'creating' | 'uploading' | 'processing'

function formatSize(bytes: number): string {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function titleFromFilename(name: string): string {
  return name
    .replace(/\.pdf$/i, '')
    .replace(/[_]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

export function AddBookPage() {
  const navigate = useNavigate()
  const fileInput = useRef<HTMLInputElement>(null)
  const abortRef = useRef<AbortController | null>(null)

  const [file, setFile] = useState<File | null>(null)
  const [title, setTitle] = useState('')
  const [author, setAuthor] = useState('')
  const [contentHash, setContentHash] = useState('')
  const [phase, setPhase] = useState<Phase>('idle')
  const [status, setStatus] = useState<BookProcessingStatus | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [duplicateId, setDuplicateId] = useState<string | null>(null)
  const [createdId, setCreatedId] = useState<string | null>(null)

  const busy = phase !== 'idle'

  useEffect(() => () => abortRef.current?.abort(), [])

  async function onPickFile(e: ChangeEvent<HTMLInputElement>) {
    const picked = e.target.files?.[0] ?? null
    setError(null)
    setDuplicateId(null)
    setContentHash('')
    if (!picked) {
      setFile(null)
      return
    }
    const looksLikePdf = picked.type === 'application/pdf' || /\.pdf$/i.test(picked.name)
    if (!looksLikePdf) {
      setFile(null)
      setError('Please choose a PDF file.')
      return
    }
    setFile(picked)
    setTitle((t) => t || titleFromFilename(picked.name))

    setPhase('hashing')
    try {
      setContentHash(await sha256File(picked))
    } catch (err) {
      setFile(null)
      setError(errorMessage(err))
    } finally {
      setPhase('idle')
    }
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setDuplicateId(null)

    const cleanTitle = title.trim()
    if (!file) return setError('Choose a PDF to add.')
    if (!cleanTitle) return setError('A title is required.')
    if (!contentHash) return setError('Still fingerprinting the file. Give it a moment.')

    const ctrl = new AbortController()
    abortRef.current = ctrl
    let bookId = createdId

    try {
      // 1. create (skipped when a previous attempt already created the record)
      if (!bookId) {
        setPhase('creating')
        const book = await booksApi.create({
          title: cleanTitle,
          author: author.trim() || undefined,
          contentHash,
          fileSizeBytes: file.size,
        })
        bookId = book.id
        setCreatedId(book.id)
      }

      // 2. upload the PDF (server verifies it matches the hash and deletes it after processing)
      setPhase('uploading')
      await booksApi.uploadFile(bookId, file)

      // 3. process, then poll
      setPhase('processing')
      setStatus(await booksApi.process(bookId))
      await pollUntilDone(bookId, setStatus, ctrl.signal)

      // Ready, FAILED or NEEDS_OCR: the detail page shows the outcome and how to retry
      navigate(`/library/${bookId}`)
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      if (err instanceof ApiError && err.code === 'BOOK_DUPLICATE') {
        const match = /id (\S+)$/.exec(err.message)
        setDuplicateId(match ? match[1] : null)
        setError('This exact file is already in your library.')
      } else {
        setError(errorMessage(err))
      }
      setPhase('idle')
    }
  }

  const phaseLabel =
    phase === 'hashing'
      ? 'Fingerprinting file\u2026'
      : phase === 'creating'
        ? 'Creating book\u2026'
        : phase === 'uploading'
          ? 'Uploading\u2026'
          : phase === 'processing'
            ? status
              ? `${STAGE_LABEL[status.stage]}\u2026`
              : 'Starting\u2026'
            : ''

  return (
    <div className="narrow">
      <Link to="/library" className="back">
        {'\u2190'} Library
      </Link>
      <header className="page-head page-head--stack">
        <p className="eyebrow">New book</p>
        <h1 className="page-title">Add a book</h1>
        <p className="lede">
          Choose a PDF. We fingerprint it in your browser, upload it just long enough to read it, then
          delete the file and keep only searchable passages.
        </p>
      </header>

      <form className="form form--quiet" onSubmit={onSubmit}>
        <Alert>{error}</Alert>
        {duplicateId && (
          <p className="hint">
            <Link to={`/library/${duplicateId}`}>Open the existing book</Link>
          </p>
        )}

        <div className="field">
          <span className="field__label">Place the book here</span>
          <label className={`dropzone${file ? ' is-filled' : ''}`}>
            <input
              ref={fileInput}
              className="sr-only"
              type="file"
              accept="application/pdf,.pdf"
              disabled={busy}
              onChange={onPickFile}
            />
            {file ? (
              <span className="dropzone__chosen">
                <strong>{file.name}</strong>
                <span>
                  {formatSize(file.size)}
                  {contentHash && (
                    <>
                      {' '}
                      {'\u00B7'} <span className="mono hint__hash">SHA-256 {contentHash.slice(0, 12)}{'\u2026'}</span>
                    </>
                  )}
                </span>
              </span>
            ) : (
              <span className="dropzone__prompt">
                Choose a PDF
                <span className="dropzone__hint">Fingerprinted in your browser, then read and discarded</span>
              </span>
            )}
          </label>
        </div>

        <label className="field">
          <span className="field__label">Title</span>
          <input
            required
            maxLength={300}
            value={title}
            disabled={busy}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="The Left Hand of Darkness"
          />
        </label>

        <label className="field">
          <span className="field__label">
            Author <span className="field__opt">optional</span>
          </span>
          <input
            maxLength={200}
            value={author}
            disabled={busy}
            onChange={(e) => setAuthor(e.target.value)}
            placeholder="Ursula K. Le Guin"
          />
        </label>

        {phase !== 'idle' && (
          <div className="progress-block" aria-live="polite">
            <div className="progress">
              <div
                className="progress__bar"
                style={{
                  width: `${phase === 'processing' ? Math.max(status?.progress ?? 0, 3) : phase === 'uploading' ? 5 : 2}%`,
                }}
              />
            </div>
            <p className="hint">{phaseLabel}</p>
          </div>
        )}

        <div className="form__actions">
          <Link to="/library" className="btn btn--ghost">
            Cancel
          </Link>
          <button className="btn btn--primary" type="submit" disabled={busy || !file || !contentHash}>
            {busy ? 'Working\u2026' : 'Add to library'}
          </button>
        </div>
      </form>
    </div>
  )
}
