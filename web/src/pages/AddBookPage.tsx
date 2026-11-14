import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { booksApi, sha256Hex } from '../api/books'
import { Alert } from '../components/Alert'
import { errorMessage } from '../components/errorMessage'

const HASH_RE = /^[A-Fa-f0-9]{64}$/

export function AddBookPage() {
  const navigate = useNavigate()
  const [title, setTitle] = useState('')
  const [author, setAuthor] = useState('')
  const [pageCount, setPageCount] = useState('')
  const [fileSize, setFileSize] = useState('')
  const [contentHash, setContentHash] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function generateHash() {
    if (!title.trim()) {
      setError('Enter a title first, then we can generate a demo hash from it.')
      return
    }
    setError(null)
    setContentHash(await sha256Hex(`${title.trim()}|${author.trim()}`))
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)

    const cleanTitle = title.trim()
    if (!cleanTitle) return setError('A title is required.')

    let hash = contentHash.trim()
    if (!hash) hash = await sha256Hex(`${cleanTitle}|${author.trim()}`)
    if (!HASH_RE.test(hash)) {
      return setError('Content hash must be a 64-character hex SHA-256 string.')
    }

    // Backend requires a positive size; for the phase-1 demo fall back to the title's byte length.
    const size = fileSize.trim() ? Number(fileSize) : new TextEncoder().encode(cleanTitle).length
    if (!Number.isFinite(size) || size <= 0) return setError('File size must be a positive number.')

    const pages = pageCount.trim() ? Number(pageCount) : undefined
    if (pages !== undefined && (!Number.isInteger(pages) || pages <= 0)) {
      return setError('Page count must be a positive whole number.')
    }

    setBusy(true)
    try {
      await booksApi.create({
        title: cleanTitle,
        author: author.trim() || undefined,
        contentHash: hash.toLowerCase(),
        fileSizeBytes: Math.round(size),
        pageCount: pages,
      })
      navigate('/library')
    } catch (err) {
      setError(errorMessage(err))
      setBusy(false)
    }
  }

  return (
    <div className="narrow">
      <Link to="/library" className="back">
        ← Library
      </Link>
      <header className="page-head page-head--stack">
        <p className="eyebrow">New book</p>
        <h1 className="page-title">Add a book</h1>
        <p className="lede">
          File upload is coming soon. For now, enter the details and a content hash — or let us
          generate one from the title for a demo.
        </p>
      </header>

      <form className="form form--card" onSubmit={onSubmit}>
        <Alert>{error}</Alert>

        <label className="field">
          <span className="field__label">Title</span>
          <input
            required
            maxLength={300}
            value={title}
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
            onChange={(e) => setAuthor(e.target.value)}
            placeholder="Ursula K. Le Guin"
          />
        </label>

        <div className="field-row">
          <label className="field">
            <span className="field__label">
              Pages <span className="field__opt">optional</span>
            </span>
            <input
              type="number"
              inputMode="numeric"
              min={1}
              value={pageCount}
              onChange={(e) => setPageCount(e.target.value)}
              placeholder="304"
            />
          </label>
          <label className="field">
            <span className="field__label">
              File size (bytes) <span className="field__opt">optional</span>
            </span>
            <input
              type="number"
              inputMode="numeric"
              min={1}
              value={fileSize}
              onChange={(e) => setFileSize(e.target.value)}
              placeholder="2400000"
            />
          </label>
        </div>

        <label className="field">
          <span className="field__label">
            Content hash <span className="field__opt">SHA-256, 64 hex chars</span>
          </span>
          <input
            className="mono"
            spellCheck={false}
            autoComplete="off"
            value={contentHash}
            onChange={(e) => setContentHash(e.target.value)}
            placeholder="Leave blank to auto-generate from the title"
          />
          <button type="button" className="link-btn field__action" onClick={generateHash}>
            Generate from title
          </button>
        </label>

        <div className="form__actions">
          <Link to="/library" className="btn btn--ghost">
            Cancel
          </Link>
          <button className="btn btn--primary" type="submit" disabled={busy}>
            {busy ? 'Adding…' : 'Add to library'}
          </button>
        </div>
      </form>
    </div>
  )
}
