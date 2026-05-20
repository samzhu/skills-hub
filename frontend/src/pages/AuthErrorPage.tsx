import { useSearchParams } from 'react-router'
import { AppShell } from '@/components/AppShell'
import { EmptyState } from '@/components/EmptyState'

type AuthFailureReason = 'session_expired' | 'access_denied' | 'token_exchange_failed' | 'oauth_failed'

const SAFE_REASONS = new Set<AuthFailureReason>([
  'session_expired',
  'access_denied',
  'token_exchange_failed',
  'oauth_failed',
])

const REASON_COPY: Record<AuthFailureReason, { sub: string; suggestions: string[] }> = {
  session_expired: {
    sub: '這次登入流程已失效，請回到瀏覽頁面後再重新開始。',
    suggestions: ['回到技能瀏覽'],
  },
  access_denied: {
    sub: 'Google 沒有授權這次登入。請回到瀏覽頁面後再重新開始。',
    suggestions: ['回到技能瀏覽'],
  },
  token_exchange_failed: {
    sub: 'Google 已回到 Skills Hub，但後端沒有完成認證。請先回到瀏覽頁面。',
    suggestions: ['回到技能瀏覽', '若剛更新 OAuth 設定，請確認 Cloud Run 已開新 revision'],
  },
  oauth_failed: {
    sub: '登入沒有完成，請回到瀏覽頁面後再重新開始。',
    suggestions: ['回到技能瀏覽'],
  },
}

/**
 * S204 — OAuth2 Login failure recovery page.
 *
 * React 只顯示 backend 給的 safe enum reason，不顯示 OAuth callback query、
 * raw provider error message、token 或 exception 內容。
 */
export function AuthErrorPage() {
  const [params] = useSearchParams()
  const reason = normalizeReason(params.get('reason'))
  const copy = REASON_COPY[reason]

  return (
    <AppShell minimalHeader>
      <div className="mx-auto max-w-2xl py-10">
        <EmptyState
          tone="redirect"
          headline="登入沒有完成"
          sub={copy.sub}
          query={reason}
          primaryAction={{ label: '返回瀏覽', href: '/browse' }}
          suggestions={[
            { text: `錯誤代碼：${reason}` },
            ...copy.suggestions.map((text) => ({ text })),
          ]}
        />
      </div>
    </AppShell>
  )
}

function normalizeReason(raw: string | null): AuthFailureReason {
  if (raw && SAFE_REASONS.has(raw as AuthFailureReason)) {
    return raw as AuthFailureReason
  }
  return 'oauth_failed'
}
