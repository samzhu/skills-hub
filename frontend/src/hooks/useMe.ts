import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '../api/client'

/**
 * S094a / S154b — fetch current user identity from backend `/api/v1/me`.
 *
 * Backend response shape — 11 keys (per S154 §2.5 + `MeController.java`)：
 * <pre>{@code
 * {
 *   userId:    "u_a3f9c1",          // S154 — platform user_id (u_<6hex>)
 *   handle:    "alice",             // S154 — user-facing slug
 *   sub:       "116549...",         // OAuth provider raw subject
 *   email:     "alice@example.com",
 *   name:      "Alice Chen",
 *   roles:     ["admin"],
 *   groups:    [],
 *   companyId: null,
 *   deptId:    null,
 *   scope:     "",
 *   picture:   "https://lh3...",    // OAuth-only
 * }
 * }</pre>
 *
 * `userId` 取代過去用 `sub` 當 author identity 的習慣（S154 後端 forge fix）；
 * `/skills?author={userId}` filter 拉自己的 skills。`sub` 保留供 OAuth-only 流程（不直接給 user 看）。
 */
export interface CurrentUser {
  /** S154 — platform user_id (`u_<6hex>`)；後續 author/owner_id 一律用此值。 */
  userId: string
  /** S154 — user-facing slug；ShareSkillModal 顯示 + URL `/users/{handle}`。 */
  handle: string
  /** OAuth provider raw subject；不直接顯給 user（前端 `getDisplayName` 永遠優先 displayName/email/handle）。 */
  sub: string
  /** OIDC `email` claim；nullable when OAuth provider omits。 */
  email: string | null
  /** OIDC `name` claim；nullable。 */
  name: string | null
  roles: string[]
  groups: string[]
  companyId: string | null
  deptId: string | null
  scope: string
  /** OIDC `picture` claim；profile avatar URL。 */
  picture: string | null
}

export function useMe() {
  return useQuery<CurrentUser>({
    queryKey: ['me'],
    queryFn: () => apiFetch('/me'),
    // identity 在 session 內穩定，cache 5min；refetch on window focus 不必要
    staleTime: 5 * 60 * 1000,
    refetchOnWindowFocus: false,
  })
}
