/** 所有 API 請求的 base path，對應後端 REST prefix */
const BASE = '/api/v1'

import type { ValidationFinding } from '../types/skill'

/**
 * S039：自訂 API 錯誤類別 — 攜 HTTP status 與 backend ErrorResponse `error` code，
 * 讓 caller 可做精細 UX 區分（例如 404 顯示「找不到」vs 5xx 顯示「載入失敗請重試」）。
 *
 * backend `ErrorResponse` shape：`{error, message, timestamp}`（per `GlobalExceptionHandler`）
 * 對應到 `code`（error 欄位）與 super 的 `message`。
 * S098b3-2：`findings` 攜帶結構化驗證 findings（`ValidationErrorResponse` shape）。
 */
export class ApiError extends Error {
  readonly status: number
  readonly code?: string
  readonly findings?: ValidationFinding[]

  constructor(status: number, message: string, code?: string, findings?: ValidationFinding[]) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.findings = findings
  }

  /**
   * S065: name-based 替代 `instanceof ApiError` — Vite HMR 模組重載會生多個 class
   * instance；不同 module 載入的 ApiError 之間 instanceof 不共享 prototype chain。
   * 用 name 字串檢查穩定，兼具 type guard。
   */
  static is(err: unknown): err is ApiError {
    return err instanceof Error && err.name === 'ApiError'
  }
}

/**
 * S160b' — 從 document.cookie 取出指定 cookie 值；不存在回 null。
 *
 * 拆出來方便 vitest mock document.cookie 做 unit test。
 */
function readCookie(name: string): string | null {
  if (typeof document === 'undefined') return null
  const target = name + '='
  for (const raw of document.cookie.split(';')) {
    const c = raw.trim()
    if (c.startsWith(target)) {
      return decodeURIComponent(c.substring(target.length))
    }
  }
  return null
}

/**
 * S160b' — mutation HTTP method 判定。Spring Security 對「safe methods」（GET/HEAD/
 * OPTIONS/TRACE）不檢查 CSRF token；只對 mutation methods 啟用 protection，所以
 * frontend 只要對 mutation 方法注入 header 即可。
 */
function isMutation(method: string | undefined): boolean {
  if (!method) return false
  const m = method.toUpperCase()
  return m === 'POST' || m === 'PUT' || m === 'DELETE' || m === 'PATCH'
}

/**
 * S160b' — 對 mutation request 自動讀 XSRF-TOKEN cookie + 帶 X-XSRF-TOKEN header。
 *
 * 設計：
 * - cookie 不存在（GET 沒打過或 backend CSRF 未啟用）→ 不加 header；backend 預設
 *   `csrf().disable()` 不檢查 token，所以沒 header 也通過。
 * - cookie 存在 → 加 header；backend 啟用 CSRF 後 chain 比對通過。
 * - 既有 caller 傳的 header 優先（不覆蓋自訂 X-XSRF-TOKEN）。
 */
function withCsrfHeader(init?: RequestInit): RequestInit | undefined {
  if (!isMutation(init?.method)) {
    return init
  }
  const token = readCookie('XSRF-TOKEN')
  if (token == null) {
    return init
  }
  const merged = new Headers(init?.headers ?? {})
  if (!merged.has('X-XSRF-TOKEN')) {
    merged.set('X-XSRF-TOKEN', token)
  }
  return { ...(init ?? {}), headers: merged }
}

/**
 * 通用的 API fetch 包裝函式。
 *
 * 自動補上 `/api/v1` 前綴，並在 HTTP 狀態非 2xx 時解析錯誤訊息並拋出 {@link ApiError}，
 * 攜帶 status + code 讓呼叫方做精細 UX 區分；保留 `message` 兼容既有 callers。
 *
 * S160b'：對 mutation methods (POST/PUT/DELETE/PATCH) 自動讀 XSRF-TOKEN cookie +
 * 帶 X-XSRF-TOKEN header。Backend `skillshub.security.csrf.enabled=false`（預設）
 * 時 cookie 不存在 → no-op；啟用後 cookie 自動 issue，header 自動 round-trip。
 *
 * @param path  相對於 BASE 的路徑，例如 `/skills` 或 `/skills/123`
 * @param init  原生 fetch RequestInit 選項（headers、method、body 等）
 * @returns     解析後的回應 body（泛型 T）
 * @throws      {@link ApiError}，含 status / code / message；status 非 2xx 觸發
 */
export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, withCsrfHeader(init))
  if (!res.ok) {
    // 嘗試解析後端回傳的錯誤 body；若 body 不是合法 JSON（如網路中斷、閘道錯誤），
    // 則 fallback 為空物件以避免額外的 UnhandledRejection。
    const body = await res.json().catch(() => ({}))
    const b = body as { message?: string; error?: string; findings?: ValidationFinding[] }
    const message = b.message ?? `API error ${res.status}`
    throw new ApiError(res.status, message, b.error, b.findings)
  }
  return res.json() as Promise<T>
}

export async function apiFetchVoid(path: string, init?: RequestInit): Promise<void> {
  const res = await fetch(`${BASE}${path}`, withCsrfHeader(init))
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    const b = body as { message?: string; error?: string; findings?: ValidationFinding[] }
    throw new ApiError(res.status, b.message ?? `API error ${res.status}`, b.error, b.findings)
  }
}

/**
 * S160b' — 純測試 export，給 vitest 直接驗 `withCsrfHeader` 行為（不需要 mock fetch）。
 * Production code 不該 import 此 symbol；只走 apiFetch / apiFetchVoid 公開 API。
 */
export const __test = { withCsrfHeader, readCookie, isMutation }
