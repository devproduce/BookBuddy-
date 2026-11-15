import { api } from './client'

export type QuotaState = 'OK' | 'LOW' | 'EXHAUSTED'

/** Soft view of the shared daily AI allowance (GET /quota/status). Coarse on purpose: no raw counters. */
export interface QuotaStatus {
  state: QuotaState
  /** 0-100. */
  percentUsed: number
  /** ISO time the daily counter starts again. */
  resetsAt: string
  resetsInSeconds: number
  /** True once background work (memory clean-up and similar) has started backing off. */
  backgroundPaused: boolean
}

export const quotaApi = {
  status: (signal?: AbortSignal) => api.get<QuotaStatus>('/quota/status', { signal }),
}
