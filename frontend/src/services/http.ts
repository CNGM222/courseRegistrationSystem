import type { ApiErrorBody } from '../types/api'
export class HttpError extends Error {
  constructor(
    public status: number,
    public body: ApiErrorBody = {},
  ) {
    super(body.message || `请求失败（${status}）`)
  }
  get code() {
    return this.body.code
  }
  get details() {
    return this.body.details
  }
  get requestId() {
    return this.body.requestId
  }
}
let csrf: { token: string; headerName: string } | null = null
let csrfPromise: Promise<void> | null = null
export function clearCsrf() {
  csrf = null
}
export async function loadCsrf(): Promise<void> {
  if (csrfPromise) return csrfPromise
  csrfPromise = request<{ token: string; headerName?: string }>('/auth/csrf')
    .then(({ data }) => {
      if (!data?.token) throw new HttpError(502, { message: '安全令牌响应无效，请联系管理员' })
      csrf = { token: data.token, headerName: data.headerName || 'X-CSRF-TOKEN' }
    })
    .finally(() => {
      csrfPromise = null
    })
  return csrfPromise
}
export async function request<T>(
  path: string,
  options: RequestInit = {},
  timeout = 15000,
): Promise<{ data: T; etag?: string }> {
  const writing = !['GET', 'HEAD'].includes((options.method || 'GET').toUpperCase())
  if (writing && !csrf) await loadCsrf()
  const headers = new Headers(options.headers)
  headers.set('Accept', 'application/json')
  if (options.body) headers.set('Content-Type', 'application/json')
  if (writing && csrf) headers.set(csrf.headerName, csrf.token)
  const controller = new AbortController(),
    timer = setTimeout(() => controller.abort(), timeout)
  try {
    const response = await fetch(`/api/v1${path}`, {
      ...options,
      headers,
      credentials: 'include',
      cache: 'no-store',
      signal: controller.signal,
    })
    const raw = await response.text()
    let body: any = null
    try {
      body = raw ? JSON.parse(raw) : null
    } catch {
      /* Proxy errors must not be rendered as HTML. */
    }
    if (!response.ok) {
      if (response.status === 401 && path !== '/auth/login')
        window.dispatchEvent(new Event('crs:unauthorized'))
      throw new HttpError(
        response.status,
        body && typeof body === 'object'
          ? body
          : { message: `服务暂不可用（${response.status}），请重试或联系管理员` },
      )
    }
    if (response.status !== 204 && (!body || !('data' in body)))
      throw new HttpError(502, { message: '服务器响应格式无效，请联系管理员' })
    return { data: body?.data as T, etag: response.headers.get('ETag') || undefined }
  } catch (error) {
    if (error instanceof HttpError) throw error
    throw new HttpError(0, {
      code: controller.signal.aborted ? 'TIMEOUT' : 'NETWORK_ERROR',
      message: writing
        ? '连接中断或超时，操作结果尚未确认。请先刷新查询结果，再决定是否重试。'
        : '无法连接服务，请检查网络或联系管理员，然后重试。',
    })
  } finally {
    clearTimeout(timer)
  }
}
export function withIfMatch(headers: HeadersInit | undefined, version?: number | null): Headers {
  const result = new Headers(headers)
  if (version !== undefined && version !== null) result.set('If-Match', `"${version}"`)
  return result
}
