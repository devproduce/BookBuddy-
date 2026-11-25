import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import type { CrisisContact } from '../api/auth'
import { accountApi } from '../api/memory'
import { quotaApi, type QuotaStatus } from '../api/quota'
import { settingsApi, type Settings } from '../api/settings'
import { useAuth } from '../auth/useAuth'
import { Alert } from '../components/Alert'
import { errorMessage, formatWait } from '../components/errorMessage'
import { strings } from '../i18n'

const LANGUAGES = [
  { code: 'en', label: 'English' },
  { code: 'hi', label: 'हिन्दी (Hindi)' },
  { code: 'es', label: 'Español' },
  { code: 'fr', label: 'Français' },
  { code: 'de', label: 'Deutsch' },
]

const MAX_CONTACTS = 5

export function SettingsPage() {
  const { user, logout, me } = useAuth()
  const navigate = useNavigate()
  const [settings, setSettings] = useState<Settings | null>(null)
  const [contacts, setContacts] = useState<CrisisContact[]>([])
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [deleteText, setDeleteText] = useState('')
  const [deleting, setDeleting] = useState(false)
  /** undefined = loading, null = could not be read */
  const [quota, setQuota] = useState<QuotaStatus | null | undefined>(undefined)

  useEffect(() => {
    const ctrl = new AbortController()
    settingsApi
      .get(ctrl.signal)
      .then((s) => {
        setSettings(s)
        setContacts(s.crisisContacts)
      })
      .catch((err) => {
        if (!ctrl.signal.aborted) setError(errorMessage(err))
      })
    quotaApi
      .status(ctrl.signal)
      .then((q) => {
        if (!ctrl.signal.aborted) setQuota(q)
      })
      .catch(() => {
        if (!ctrl.signal.aborted) setQuota(null)
      })
    return () => ctrl.abort()
  }, [])

  async function save(input: Parameters<typeof settingsApi.update>[0], message: string) {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const next = await settingsApi.update(input)
      setSettings(next)
      setContacts(next.crisisContacts)
      setNotice(message)
      await me()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  async function onExport() {
    setExporting(true)
    setError(null)
    setNotice(null)
    try {
      await accountApi.downloadExport()
      setNotice('Your export has been downloaded.')
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setExporting(false)
    }
  }

  async function onDeleteAll(e: FormEvent) {
    e.preventDefault()
    if (deleteText.trim() !== 'DELETE') return
    if (!window.confirm('Last chance: delete ALL your books, conversations and memories? This cannot be undone.')) return
    setDeleting(true)
    setError(null)
    setNotice(null)
    try {
      const r = await accountApi.deleteAllData()
      setConfirmingDelete(false)
      setDeleteText('')
      setNotice(
        `Deleted ${r.books} ${r.books === 1 ? 'book' : 'books'}, ${r.conversations} ${r.conversations === 1 ? 'conversation' : 'conversations'} and ${r.memories} ${r.memories === 1 ? 'memory' : 'memories'}.`,
      )
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setDeleting(false)
    }
  }

  function onSaveContacts(e: FormEvent) {
    e.preventDefault()
    const cleaned = contacts
      .map((c) => ({ name: c.name.trim(), phone: c.phone.trim() }))
      .filter((c) => c.name || c.phone)
    void save({ crisisContacts: cleaned }, 'Trusted contacts saved.')
  }

  function updateContact(i: number, change: Partial<CrisisContact>) {
    setContacts((cs) => cs.map((c, idx) => (idx === i ? { ...c, ...change } : c)))
  }

  function onLogout() {
    logout()
    navigate('/login', { replace: true })
  }

  const language = settings?.defaultLanguage ?? user?.defaultLanguage ?? 'en'
  const consented = settings?.consent.accepted ?? Boolean(user?.consentAcceptedAt)

  return (
    <div className="narrow">
      <header className="page-head page-head--stack">
        <p className="eyebrow">Preferences</p>
        <h1 className="page-title">Settings</h1>
        <p className="lede">Language, safety contacts, and how Book Buddy looks after your data.</p>
      </header>

      <Alert>{error}</Alert>
      {notice && (
        <p className="notice" role="status">
          {notice}
        </p>
      )}

      <section className="panel">
        <header className="panel__head">
          <h2>Account</h2>
        </header>
        <dl className="kv">
          <div>
            <dt>Name</dt>
            <dd>{user?.displayName}</dd>
          </div>
          <div>
            <dt>Email</dt>
            <dd>{user?.email}</dd>
          </div>
        </dl>
      </section>

      <section className="panel" aria-labelledby="quota-heading">
        <header className="panel__head">
          <h2 id="quota-heading">{strings.quota.title}</h2>
          {quota && (
            <span className={`tag${quota.state === 'OK' ? '' : ' tag--warn'}`}>{strings.quota.states[quota.state]}</span>
          )}
        </header>
        <p className="panel__note">{strings.quota.lede}</p>
        {quota === undefined ? (
          <p className="hint">{strings.a11y.loading}</p>
        ) : quota === null ? (
          <p className="hint">{strings.quota.unavailable}</p>
        ) : (
          <>
            <div
              className="progress"
              role="progressbar"
              aria-label={strings.quota.title}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={quota.percentUsed}
              aria-valuetext={strings.quota.percentUsed(quota.percentUsed)}
            >
              <div className="progress__bar" style={{ width: `${quota.percentUsed}%` }} />
            </div>
            <p className="panel__note">
              {strings.quota.percentUsed(quota.percentUsed)}. {strings.quota.resetsIn(formatWait(quota.resetsInSeconds))}{' '}
              {quota.state === 'EXHAUSTED'
                ? strings.quota.explainExhausted
                : quota.state === 'LOW'
                  ? strings.quota.explainLow
                  : quota.backgroundPaused
                    ? strings.quota.explainBackground
                    : strings.quota.explainOk}
            </p>
          </>
        )}
      </section>

      <section className="panel">
        <header className="panel__head">
          <h2>Language</h2>
        </header>
        <label className="field">
          <span className="field__label">Preferred language</span>
          <select
            value={language}
            disabled={busy || !settings}
            onChange={(e) => void save({ defaultLanguage: e.target.value }, 'Language saved.')}
          >
            {LANGUAGES.map((l) => (
              <option key={l.code} value={l.code}>
                {l.label}
              </option>
            ))}
          </select>
        </label>
        <p className="panel__note">
          Book Buddy still follows the language you write in; this is the default when it can't tell.
        </p>
      </section>

      <section className="panel">
        <header className="panel__head">
          <h2>Privacy &amp; consent</h2>
          <span className="tag">{consented ? 'Accepted' : 'Not accepted'}</span>
        </header>
        <p className="panel__note">
          {consented
            ? 'You agreed to how Book Buddy uses your messages to write replies.'
            : 'Chat is turned off until you accept the consent notice.'}
          {settings?.consent.acceptedAt && ` Accepted ${new Date(settings.consent.acceptedAt).toLocaleDateString()}.`}
        </p>
        <div className="panel__actions">
          {consented ? (
            <button
              type="button"
              className="btn btn--sm btn--danger"
              disabled={busy}
              onClick={() => {
                if (window.confirm('Withdraw consent? Chat will be turned off until you accept again.')) {
                  void save({ consent: { accepted: false } }, 'Consent withdrawn. Chat is turned off.')
                }
              }}
            >
              Withdraw consent
            </button>
          ) : (
            <button
              type="button"
              className="btn btn--sm btn--primary"
              disabled={busy}
              onClick={() => void save({ consent: { accepted: true } }, 'Thanks — chat is turned on.')}
            >
              Accept consent
            </button>
          )}
        </div>
      </section>

      <section className="panel">
        <header className="panel__head">
          <h2>Trusted contacts</h2>
        </header>
        <p className="panel__note">
          If you ever seem to be in distress, these numbers are listed first, above the national helplines.
        </p>
        <form className="contacts" onSubmit={onSaveContacts}>
          {contacts.map((c, i) => (
            <div className="contacts__row" key={i}>
              <input
                type="text"
                placeholder="Name"
                value={c.name}
                maxLength={80}
                aria-label={`Contact ${i + 1} name`}
                onChange={(e) => updateContact(i, { name: e.target.value })}
              />
              <input
                type="text"
                inputMode="tel"
                placeholder="Phone"
                value={c.phone}
                maxLength={32}
                aria-label={`Contact ${i + 1} phone`}
                onChange={(e) => updateContact(i, { phone: e.target.value })}
              />
              <button
                type="button"
                className="btn btn--sm btn--ghost"
                onClick={() => setContacts((cs) => cs.filter((_, idx) => idx !== i))}
              >
                Remove
              </button>
            </div>
          ))}
          <div className="panel__actions">
            <button
              type="button"
              className="btn btn--sm btn--ghost"
              disabled={contacts.length >= MAX_CONTACTS}
              onClick={() => setContacts((cs) => [...cs, { name: '', phone: '' }])}
            >
              Add contact
            </button>
            <button type="submit" className="btn btn--sm btn--primary" disabled={busy || !settings}>
              Save contacts
            </button>
          </div>
        </form>
        {settings && settings.crisisResources.length > 0 && (
          <p className="panel__note">
            National helplines:{' '}
            {settings.crisisResources.map((r) => `${r.name} ${r.phone}`).join(' · ')}
          </p>
        )}
      </section>

      <section className="panel">
        <header className="panel__head">
          <h2>Memory</h2>
          <Link to="/memory" className="link-btn">
            Manage memories
          </Link>
        </header>
        <div className="toggle-row toggle-row--live">
          <div>
            <strong>Let Book Buddy remember me</strong>
            <p className="panel__note">
              Remember your tastes and past conversations to make recommendations more personal.
            </p>
          </div>
          <label className="switch switch--live" aria-label="Let Book Buddy remember me">
            <input
              type="checkbox"
              checked={settings?.memoryEnabled ?? false}
              disabled={busy || !settings}
              onChange={(e) =>
                void save(
                  { memoryEnabled: e.target.checked },
                  e.target.checked ? 'Memory is on.' : 'Memory is paused.',
                )
              }
            />
            <span className="switch__track" />
          </label>
        </div>
      </section>

      <section className="panel">
        <header className="panel__head">
          <h2>Your data</h2>
        </header>
        <p className="panel__note">
          Download a copy of everything Book Buddy holds about you: books, conversations and memories, as one JSON file.
        </p>
        <div className="panel__actions">
          <button type="button" className="btn btn--sm btn--ghost" disabled={exporting} onClick={() => void onExport()}>
            {exporting ? 'Preparing…' : 'Export my data'}
          </button>
        </div>

        <div className="danger-zone">
          <h3 className="danger-zone__title">Delete all my data</h3>
          <p className="panel__note">
            Permanently removes every book, conversation and memory. Your account and settings stay. This can't be
            undone, so consider exporting first.
          </p>
          {confirmingDelete ? (
            <form className="danger-zone__confirm" onSubmit={onDeleteAll}>
              <label className="field">
                <span className="field__label">Type DELETE to confirm</span>
                <input
                  type="text"
                  value={deleteText}
                  autoComplete="off"
                  autoFocus
                  spellCheck={false}
                  placeholder="DELETE"
                  onChange={(e) => setDeleteText(e.target.value)}
                />
              </label>
              <div className="panel__actions">
                <button
                  type="submit"
                  className="btn btn--sm btn--danger"
                  disabled={deleting || deleteText.trim() !== 'DELETE'}
                >
                  {deleting ? 'Deleting…' : 'Permanently delete everything'}
                </button>
                <button
                  type="button"
                  className="btn btn--sm btn--ghost"
                  disabled={deleting}
                  onClick={() => {
                    setConfirmingDelete(false)
                    setDeleteText('')
                  }}
                >
                  Cancel
                </button>
              </div>
            </form>
          ) : (
            <div className="panel__actions">
              <button type="button" className="btn btn--sm btn--danger" onClick={() => setConfirmingDelete(true)}>
                Delete all data…
              </button>
            </div>
          )}
        </div>
      </section>

      <button type="button" className="btn btn--danger btn--block" onClick={onLogout}>
        Log out
      </button>
    </div>
  )
}