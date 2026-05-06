/**
 * S139 — auth API client.
 *
 * `fetchMe()` 對接 backend `GET /api/v1/me`。Spec §4.1 AuthUser shape 含
 * email/name/picture（Google id_token claims）；目前 backend MeController 只回
 * sub/roles/groups/companyId/deptId/scope（per S027 / S134），尚未擴 OIDC profile
 * claims。本 type 內 email/name/picture 設 optional，frontend 容錯：
 *   - picture 缺 → AuthArea avatar fallback 字母（email 或 sub 首字母）
 *   - email 缺 → dropdown 顯示 sub
 *   - name 缺 → 不阻擋登入 UX
 *
 * 401（未登入）由 fetchMe 攔截，return null（不 throw）— 符合 spec §4.1
 * "200 → AuthUser | 401 → null" 約定。其他 status 仍 throw。
 */
export interface AuthUser {
  sub: string
  email?: string
  name?: string
  picture?: string
}

const API_BASE = '/api/v1'

export async function fetchMe(): Promise<AuthUser | null> {
  const res = await fetch(`${API_BASE}/me`, { credentials: 'same-origin' })
  if (res.status === 401) {
    return null
  }
  if (!res.ok) {
    throw new Error(`fetchMe failed: HTTP ${res.status}`)
  }
  const data = (await res.json()) as unknown
  // Strict shape check：若 response 結構不對（缺 sub 字串），視為未登入。
  // 防呆既有 page tests 的 wildcard fetch mock 回非 AuthUser shape 時 AppShell 整個炸。
  if (typeof data !== 'object' || data === null) {
    return null
  }
  const obj = data as Record<string, unknown>
  if (typeof obj.sub !== 'string') {
    return null
  }
  return obj as unknown as AuthUser
}
