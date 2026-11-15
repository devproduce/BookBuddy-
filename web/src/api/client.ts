export const API_BASE_URL: string = (
  import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api/v1'
).replace(/\/+$/, '')

export const TOKEN_KEY = 'bookbuddy.token'

export interface ApiErrorBody {
  code: string
  message: string
  retryable: boolean
  retryAfterSeconds?: number | null
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly retryable: boolean
  readonly retryAfterSeconds: number | null

  constructor(status: number, body: ApiErrorBody) {
    super(body.message)
    this.name = 'ApiError'
    this.status = status
    this.code = body.code
    this.retryable = body.retryable
    this.retryAfterSeconds = body.retryAfterSeconds ?? null
  }
}

/** True for "slow down / allowance used up" answers: HTTP 429 or the RATE_LIMITED / QUOTA_EXHAUSTED codes. */
export function isQuotaProblem(status: number | null | undefined, code: string | null | undefined): boolean {
  return status === 429 || code === 'RATE_LIMITED' || code === 'QUOTA_EXHAUSTED'
}

export const tokenStore = {
  get: (): string | null => localStorage.getItem(TOKEN_KEY),
  set: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  clear: () => localStorage.removeItem(TOKEN_KEY),
}

type UnauthorizedListener = () => void
let onUnauthorized: UnauthorizedListener | null = null

/** AuthContext registers here so a 401 on a protected call logs the user out. */
export function setUnauthorizedListener(listener: UnauthorizedListener | null) {
  onUnauthorized = listener
}

interface RequestOptions {
  body?: unknown
  /** Multipart upload; the browser sets the Content-Type boundary. */
  formData?: FormData
  /** Skip the global 401 handler (used for login/register). */
  skipAuthRedirect?: boolean
  signal?: AbortSignal
}

function toApiError(res: Response, data: unknown, skipAuthRedirect = false): ApiError {
  const parsed = data as Partial<ApiErrorBody> | undefined
  const retryAfterHeader = Number(res.headers.get('Retry-After'))
  const error = new ApiError(res.status, {
    code: parsed?.code ?? (res.status === 429 ? 'RATE_LIMITED' : `HTTP_${res.status}`),
    message: parsed?.message ?? res.statusText ?? 'Something went wrong.',
    retryable: parsed?.retryable ?? (res.status >= 500 || res.status === 429),
    retryAfterSeconds:
      parsed?.retryAfterSeconds ?? (Number.isFinite(retryAfterHeader) && retryAfterHeader > 0 ? retryAfterHeader : null),
  })
  if (res.status === 401 && !skipAuthRedirect) onUnauthorized?.()
  return error
}

async function request<T>(
  method: string,
  path: string,
  { body, formData, skipAuthRedirect, signal }: RequestOptions = {},
): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  const token = tokenStore.get()
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined && !formData) headers['Content-Type'] = 'application/json'

  let res: Response
  try {
    res = await fetch(`${API_BASE_URL}${path}`, {
      method,
      headers,
      body: formData ?? (body === undefined ? undefined : JSON.stringify(body)),
      signal,
    })
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err
    throw new ApiError(0, {
      code: 'NETWORK_ERROR',
      message: "Can't reach Book Buddy right now. Check your connection and try again.",
      retryable: true,
    })
  }

  if (res.status === 204) return undefined as T

  const text = await res.text()
  let data: unknown = undefined
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = undefined
    }
  }

  if (!res.ok) {
    throw toApiError(res, data, skipAuthRedirect)
  }

  return data as T
}

export const api = {
  get: <T>(path: string, opts?: RequestOptions) => request<T>('GET', path, opts),
  post: <T>(path: string, body?: unknown, opts?: RequestOptions) =>
    request<T>('POST', path, { ...opts, body }),
  upload: <T>(path: string, formData: FormData, opts?: RequestOptions) =>
    request<T>('POST', path, { ...opts, formData }),
  patch: <T>(path: string, body?: unknown, opts?: RequestOptions) =>
    request<T>('PATCH', path, { ...opts, body }),
  put: <T>(path: string, body?: unknown, opts?: RequestOptions) =>
    request<T>('PUT', path, { ...opts, body }),
  delete: <T = void>(path: string, opts?: RequestOptions) => request<T>('DELETE', path, opts),
}

/**
 * Opens a POST that answers with Server-Sent Events. Resolves with the live Response once the server accepted the
 * request (2xx); non-2xx answers are turned into an {@link ApiError} exactly like regular calls.
 */
export async function openEventStream(
  path: string,
  body: unknown,
  signal?: AbortSignal,
): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'text/event-stream' }
  const token = tokenStore.get()
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  let res: Response
  try {
    res = await fetch(`${API_BASE_URL}${path}`, {
      method: 'POST',
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    })
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err
    throw new ApiError(0, {
      code: 'NETWORK_ERROR',
      message: "Can't reach Book Buddy right now. Check your connection and try again.",
      retryable: true,
    })
  }
  if (!res.ok) {
    const text = await res.text()
    let data: unknown = undefined
    try {
      data = text ? JSON.parse(text) : undefined
    } catch {
      data = undefined
    }
    throw toApiError(res, data)
  }
  return res
}