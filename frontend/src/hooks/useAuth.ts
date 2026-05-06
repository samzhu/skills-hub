import { useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchMe, type AuthUser } from '../api/auth'

/**
 * S139 — useAuth hook：探測登入狀態 + 提供 login/logout 動作。
 *
 * <p>狀態 3 種（per spec §4.1 AuthState）：
 * <ul>
 *   <li>{@code loading} — fetchMe in-flight 第一次（避免閃登入按鈕後又變 avatar）</li>
 *   <li>{@code authenticated} — fetchMe 200 + 帶 user payload</li>
 *   <li>{@code anonymous} — fetchMe 401（fetchMe 已轉成 null 不 throw）</li>
 * </ul>
 *
 * <p>{@code login(returnTo?)} 跳 OAuth：拼 `/oauth2/authorization/skillshub?returnTo=<encoded>`，
 * `returnTo` 預設用當前 pathname + search（讓登入完成後回原頁面）。
 *
 * <p>{@code logout()} 走 Spring Security 預設 `POST /logout` endpoint，session 清掉後
 * 把 query cache invalidate 並導 `/`（landing）。Spring 預設 `/logout` 是 same-origin POST
 * 配 CSRF token；本 app csrf().disable() 故 plain POST 即可。
 *
 * <p>React Query 30s staleTime — 大部分頁面觸發 useAuth 不會重打 /me；user 主動 logout
 * 後 invalidate 立即同步全 app 狀態。
 *
 * @see frontend/src/api/auth.ts fetchMe
 * @see backend/src/main/java/.../shared/security/AuthRedirectConfig.java OAuth resolver
 */

type LoginFn = (returnTo?: string) => void
type LogoutFn = () => Promise<void>

export type AuthState =
  | { status: 'loading'; login: LoginFn; logout: LogoutFn }
  | { status: 'authenticated'; user: AuthUser; login: LoginFn; logout: LogoutFn }
  | { status: 'anonymous'; login: LoginFn; logout: LogoutFn }

const AUTH_QUERY_KEY = ['auth', 'me'] as const

export function useAuth(): AuthState {
  const queryClient = useQueryClient()

  const { data, isPending } = useQuery<AuthUser | null>({
    queryKey: AUTH_QUERY_KEY,
    queryFn: fetchMe,
    staleTime: 30 * 1000,
    refetchOnWindowFocus: false,
    retry: false,
  })

  const login: LoginFn = (returnTo) => {
    const target = returnTo ?? `${window.location.pathname}${window.location.search}`
    window.location.href = `/oauth2/authorization/skillshub?returnTo=${encodeURIComponent(target)}`
  }

  const logout: LogoutFn = async () => {
    try {
      await fetch('/logout', {
        method: 'POST',
        credentials: 'same-origin',
      })
    } finally {
      // 不論 POST 成功否（401 / 200 都算「已登出」），清 cache + 跳 landing
      queryClient.removeQueries({ queryKey: AUTH_QUERY_KEY })
      window.location.href = '/'
    }
  }

  if (isPending) {
    return { status: 'loading', login, logout }
  }
  if (data == null) {
    return { status: 'anonymous', login, logout }
  }
  return { status: 'authenticated', user: data, login, logout }
}
