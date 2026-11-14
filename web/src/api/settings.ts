import { api } from './client'
import type { CrisisContact } from './auth'

export interface CrisisResource {
  name: string
  phone: string
  /** True for the user's own trusted contacts. */
  personal: boolean
}

export interface Settings {
  consent: {
    accepted: boolean
    version: string | null
    acceptedAt: string | null
    currentVersion: string
  }
  memoryEnabled: boolean
  defaultLanguage: string | null
  crisisContacts: CrisisContact[]
  /** Built-in helplines configured on the server. */
  crisisResources: CrisisResource[]
}

export interface UpdateSettingsInput {
  consent?: { accepted: boolean; version?: string }
  memoryEnabled?: boolean
  defaultLanguage?: string
  crisisContacts?: CrisisContact[]
}

export const settingsApi = {
  get: (signal?: AbortSignal) => api.get<Settings>('/settings', { signal }),
  update: (input: UpdateSettingsInput) => api.put<Settings>('/settings', input),
  acceptConsent: (version?: string) => api.post<Settings>('/consent', version ? { version } : {}),
}