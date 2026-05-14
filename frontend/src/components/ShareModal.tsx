import { useState } from 'react'
import { toast } from 'sonner'
import { useQuery } from '@tanstack/react-query'
import { useGrants, useCreateGrant, useRevokeGrant } from '@/hooks/useGrants'
import type { SkillGrant } from '@/api/grants'
import { searchShareTargets } from '@/api/shareTargets'
import { localizeApiError } from '@/lib/api-error-messages'
import { getDisplayName } from '@/lib/displayName'

/**
 * S114a — ACL 分享管理 Modal。
 *
 * 顯示技能現有 grants 清單，並提供新增 VIEWER grant / 撤銷 grant 操作。
 * owner-only：呼叫端以 detail API 的 `viewerPermissions.canManageGrants` 決定是否 render。
 */
export function ShareModal({ skillId, onClose }: { skillId: string; onClose: () => void }) {
  const { data: grants = [], isLoading } = useGrants(skillId)
  const createGrant = useCreateGrant(skillId)
  const revokeGrant = useRevokeGrant(skillId)

  const [principalType, setPrincipalType] = useState<'user' | 'group' | 'public'>('user')
  const [principalId, setPrincipalId] = useState('')
  const [role, setRole] = useState<'VIEWER' | 'EDITOR'>('VIEWER')

  const isPublicGrant = principalType === 'public'
  const resolvedPrincipalId = isPublicGrant ? '*' : principalId.trim()
  const targetQuery = useQuery({
    queryKey: ['share-targets', principalType, principalId],
    queryFn: () => searchShareTargets(principalId),
    enabled: principalType === 'group' && principalId.trim().length > 0,
  })
  // S154b — 已 public:*:read 偵測：若 grants 含 principalType='public' && principalId='*'
  // → 不允許再加 public grant（duplicate；backend 也會回 conflict）。對 UI disable + 告知。
  const isAlreadyPublic = grants.some(
    (g) => g.principalType === 'public' && g.principalId === '*',
  )
  const canSubmit =
    (isPublicGrant ? !isAlreadyPublic : resolvedPrincipalId.length > 0) &&
    !createGrant.isPending

  function handleSubmit() {
    createGrant.mutate(
      { principalType, principalId: resolvedPrincipalId, role },
      {
        onSuccess: () => {
          setPrincipalId('')
          toast.success('分享已更新（生效中…）')
        },
        onError: (err) => toast.error(`新增失敗：${localizeApiError(err)}`),
      },
    )
  }

  function handleRevoke(grantId: string) {
    revokeGrant.mutate(grantId, {
      onSuccess: () => toast.success('已移除分享'),
      onError: (err) => toast.error(`移除失敗：${localizeApiError(err)}`),
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
                    {/* S154b — user principal 走 getDisplayName 5-layer fallback；
                        public 維持「所有人（public）」固定 label，避免顯 raw "*" */}
                    <span className="font-medium">{principalLabel(g)}</span>
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
          <p className="mb-2 text-[12px] text-muted-foreground">新增分享</p>
          <div className="mb-2 flex gap-2">
            {(['user', 'group', 'public'] as const).map((t) => {
              const isPublicOption = t === 'public'
              const disabled = isPublicOption && isAlreadyPublic
              return (
                <label
                  key={t}
                  className={
                    'flex cursor-pointer items-center gap-1 text-[12px] ' +
                    (disabled ? 'opacity-50 cursor-not-allowed' : '')
                  }
                  title={disabled ? '此技能已公開' : undefined}
                >
                  <input
                    type="radio"
                    name="principalType"
                    value={t}
                    checked={principalType === t}
                    onChange={() => setPrincipalType(t)}
                    disabled={disabled}
                  />
                  {targetTypeLabel(t)}
                </label>
              )
            })}
          </div>
          <div className="mb-2 flex gap-2">
            {(['VIEWER', 'EDITOR'] as const).map((r) => (
              <label key={r} className="flex cursor-pointer items-center gap-1 text-[12px]">
                <input
                  type="radio"
                  name="grantRole"
                  value={r}
                  checked={role === r}
                  onChange={() => setRole(r)}
                />
                {roleLabel(r)}
              </label>
            ))}
          </div>
          {/* S154b — 已 public 時 inline 文案告知（搭配 radio disabled）。tooltip
              藉 title 屬性留給 hover 場景；無 hover 也要看得到狀態。 */}
          {isAlreadyPublic && (
            <p className="mb-2 text-[11.5px] text-muted-foreground">
              此技能已公開瀏覽，無需再加 public 分享。
            </p>
          )}
          <input
            type="text"
            value={isPublicGrant ? '*' : principalId}
            onChange={(e) => setPrincipalId(e.target.value)}
            disabled={isPublicGrant}
            // S154b — placeholder 改友善版（AC-9）；backend `UserResolver.resolveByEmailHandleOrId`
            // 接收 email / handle / user_id 三種；submit 時 backend 轉成 user_id 寫入 ACL。
            placeholder={targetPlaceholder(principalType)}
            className="w-full rounded-md border border-border bg-background p-2 text-[13px] disabled:opacity-50"
          />
          {principalType === 'group' && targetQuery.data && targetQuery.data.length > 0 && (
            <ul className="mt-2 max-h-32 overflow-auto rounded-md border border-border">
              {targetQuery.data.map((target) => (
                <li key={target.principalId}>
                  <button
                    type="button"
                    className="flex w-full flex-col px-2 py-1.5 text-left text-[12px] hover:bg-muted"
                    onClick={() => setPrincipalId(target.principalId)}
                  >
                    <span>{target.label}</span>
                    <span className="text-[11px] text-muted-foreground">{target.hint}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        {createGrant.isError && (
          <p className="mb-2 text-[12px] text-red-500">
            新增失敗：{localizeApiError(createGrant.error)}
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

/**
 * S154b — grants list row 顯示 label。
 *
 * <ul>
 *   <li>{@code public} principal → 固定「所有人（public）」，不顯 raw "*"</li>
 *   <li>{@code user} principal → 走 `getDisplayName` 5-layer fallback；backend enrich 後
 *       有 displayName/handle，無 enrich（舊資料）fallback 顯 raw user_id</li>
 *   <li>其他（group/company legacy 資料） → fallback `type:id` 維持向下相容</li>
 * </ul>
 */
function principalLabel(g: SkillGrant): string {
  if (g.principalType === 'public') return '所有人（public）'
  if (g.principalType === 'user') {
    return getDisplayName({
      author: g.principalId,
      authorDisplayName: g.displayName,
      authorHandle: g.handle,
    })
  }
  if (g.principalType === 'group') return `群組：${g.principalId}`
  return `${g.principalType}:${g.principalId}`
}

function targetTypeLabel(t: 'user' | 'group' | 'public'): string {
  if (t === 'user') return '人員 user'
  if (t === 'group') return '群組 group'
  return '所有人 public'
}

function roleLabel(role: 'VIEWER' | 'EDITOR'): string {
  return role === 'VIEWER' ? '可檢視' : '可編輯'
}

function targetPlaceholder(t: 'user' | 'group' | 'public'): string {
  if (t === 'public') return '所有人（public:*）'
  if (t === 'group') return '搜尋群組名稱'
  return '輸入使用者 email 或 handle'
}
