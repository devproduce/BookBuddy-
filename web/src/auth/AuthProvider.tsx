import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { authApi, type LoginInput, type RegisterInput, type User } from '../api/auth'
import { setUnauthorizedListener, tokenStore } from '../api/client'
import { AuthContext, type AuthContextValue, type AuthStatus } from './auth-context'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [status, setStatus] = useState<AuthStatus>(() =>
    tokenStore.get() ? 'loading' : 'anonymous',
  )

  const clearSession = useCallback(() => {
    tokenStore.clear()
    setUser(null)
    setStatus('anonymous')
  }, [])

  const me = useCallback(async () => {
    if (!tokenStore.get()) {
      clearSession()
      return null
    }
    const current = await authApi.me()
    setUser(current)
    setStatus('authenticated')
    return current
  }, [clearSession])

  // Restore session on first load.
  useEffect(() => {
    if (!tokenStore.get()) return
    let cancelled = false
    authApi
      .me()
      .then((current) => {
        if (cancelled) return
        setUser(current)
        setStatus('authenticated')
      })
      .catch(() => {
        if (!cancelled) clearSession()
      })
    return () => {
      cancelled = true
    }
  }, [clearSession])

  // Expired/invalid token on any protected call logs the user out.
  useEffect(() => {
    setUnauthorizedListener(clearSession)
    return () => setUnauthorizedListener(null)
  }, [clearSession])

  const login = useCallback(
    async (input: LoginInput) => {
      const res = await authApi.login(input)
      tokenStore.set(res.token)
      try {
        await me()
      } catch (err) {
        clearSession()
        throw err
      }
    },
    [me, clearSession],
  )

  const register = useCallback(
    async (input: RegisterInput) => {
      const res = await authApi.register(input)
      tokenStore.set(res.token)
      try {
        await me()
      } catch (err) {
        clearSession()
        throw err
      }
    },
    [me, clearSession],
  )

  const value = useMemo<AuthContextValue>(
    () => ({ user, status, login, register, logout: clearSession, me }),
    [user, status, login, register, clearSession, me],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
