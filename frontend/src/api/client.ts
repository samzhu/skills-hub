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
 * 通用的 API fetch 包裝函式。
 *
 * 自動補上 `/api/v1` 前綴，並在 HTTP 狀態非 2xx 時解析錯誤訊息並拋出 {@link ApiError}，
 * 攜帶 status + code 讓呼叫方做精細 UX 區分；保留 `message` 兼容既有 callers。
 *
 * @param path  相對於 BASE 的路徑，例如 `/skills` 或 `/skills/123`
 * @param init  原生 fetch RequestInit 選項（headers、method、body 等）
 * @returns     解析後的回應 body（泛型 T）
 * @throws      {@link ApiError}，含 status / code / message；status 非 2xx 觸發
 */
export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, init)
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
  const res = await fetch(`${BASE}${path}`, init)
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    const b = body as { message?: string; error?: string; findings?: ValidationFinding[] }
    throw new ApiError(res.status, b.message ?? `API error ${res.status}`, b.error, b.findings)
  }
}
