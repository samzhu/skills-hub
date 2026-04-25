/** 所有 API 請求的 base path，對應後端 REST prefix */
const BASE = '/api/v1'

/**
 * 通用的 API fetch 包裝函式。
 *
 * 自動補上 `/api/v1` 前綴，並在 HTTP 狀態非 2xx 時解析錯誤訊息並拋出 Error，
 * 讓呼叫方只需 try/catch 一種錯誤型別。
 *
 * @param path  相對於 BASE 的路徑，例如 `/skills` 或 `/skills/123`
 * @param init  原生 fetch RequestInit 選項（headers、method、body 等）
 * @returns     解析後的回應 body（泛型 T）
 * @throws      Error，訊息優先使用後端回傳的 `message` 欄位，fallback 為 HTTP 狀態碼
 */
export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, init)
  if (!res.ok) {
    // 嘗試解析後端回傳的錯誤訊息；若 body 不是合法 JSON（如網路中斷、閘道錯誤），
    // 則 fallback 為空物件以避免額外的 UnhandledRejection。
    const body = await res.json().catch(() => ({}))
    throw new Error((body as { message?: string }).message ?? `API error ${res.status}`)
  }
  return res.json() as Promise<T>
}
