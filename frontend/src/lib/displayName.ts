/**
 * S154b — 統一 author display 邏輯，五層 fallback 對齊 backend `DisplayNameResolver`。
 *
 * 前後端共識：永遠不顯 raw OAuth sub。最終 fallback 是 platform user_id（`u_<6hex>`），
 * 不是 OAuth provider 的 21+ 位數 subject。
 *
 * Priority chain (per spec §2.2)：
 *   1. authorDisplayName — backend live join 結果（users.name 或 snapshot fallback）
 *   2. authorEmail (local-part) — 取 @ 前段；無 displayName 但有 email 時的人類可讀名
 *   3. authorHandle — user-facing slug
 *   4. author (user_id) — 最終 fallback，platform 識別
 *
 * @see ../components/SkillCard.tsx
 * @see backend `DisplayNameResolver` (S154 §2.5)
 */

export type AuthorFields = {
  /** Platform user_id (`u_<6hex>`) — 必填 fallback；對應 backend `Skill.author` 欄位。 */
  author: string
  /** Backend live-join 結果；五層 fallback 已在 server 端算過，client 端優先用此值。 */
  authorDisplayName?: string | null
  /** User-facing slug；若 displayName / email 缺，作 priority 3 fallback。 */
  authorHandle?: string | null
  /** 取 @ 前段作 priority 2 fallback；缺則跳過。 */
  authorEmail?: string | null
}

export function getDisplayName(obj: AuthorFields): string {
  if (obj.authorDisplayName) return obj.authorDisplayName
  // Empty-string / null email 走 falsy 跳過；split('@') 在 'a@b' 取 'a'，在 'noemail' 取整段
  if (obj.authorEmail) return obj.authorEmail.split('@')[0]
  if (obj.authorHandle) return obj.authorHandle
  return obj.author
}
