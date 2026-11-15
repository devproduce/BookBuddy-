import { ApiError, isQuotaProblem } from '../api/client'
import { strings } from '../i18n'

/** "45 seconds", "3 minutes", "2 hours": rounded, never "0". */
export function formatWait(seconds: number): string {
  const s = Math.max(1, Math.round(seconds))
  if (s < 90) return strings.time.seconds(s)
  if (s < 90 * 60) return strings.time.minutes(Math.round(s / 60))
  return strings.time.hours(Math.round(s / 3600))
}

export interface ApiProblem {
  status?: number | null
  code?: string | null
  message?: string | null
  retryAfterSeconds?: number | null
}

/**
 * One honest sentence for an API problem. Rate limits and an exhausted AI allowance say what is happening and, when
 * the server knows, how long to wait; everything else keeps the server's own message.
 */
export function describeProblem({ status, code, message, retryAfterSeconds }: ApiProblem): string {
  const wait = retryAfterSeconds && retryAfterSeconds > 0 ? formatWait(retryAfterSeconds) : null
  if (code === 'QUOTA_EXHAUSTED') {
    return wait ? strings.errors.quotaExhaustedWait(wait) : strings.errors.quotaExhausted
  }
  if (isQuotaProblem(status, code)) {
    return wait ? strings.errors.rateLimitedWait(wait) : strings.errors.rateLimited
  }
  const base = message || strings.errors.generic
  return wait ? strings.errors.tryAgainIn(base, wait) : base
}

export function errorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    return describeProblem({
      status: err.status,
      code: err.code,
      message: err.message,
      retryAfterSeconds: err.retryAfterSeconds,
    })
  }
  if (err instanceof Error) return err.message
  return strings.errors.generic
}
