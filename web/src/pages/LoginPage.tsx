import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { Alert } from '../components/Alert'
import { errorMessage } from '../components/errorMessage'
import { useAuth } from '../auth/useAuth'
import { strings } from '../i18n'
import { AuthLayout } from './AuthLayout'

export function LoginPage() {
  const { login } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const t = strings.auth.login

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
      <h2 className="auth__title">{t.title}</h2>
      <p className="auth__sub">{t.subtitle}</p>

      <form className="form" onSubmit={onSubmit} noValidate={false} aria-busy={busy}>
        <Alert>{error}</Alert>
        <label className="field">
          <span className="field__label">{t.email}</span>
          <input
            type="email"
            name="email"
            autoComplete="email"
            inputMode="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder={t.emailPlaceholder}
            aria-invalid={error ? true : undefined}
          />
        </label>
        <label className="field">
          <span className="field__label">{t.password}</span>
          <input
            type="password"
            name="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder={t.passwordPlaceholder}
            aria-invalid={error ? true : undefined}
          />
        </label>
        <button className="btn btn--primary btn--block" type="submit" disabled={busy}>
          {busy ? t.submitting : t.submit}
        </button>
      </form>

      <p className="auth__switch">
        {t.newHere} <Link to="/register">{t.createAccount}</Link>
      </p>
    </AuthLayout>
  )
}
