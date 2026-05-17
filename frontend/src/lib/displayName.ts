/**
 * S192: Human labels never fall back to platform user_id.
 * Use getAuthorRouteSegment for route/install technical identifiers.
 */

export type AuthorFields = {
  /** Platform user_id (`u_<6hex>`): 行為用 id，不是人類 label；route/install segment 才可 fallback。 */
  author: string
  /** Backend live-join 結果；client 端人類 label 優先用此值。 */
  authorDisplayName?: string | null
  /** User-facing slug；display label 的 fallback，也可作 route/install segment。 */
  authorHandle?: string | null
  /** 取 @ 前段作 priority 2 fallback；缺則跳過。 */
  authorEmail?: string | null
}

export function getDisplayName(obj: AuthorFields): string {
  const displayName = cleanText(obj.authorDisplayName)
  if (displayName) return displayName
  // Empty-string / null email 走 falsy 跳過；split('@') 在 'a@b' 取 'a'，在 'noemail' 取整段
  const emailLocalPart = cleanText(obj.authorEmail)?.split('@')[0]
  if (emailLocalPart) return emailLocalPart
  const handle = cleanText(obj.authorHandle)
  if (handle) return handle
  return ''
}

export function getAuthorRouteSegment(obj: Pick<AuthorFields, 'author' | 'authorHandle'>): string {
  return cleanText(obj.authorHandle) ?? obj.author
}

function cleanText(value: string | null | undefined): string | undefined {
  const trimmed = value?.trim()
  return trimmed ? trimmed : undefined
}
