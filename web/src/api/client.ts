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
    const parsed = data as Partial<ApiErrorBody> | undefined
    const retryAfterHeader = Number(res.headers.get('Retry-After'))
    const error = new ApiError(res.status, {
      code: parsed?.code ?? `HTTP_${res.status}`,
      message: parsed?.message ?? res.statusText ?? 'Something went wrong.',
      retryable: parsed?.retryable ?? res.status >= 500,
      retryAfterSeconds:
        parsed?.retryAfterSeconds ?? (Number.isFinite(retryAfterHeader) && retryAfterHeader > 0 ? retryAfterHeader : null),
    })
    if (res.status === 401 && !skipAuthRedirect) onUnauthorized?.()
    throw error
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
  delete: <T = void>(path: string, opts?: RequestOptions) => request<T>('DELETE', path, opts),
}
