import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { Alert } from '../components/Alert'
import { errorMessage } from '../components/errorMessage'
import { useAuth } from '../auth/useAuth'
import { AuthLayout } from './AuthLayout'

export function LoginPage() {
  const { login } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await login({ email: email.trim(), password })
      // PublicOnlyRoute redirects once the session is established.
    } catch (err) {
      setError(errorMessage(err))
      setBusy(false)
    }
  }

  return (
    <AuthLayout>
      <h2 className="auth__title">Welcome back</h2>
      <p className="auth__sub">Sign in to pick up where you left off.</p>

      <form className="form" onSubmit={onSubmit} noValidate={false}>
        <Alert>{error}</Alert>
        <label className="field">
          <span className="field__label">Email</span>
          <input
            type="email"
            autoComplete="email"
            inputMode="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@example.com"
          />
        </label>
        <label className="field">
          <span className="field__label">Password</span>
          <input
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="••••••••"
          />
        </label>
        <button className="btn btn--primary btn--block" type="submit" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>

      <p className="auth__switch">
        New to Book Buddy? <Link to="/register">Create an account</Link>
      </p>
    </AuthLayout>
  )
}
