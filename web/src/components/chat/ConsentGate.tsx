import { useCallback, useState, type ReactNode } from 'react'
import { settingsApi } from '../../api/settings'
import { useAuth } from '../../auth/useAuth'
import { Alert } from '../Alert'
import { errorMessage } from '../errorMessage'

/** Shows the consent notice instead of its children until the user has accepted it. */
export function ConsentGate({ children }: { children: ReactNode }) {
  const { user, me } = useAuth()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // Stable callback ref: focus the heading once when the dialog appears, not on every re-render.
  const focusOnMount = useCallback((el: HTMLElement | null) => el?.focus(), [])

  if (user?.consentAcceptedAt) return <>{children}</>

  async function accept() {
    setBusy(true)
    setError(null)
    try {
      await settingsApi.acceptConsent()
      await me()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="consent" role="dialog" aria-modal="true" aria-labelledby="consent-title">
      <div className="consent__card">
        <p className="eyebrow">Before we talk</p>
        <h1 id="consent-title" tabIndex={-1} ref={focusOnMount} className="consent__title">
          How Book Buddy uses your messages
        </h1>
        <ul className="consent__list">
          <li>
            Your messages, and short passages from your own books that match your question, are sent to an AI
            provider to write replies. Your PDFs are never shared.
          </li>
          <li>
            Book Buddy is an AI companion for reading and reflection. It is <strong>not</strong> a therapist or
            a crisis service, and it can make mistakes.
          </li>
          <li>
            If you ever feel unsafe, Book Buddy will show helplines you can call. In an emergency, call your
            local emergency number.
          </li>
          <li>
            You can delete any conversation at any time, and use <strong>Incognito</strong> for chats that
            should not be remembered.
          </li>
        </ul>
        <Alert>{error}</Alert>
        <div className="consent__actions">
          <button type="button" className="btn btn--primary" onClick={accept} disabled={busy}>
            {busy ? 'Saving…' : 'I understand and agree'}
          </button>
        </div>
      </div>
    </div>
  )
}