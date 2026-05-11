/**
 * S139 / S154b — auth API client.
 *
 * `fetchMe()` 對接 backend `GET /api/v1/me`。S154 backend 已擴 response 從 6 → 11 keys
 * （加 `userId / handle`），本 type 對齊 backend `MeController.java` 11-key response。
 *
 *   - userId — S154 platform user_id (`u_<6hex>`)；author/owner_id 一律用此值
 *   - handle — S154 user-facing slug；ShareSkillModal + URL 路徑用
 *   - sub    — OAuth provider raw subject；**永遠不直接顯給 user**，前端 `getDisplayName`
 *              priority chain 把它擋掉
 *   - email/name/picture — OIDC standard claims；optional 容錯
 *
 * 401（未登入）由 fetchMe 攔截，return null（不 throw）— 符合 spec §4.1
 * "200 → AuthUser | 401 → null" 約定。其他 status 仍 throw。
 */
export interface AuthUser {
  /** S154 — platform user_id；author/owner_id/ACL principal 全用此值。 */
  userId: string
  /** S154 — user-facing slug。 */
  handle: string
  /** OAuth provider raw subject；不直接顯給 user。 */
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
