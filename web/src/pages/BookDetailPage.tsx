import { useCallback, useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  booksApi,
  formatPages,
  isTerminalStatus,
  pollUntilDone,
  ragApi,
  sha256File,
  STAGE_LABEL,
  STATUS_LABEL,
  type AskResult,
  type Book,
  type BookProcessingStatus,
  type ChunkPage,
} from '../api/books'
import { ApiError } from '../api/client'
import { Alert } from '../components/Alert'
import { errorMessage } from '../components/errorMessage'

const isAbort = (err: unknown) => err instanceof DOMException && err.name === 'AbortError'

const SECTION_LABEL: Record<string, string> = {
  BODY: 'Body',
  FRONT_MATTER: 'Front matter',
  BACK_MATTER: 'Back matter',
}

function renderAnswer(result: AskResult) {
  return result.answer.split(/(\[\d+\])/g).map((part, i) => {
    const m = /^\[(\d+)\]$/.exec(part)
    if (!m) return <span key={i}>{part}</span>
    const cite = result.citations[Number(m[1]) - 1]
    return (
      <span key={i} className="cite" title={cite ? cite.bookTitle : undefined}>
        {part}
      </span>
    )
  })
}

export function BookDetailPage() {
  const { id = '' } = useParams()
  const filePicker = useRef<HTMLInputElement>(null)

  const [book, setBook] = useState<Book | null>(null)
  const [status, setStatus] = useState<BookProcessingStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [actionBusy, setActionBusy] = useState(false)
  const [needFile, setNeedFile] = useState(false)

  const [chunkPage, setChunkPage] = useState(0)
  const [chunks, setChunks] = useState<ChunkPage | null>(null)
  const [chunksError, setChunksError] = useState<string | null>(null)

  const [question, setQuestion] = useState('')
  const [asking, setAsking] = useState(false)
  const [askError, setAskError] = useState<string | null>(null)
  const [result, setResult] = useState<AskResult | null>(null)

  const refreshBook = useCallback(async () => {
    try {
      setBook(await booksApi.get(id))
    } catch (err) {
      if (!isAbort(err)) setError(errorMessage(err))
    }
  }, [id])

  // initial load
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setNotFound(false)
    setBook(null)
    setStatus(null)
    setChunks(null)
    setResult(null)
    setChunkPage(0)
    Promise.all([booksApi.get(id), booksApi.status(id)])
      .then(([b, s]) => {
        if (cancelled) return
        setBook(b)
        setStatus(s)
      })
      .catch((err) => {
        if (cancelled) return
        if (err instanceof ApiError && err.status === 404) setNotFound(true)
        else setError(errorMessage(err))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [id])

  // poll while a job is working on this book
  const active = status !== null && status.jobId !== null && !isTerminalStatus(status)
  useEffect(() => {
    if (!active) return
    const ctrl = new AbortController()
    pollUntilDone(id, setStatus, ctrl.signal, 2000)
      .then(() => refreshBook())
      .catch((err) => {
        if (!isAbort(err)) setError(errorMessage(err))
      })
    return () => ctrl.abort()
  }, [active, id, refreshBook])

  // chunk preview
  const chunkCount = book?.chunkCount ?? 0
  const ready = book?.status === 'COMPLETED'
  useEffect(() => {
    if (!ready || chunkCount === 0) {
      setChunks(null)
      return
    }
    const ctrl = new AbortController()
    setChunksError(null)
    booksApi
      .chunks(id, chunkPage, 5, ctrl.signal)
      .then(setChunks)
      .catch((err) => {
        if (!isAbort(err)) setChunksError(errorMessage(err))
      })
    return () => ctrl.abort()
  }, [id, ready, chunkCount, chunkPage])

  async function runProcess() {
    setActionBusy(true)
    setError(null)
    try {
      setStatus(await booksApi.process(id))
      setNeedFile(false)
    } catch (err) {
      if (err instanceof ApiError && err.code === 'FILE_REQUIRED') {
        setNeedFile(true) // the server already deleted the PDF: ask for it again
      } else {
        setError(errorMessage(err))
      }
    } finally {
      setActionBusy(false)
    }
  }

  async function onReupload(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file || !book) return
    setActionBusy(true)
    setError(null)
    try {
      const hash = await sha256File(file)
      if (hash !== book.contentHash) {
        throw new Error('That is a different file from the one originally added to this book.')
      }
      await booksApi.uploadFile(id, file)
      setStatus(await booksApi.process(id))
      setNeedFile(false)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setActionBusy(false)
    }
  }

  async function onCancel() {
    setActionBusy(true)
    try {
      setStatus(await booksApi.cancel(id))
      await refreshBook()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setActionBusy(false)
    }
  }

  async function onAsk(e: FormEvent) {
    e.preventDefault()
    const q = question.trim()
    if (!q) return
    setAsking(true)
    setAskError(null)
    try {
      setResult(await ragApi.ask(q, [id]))
    } catch (err) {
      setAskError(errorMessage(err))
    } finally {
      setAsking(false)
    }
  }

  if (loading) {
    return (
      <div className="narrow" aria-busy="true">
        <p className="lede">Loading book{'\u2026'}</p>
      </div>
    )
  }

  if (notFound || !book) {
    return (
      <div className="narrow">
        <Link to="/library" className="back">
          {'\u2190'} Library
        </Link>
        <Alert>{notFound ? 'This book could not be found.' : error}</Alert>
      </div>
    )
  }

  const stage = status?.stage ?? 'QUEUED'
  const failed = book.status === 'FAILED' || book.status === 'NEEDS_OCR'
  const processing = active
  const canRetry = !processing && book.status !== 'COMPLETED' && (failed || status?.jobId == null)
  const reason = status?.failureReason ?? book.failureReason

  return (
    <div className="detail">
      <Link to="/library" className="back">
        {'\u2190'} Library
      </Link>

      <header className="detail__hero">
        <div
          className="detail__cover"
          style={{
            ['--hue' as string]: (() => {
              let h = 0
              for (const ch of book.title) h = (h * 31 + ch.charCodeAt(0)) % 360
              return h
            })(),
          }}
          aria-hidden="true"
        >
          <span>{book.title.slice(0, 1).toUpperCase()}</span>
        </div>
        <div className="detail__intro">
          <p className="eyebrow">{book.author || 'Unknown author'}</p>
          <h1 className="page-title">{book.title}</h1>
          <p className="detail__meta">
            <span className={`status status--${book.status.toLowerCase()}`}>
              {STATUS_LABEL[book.status]}
            </span>
            {book.pageCount ? <span>{book.pageCount} pages</span> : null}
            {book.chunkCount > 0 ? <span>{book.chunkCount} passages</span> : null}
            {book.language ? <span>{book.language.toUpperCase()}</span> : null}
          </p>
        </div>
      </header>

      <Alert>{error}</Alert>

      {/* processing status */}
      {(processing || failed || canRetry) && (
        <section className="panel" aria-labelledby="proc-h">
          <h2 id="proc-h" className="panel__title">
            Processing
          </h2>

          {processing && (
            <div className="progress-block" aria-live="polite">
              <div className="progress">
                <div
                  className="progress__bar"
                  style={{ width: `${Math.max(status?.progress ?? 0, 3)}%` }}
                />
              </div>
              <p className="hint">
                {STAGE_LABEL[stage]}
                {status ? ` \u00B7 ${status.progress}%` : ''}
              </p>
            </div>
          )}

          {failed && (
            <p className="panel__note panel__note--bad">
              {book.status === 'NEEDS_OCR'
                ? 'This looks like a scanned PDF with no readable text. OCR is not available yet.'
                : 'Processing failed.'}{' '}
              {reason}
            </p>
          )}

          {canRetry && !failed && (
            <p className="panel__note">
              This book has no processed text yet. Upload its PDF to make it searchable.
            </p>
          )}

          <div className="panel__actions">
            {processing && (
              <button type="button" className="btn btn--ghost btn--sm" disabled={actionBusy} onClick={onCancel}>
                Cancel
              </button>
            )}
            {canRetry && (
              <button
                type="button"
                className="btn btn--primary btn--sm"
                disabled={actionBusy}
                onClick={() => (status?.fileUploaded ? void runProcess() : setNeedFile(true))}
              >
                {failed ? 'Retry processing' : 'Process book'}
              </button>
            )}
          </div>

          {needFile && canRetry && (
            <div className="panel__reupload">
              <p className="hint">
                For privacy the PDF is deleted after processing. Choose the same file again to retry.
              </p>
              <input
                ref={filePicker}
                className="file-input"
                type="file"
                accept="application/pdf,.pdf"
                disabled={actionBusy}
                onChange={onReupload}
              />
            </div>
          )}
        </section>
      )}

      {/* chunks preview */}
      {ready && chunkCount > 0 && (
        <section className="panel" aria-labelledby="chunks-h">
          <h2 id="chunks-h" className="panel__title">
            Passage preview
          </h2>
          <Alert>{chunksError}</Alert>
          {!chunks && !chunksError && <p className="hint">Loading passages{'\u2026'}</p>}
          {chunks && (
            <>
              <ol className="chunks" start={chunkPage * 5 + 1}>
                {chunks.items.map((c) => (
                  <li key={c.id} className="chunk">
                    <p className="chunk__meta">
                      <span>#{c.chunkIndex + 1}</span>
                      {formatPages(c.pageStart, c.pageEnd) && <span>{formatPages(c.pageStart, c.pageEnd)}</span>}
                      {c.sectionType && c.sectionType !== 'BODY' && (
                        <span>{SECTION_LABEL[c.sectionType] ?? c.sectionType}</span>
                      )}
                      {c.chapterTitle && <span>{c.chapterTitle}</span>}
                      <span>{c.tokenCount} tokens</span>
                    </p>
                    <p className="chunk__text">
                      {c.content.length > 320 ? `${c.content.slice(0, 320)}\u2026` : c.content}
                    </p>
                    {c.content.length > 320 && (
                      <details>
                        <summary>Show full passage</summary>
                        <p className="chunk__text">{c.content}</p>
                      </details>
                    )}
                  </li>
                ))}
              </ol>
              <div className="pager">
                <button
                  type="button"
                  className="btn btn--ghost btn--sm"
                  disabled={chunkPage === 0}
                  onClick={() => setChunkPage((p) => Math.max(0, p - 1))}
                >
                  Previous
                </button>
                <span className="hint">
                  Page {chunks.page + 1} of {Math.max(chunks.totalPages, 1)}
                </span>
                <button
                  type="button"
                  className="btn btn--ghost btn--sm"
                  disabled={chunkPage + 1 >= chunks.totalPages}
                  onClick={() => setChunkPage((p) => p + 1)}
                >
                  Next
                </button>
              </div>
            </>
          )}
        </section>
      )}

      {/* ask panel */}
      <section className="panel" aria-labelledby="ask-h">
        <h2 id="ask-h" className="panel__title">
          Ask this book
        </h2>
        {!ready ? (
          <p className="hint">Questions unlock once the book has finished processing.</p>
        ) : (
          <form className="ask" onSubmit={onAsk}>
            <label className="field">
              <span className="field__label">Your question</span>
              <textarea
                rows={3}
                maxLength={2000}
                value={question}
                disabled={asking}
                onChange={(e) => setQuestion(e.target.value)}
                placeholder="Who is the narrator, and what do they want?"
              />
            </label>
            <div className="form__actions">
              <button className="btn btn--primary btn--sm" type="submit" disabled={asking || !question.trim()}>
                {asking ? 'Thinking\u2026' : 'Ask'}
              </button>
            </div>
          </form>
        )}

        <Alert>{askError}</Alert>

        {result && (
          <div className="answer" aria-live="polite">
            <p className="answer__text">{renderAnswer(result)}</p>
            {result.citations.length > 0 && (
              <>
                <h3 className="answer__sources">Sources ({result.usedChunks})</h3>
                <ol className="citations">
                  {result.citations.map((c, i) => (
                    <li key={c.chunkId}>
                      <span className="cite">[{i + 1}]</span> {c.bookTitle}
                      {formatPages(c.pageStart, c.pageEnd) ? `, ${formatPages(c.pageStart, c.pageEnd)}` : ''}
                    </li>
                  ))}
                </ol>
              </>
            )}
          </div>
        )}
      </section>
    </div>
  )
}
