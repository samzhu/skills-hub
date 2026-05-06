import { useState } from 'react'
import { toast } from 'sonner'
import { useGrants, useCreateGrant, useRevokeGrant } from '@/hooks/useGrants'
import type { SkillGrant } from '@/api/grants'

/**
 * S114a — ACL 分享管理 Modal。
 *
 * 顯示技能現有 grants 清單，並提供新增 VIEWER grant / 撤銷 grant 操作。
 * owner-only：呼叫端負責確認 `skill.ownerId === me.sub` 後再 render。
 */
export function ShareModal({ skillId, onClose }: { skillId: string; onClose: () => void }) {
  const { data: grants = [], isLoading } = useGrants(skillId)
  const createGrant = useCreateGrant(skillId)
  const revokeGrant = useRevokeGrant(skillId)

  const [principalType, setPrincipalType] = useState<SkillGrant['principalType']>('user')
  const [principalId, setPrincipalId] = useState('')

  const isPublicGrant = principalType === 'public'
  const resolvedPrincipalId = isPublicGrant ? '*' : principalId.trim()
  const canSubmit =
    (isPublicGrant || resolvedPrincipalId.length > 0) &&
    !createGrant.isPending

  function handleSubmit() {
    createGrant.mutate(
      { principalType, principalId: resolvedPrincipalId, role: 'VIEWER' },
      {
        onSuccess: () => {
          setPrincipalId('')
          toast.success('分享已更新（生效中…）')
        },
        onError: (err) => toast.error(`新增失敗：${err.message}`),
      },
    )
  }

  function handleRevoke(grantId: string) {
    revokeGrant.mutate(grantId, {
      onSuccess: () => toast.success('已移除分享'),
      onError: (err) => toast.error(`移除失敗：${err.message}`),
    })
  }

  return (
    <div
      role="dialog"
      aria-label="分享技能"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
    >
      <div className="w-full max-w-md rounded-md border border-border bg-card p-6">
        <h3 className="mb-4 text-[16px] font-semibold">分享技能</h3>

        {/* grants list */}
        <div className="mb-4">
          <p className="mb-2 text-[12px] text-muted-foreground">現有分享</p>
          {isLoading ? (
            <p className="text-[13px] text-muted-foreground">載入中…</p>
          ) : grants.length === 0 ? (
            <p className="text-[13px] text-muted-foreground">尚無分享設定</p>
          ) : (
            <ul className="space-y-1.5">
              {grants.map((g) => (
                <li
                  key={g.id}
                  className="flex items-center justify-between rounded-md border border-border px-3 py-1.5 text-[13px]"
                >
                  <span>
                    <span className="font-medium">{g.principalType}:{g.principalId}</span>
                    <span className="ml-2 text-muted-foreground">{g.role}</span>
                  </span>
                  {/* OWNER grants cannot be removed via UI */}
                  {g.role !== 'OWNER' && (
                    <button
                      type="button"
                      onClick={() => handleRevoke(g.id)}
                      disabled={revokeGrant.isPending}
                      className="ml-2 text-[12px] text-red-500 hover:underline disabled:opacity-50"
                    >
                      移除
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>

        {/* add grant form */}
        <div className="mb-4">
          <p className="mb-2 text-[12px] text-muted-foreground">新增分享（VIEWER）</p>
          <div className="mb-2 flex gap-2">
            {(['user', 'group', 'company', 'public'] as const).map((t) => (
              <label key={t} className="flex cursor-pointer items-center gap-1 text-[12px]">
                <input
                  type="radio"
                  name="principalType"
                  value={t}
                  checked={principalType === t}
                  onChange={() => setPrincipalType(t)}
                />
                {t}
              </label>
            ))}
          </div>
          <input
            type="text"
            value={isPublicGrant ? '*' : principalId}
            onChange={(e) => setPrincipalId(e.target.value)}
            disabled={isPublicGrant}
            placeholder={isPublicGrant ? '所有人（public:*）' : '輸入 ID…'}
            className="w-full rounded-md border border-border bg-background p-2 text-[13px] disabled:opacity-50"
          />
        </div>

        {createGrant.isError && (
          <p className="mb-2 text-[12px] text-red-500">
            新增失敗：{createGrant.error.message}
          </p>
        )}

        <div className="flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-border px-3 py-1.5 text-[13px] hover:bg-muted"
          >
            關閉
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={!canSubmit}
            className="rounded-md bg-primary px-3 py-1.5 text-[13px] text-primary-foreground hover:bg-foreground disabled:opacity-50"
          >
            {createGrant.isPending ? '處理中…' : '新增'}
          </button>
        </div>
      </div>
    </div>
  )
}
