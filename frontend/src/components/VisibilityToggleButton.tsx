import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createGrant, fetchGrants, revokeGrant } from '@/api/grants'

/**
 * S163b' — Owner-only 公開 ↔ 私人 toggle button。
 *
 * 後端 ACL 路徑：
 * - 當前 public（grants 含 principalType='public' && principalId='*'）→ 顯「轉為私人」→
 *   click revokeGrant(skillId, publicGrantId)
 * - 當前 private（無 public:* grant）→ 顯「公開分享」→ click createGrant({public, *, VIEWER})
 *
 * 對應 S163 AC-4（切私人）/ AC-6（重新公開）；驗證走既有 ACL backend 行為。
 *
 * 設計：自包 grants query — 不依賴 parent 傳 isPublic prop，避免 parent 漏接 stale。
 * 切換成功後 invalidate skill detail + grants query，PageHeader 與 ShareModal 同步刷新。
 */
export function VisibilityToggleButton({ skillId }: { skillId: string }) {
  const queryClient = useQueryClient()
  const { data: grants, isLoading } = useQuery({
    queryKey: ['skill-grants', skillId],
    queryFn: () => fetchGrants(skillId),
  })

  const publicGrant = grants?.find(
    (g) => g.principalType === 'public' && g.principalId === '*',
  )
  const isPublic = !!publicGrant

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['skill-grants', skillId] })
    queryClient.invalidateQueries({ queryKey: ['skill', skillId] })
    queryClient.invalidateQueries({ queryKey: ['skills'] })
  }

  const grantPublic = useMutation({
    mutationFn: () =>
      createGrant(skillId, {
        principalType: 'public',
        principalId: '*',
        role: 'VIEWER',
      }),
    onSuccess: invalidate,
  })

  const revokePublic = useMutation({
    mutationFn: () => {
      if (!publicGrant) throw new Error('no public grant to revoke')
      return revokeGrant(skillId, publicGrant.id)
    },
    onSuccess: invalidate,
  })

  const pending = grantPublic.isPending || revokePublic.isPending

  if (isLoading) {
    return (
      <button
        type="button"
        disabled
        aria-label="讀取中"
        data-testid="visibility-toggle"
        style={{
          padding: '8px 14px',
          fontSize: 13,
          background: 'rgba(255,255,255,0.06)',
          border: '0.5px solid var(--line-2, rgba(255,255,255,0.12))',
          borderRadius: 8,
          opacity: 0.5,
          cursor: 'wait',
        }}
      >
        ...
      </button>
    )
  }

  const label = isPublic ? '轉為私人' : '公開分享'
  const onClick = () => {
    if (isPublic) revokePublic.mutate()
    else grantPublic.mutate()
  }

  return (
    <button
      type="button"
      aria-label={label}
      data-testid="visibility-toggle"
      disabled={pending}
      onClick={onClick}
      style={{
        padding: '8px 14px',
        fontSize: 13,
        background: 'rgba(255,255,255,0.06)',
        border: '0.5px solid var(--line-2, rgba(255,255,255,0.12))',
        borderRadius: 8,
        cursor: pending ? 'wait' : 'pointer',
        opacity: pending ? 0.6 : 1,
      }}
    >
      {pending ? '處理中...' : label}
    </button>
  )
}
