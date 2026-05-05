import { useQuery } from '@tanstack/react-query'

/**
 * S134 — dev-only「我的認證」頁。
 *
 * 顯示後端 `/api/v1/dev/auth-debug` dump 的 OAuth2 session / JWT claim shape，
 * 供開發者比對真實 IdP claim 與 mock-oauth2-server 的差異。
 *
 * 啟用條件：後端跑 real-oauth profile（`SPRING_PROFILES_ACTIVE` 含 `real-oauth`）；
 * 否則 fetch 收 404（@Profile gate 沒註冊 controller） → 顯示提示文字。
 *
 * @see backend/src/main/java/io/github/samzhu/skillshub/shared/security/dev/AuthDebugController
 */

const NOT_REAL_OAUTH = 'NOT_REAL_OAUTH_PROFILE'

async function fetchAuthDebug(): Promise<unknown> {
  const res = await fetch('/api/v1/dev/auth-debug', { credentials: 'same-origin' })
  if (res.status === 404) {
    // bean 未註冊 → 顯式 sentinel error 區分「真實錯誤」 vs「profile 未啟用」
    throw new Error(NOT_REAL_OAUTH)
  }
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`)
  }
  return res.json()
}

export function AuthDebugPage() {
  const { data, error, isLoading } = useQuery({
    queryKey: ['auth-debug'],
    queryFn: fetchAuthDebug,
    retry: false,             // 404 不該 retry；錯誤即是訊息本身
    refetchOnWindowFocus: false,
  })

  // 載入中：showtime 短，不刻意做 skeleton
  if (isLoading) {
    return (
      <div className="p-6">
        <h1 className="text-2xl mb-4 font-semibold">我的認證</h1>
        <p className="text-zinc-400">載入中…</p>
      </div>
    )
  }

  // 404 fallback：dev 端未啟用 real-oauth profile
  if (error?.message === NOT_REAL_OAUTH) {
    return (
      <div className="p-6 max-w-2xl">
        <h1 className="text-2xl mb-4 font-semibold">我的認證</h1>
        <p className="text-zinc-400">
          目前未啟用真實 OAuth profile（需要 SPRING_PROFILES_ACTIVE 含 real-oauth）。
        </p>
      </div>
    )
  }

  // 其他錯誤：顯示通用錯誤訊息（網路斷線 / 後端 500 等）
  if (error) {
    return (
      <div className="p-6 text-red-400">
        錯誤：{error.message}
      </div>
    )
  }

  return (
    <div className="p-6 max-w-4xl">
      <h1 className="text-2xl mb-4 font-semibold">我的認證</h1>
      {/* 純 JSON dump（含 access_token_value）— 僅 dev 用；real-oauth profile 嚴禁部署到 LAB / prod */}
      <pre
        data-testid="auth-debug-json"
        className="text-xs bg-zinc-900 p-4 rounded overflow-auto border border-zinc-800"
      >
        {JSON.stringify(data, null, 2)}
      </pre>
    </div>
  )
}
