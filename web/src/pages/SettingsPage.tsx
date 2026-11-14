import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import type { CrisisContact } from '../api/auth'
import { settingsApi, type Settings } from '../api/settings'
import { useAuth } from '../auth/useAuth'
import { Alert } from '../components/Alert'
import { errorMessage } from '../components/errorMessage'

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
          <span className="tag">Soon</span>
        </header>
        <div className="toggle-row">
          <div>
            <strong>Let Book Buddy remember me</strong>
            <p className="panel__note">
              Remember your tastes and past conversations to make recommendations more personal.
            </p>
          </div>
          <label className="switch" aria-label="Memory (coming soon)">
            <input type="checkbox" checked={settings?.memoryEnabled ?? false} disabled readOnly />
            <span className="switch__track" />
          </label>
        </div>
      </section>

      <button type="button" className="btn btn--danger btn--block" onClick={onLogout}>
        Log out
      </button>
    </div>
  )
}