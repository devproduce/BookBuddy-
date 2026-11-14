import { ApiError } from '../api/client'

export function errorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.retryAfterSeconds) return `${err.message} Try again in ${err.retryAfterSeconds}s.`
    return err.message
  }
  if (err instanceof Error) return err.message
  return 'Something went wrong.'
}
