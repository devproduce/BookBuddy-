import { api } from './client'

export interface AuthResponse {
  token: string
  userId: string
  email: string
  displayName: string
}

export interface CrisisContact {
  name: string
  phone: string
}

export interface User {
  id: string
  email: string
  displayName: string
  consentVersion: string | null
  consentAcceptedAt: string | null
  memoryEnabled: boolean
  defaultLanguage: string | null
  crisisContacts: CrisisContact[]
  createdAt: string
  updatedAt: string
}

export interface LoginInput {
  email: string
  password: string
}

export interface RegisterInput {
  email: string
  displayName: string
  password: string
  consentAccepted: boolean
  consentVersion?: string
  defaultLanguage?: string
}

export const authApi = {
  login: (input: LoginInput) =>
    api.post<AuthResponse>('/auth/login', input, { skipAuthRedirect: true }),
  register: (input: RegisterInput) =>
    api.post<AuthResponse>('/auth/register', input, { skipAuthRedirect: true }),
  me: () => api.get<User>('/auth/me'),
}
