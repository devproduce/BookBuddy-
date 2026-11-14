import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'

const LANG_KEY = 'bookbuddy.language'

const LANGUAGES = [
  { code: 'en', label: 'English' },
  { code: 'hi', label: 'हिन्दी (Hindi)' },
  { code: 'es', label: 'Español' },
  { code: 'fr', label: 'Français' },
  { code: 'de', label: 'Deutsch' },
]

export function SettingsPage() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const [language, setLanguage] = useState(
    () => localStorage.getItem(LANG_KEY) ?? user?.defaultLanguage ?? 'en',
  )

  function onLanguage(code: string) {
    setLanguage(code)
    localStorage.setItem(LANG_KEY, code)
  }

  function onLogout() {
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="narrow">
      <header className="page-head page-head--stack">
        <p className="eyebrow">Preferences</p>
        <h1 className="page-title">Settings</h1>
      </header>

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
          <select value={language} onChange={(e) => onLanguage(e.target.value)}>
            {LANGUAGES.map((l) => (
              <option key={l.code} value={l.code}>
                {l.label}
              </option>
            ))}
          </select>
        </label>
        <p className="panel__note">Saved on this device for now. It will sync to your account soon.</p>
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
            <input type="checkbox" checked={user?.memoryEnabled ?? false} disabled readOnly />
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
