import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { Alert } from '../components/Alert'
import { errorMessage } from '../components/errorMessage'
import { useAuth } from '../auth/useAuth'
import { AuthLayout } from './AuthLayout'

export const CONSENT_VERSION = '1.0'

export function RegisterPage() {
  const { register } = useAuth()
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [consent, setConsent] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    if (password.length < 8) {
      setError('Password must be at least 8 characters.')
      return
    }
    if (!consent) {
      setError('Please accept the terms to create your account.')
      return
    }
    setBusy(true)
    try {
      await register({
        email: email.trim(),
        displayName: displayName.trim(),
        password,
        consentAccepted: true,
        consentVersion: CONSENT_VERSION,
        defaultLanguage: 'en',
      })
    } catch (err) {
      setError(errorMessage(err))
      setBusy(false)
    }
  }

  return (
    <AuthLayout>
      <h2 className="auth__title">Start your shelf</h2>
      <p className="auth__sub">Create an account and bring your first book along.</p>

      <form className="form" onSubmit={onSubmit}>
        <Alert>{error}</Alert>
        <label className="field">
          <span className="field__label">Display name</span>
          <input
            type="text"
            autoComplete="nickname"
            required
            maxLength={100}
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            placeholder="What should we call you?"
          />
        </label>
        <label className="field">
          <span className="field__label">Email</span>
          <input
            type="email"
            autoComplete="email"
            inputMode="email"
            required
            maxLength={254}
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@example.com"
          />
        </label>
        <label className="field">
          <span className="field__label">Password</span>
          <input
            type="password"
            autoComplete="new-password"
            required
            minLength={8}
            maxLength={128}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="At least 8 characters"
          />
        </label>

        <label className="check">
          <input
            type="checkbox"
            required
            checked={consent}
            onChange={(e) => setConsent(e.target.checked)}
          />
          <span>
            I agree to the Terms and Privacy Policy, and understand Book Buddy processes the books I
            add in order to chat about them.
          </span>
        </label>

        <button className="btn btn--primary btn--block" type="submit" disabled={busy}>
          {busy ? 'Creating account…' : 'Create account'}
        </button>
      </form>

      <p className="auth__switch">
        Already have an account? <Link to="/login">Sign in</Link>
      </p>
    </AuthLayout>
  )
}
