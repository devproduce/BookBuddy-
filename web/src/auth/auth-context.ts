import { createContext } from 'react'
import type { LoginInput, RegisterInput, User } from '../api/auth'

export type AuthStatus = 'loading' | 'authenticated' | 'anonymous'

export interface AuthContextValue {
  user: User | null
  status: AuthStatus
  login: (input: LoginInput) => Promise<void>
  register: (input: RegisterInput) => Promise<void>
  logout: () => void
  /** Re-fetch the current user from /auth/me. */
  me: () => Promise<User | null>
}

export const AuthContext = createContext<AuthContextValue | null>(null)
