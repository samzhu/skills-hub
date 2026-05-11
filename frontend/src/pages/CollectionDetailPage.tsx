import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { ArrowLeft, Boxes } from 'lucide-react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { AppShell } from '@/components/AppShell'
import { EmptyState } from '@/components/EmptyState'
import { InstallButton } from '@/components/InstallButton'
import { RiskBadge } from '@/components/RiskBadge'
import { IconTile } from '@/components/IconTile'
import { EditCollectionModal } from '@/components/EditCollectionModal'
import { useCollection } from '@/hooks/useCollection'
import { useMe } from '@/hooks/useMe'
import { deleteCollection, type CollectionSkillSummary } from '@/api/skills'
import { localizeApiError } from '@/lib/api-error-messages'

/** S150 — /collections/:id detail page；S164b — owner-only edit / delete 操作列。 */
export function CollectionDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { data: collection, isLoading, isError } = useCollection(id)
  const { data: me } = useMe()
  const [editOpen, setEditOpen] = useState(false)

  const isOwner = !!collection && !!me && collection.ownerId === me.sub

  const deleteMutation = useMutation({
    mutationFn: () => deleteCollection(collection!.id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['collections'] })
      navigate('/collections')
    },
  })

  const onDeleteClick = () => {
    if (!collection) return
    if (!window.confirm(`確定刪除集合「${collection.name}」？此動作無法復原。`)) return
    deleteMutation.mutate()
  }

  if (isLoading) {
    return (
      <AppShell>
        <div className="flex items-center justify-center py-16 text-muted-foreground">載入中...</div>
      </AppShell>
    )
  }

  if (isError || !collection) {
    return (
      <AppShell>
        <EmptyState
          tone="redirect"
          headline="找不到此集合。"
          sub="此集合不存在或已被刪除。"
          primaryAction={{ label: '返回集合列表', href: '/collections' }}
        />
      </AppShell>
    )
  }

  return (
    <AppShell>
      {/* Back link */}
      <Link
        to="/collections"
        className="mb-4 inline-flex items-center gap-1.5 text-[12px] text-muted-foreground hover:text-foreground"
      >
        <ArrowLeft className="h-3 w-3" />
        集合列表
      </Link>

      {/* Header */}
      <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <div className="mb-1 flex items-center gap-2">
            <Boxes className="h-4 w-4 text-muted-foreground" />
            <span className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
              {collection.category}
            </span>
          </div>
          <h1 className="text-[22px] font-semibold tracking-tight">{collection.name}</h1>
          {collection.description && (
            <p className="mt-2 max-w-prose text-[13px] leading-relaxed text-muted-foreground">
              {collection.description}
            </p>
          )}
        </div>
        <div className="flex shrink-0 items-start gap-2">
          {isOwner && (
            <>
              <button
                type="button"
                data-testid="edit-collection-btn"
                onClick={() => setEditOpen(true)}
                className="rounded-md border border-border px-3 py-1.5 text-[13px] hover:bg-muted"
              >
                編輯
              </button>
              <button
                type="button"
                data-testid="delete-collection-btn"
                onClick={onDeleteClick}
                disabled={deleteMutation.isPending}
                className="rounded-md border border-red-700/40 px-3 py-1.5 text-[13px] text-red-400 hover:bg-red-950/30 disabled:opacity-50"
              >
                {deleteMutation.isPending ? '刪除中...' : '刪除'}
              </button>
            </>
          )}
          <InstallButton collectionId={collection.id} skillCount={collection.skills.length} />
        </div>
      </div>

      {deleteMutation.isError && (
        <p className="mb-3 text-[12px] text-red-500">
          刪除失敗：{localizeApiError(deleteMutation.error)}
        </p>
      )}

      {editOpen && (
        <EditCollectionModal collection={collection} onClose={() => setEditOpen(false)} />
      )}

      {/* Skill list */}
      {collection.skills.length === 0 ? (
        <EmptyState
          tone="redirect"
          headline="此集合目前無技能。"
          sub="這個集合還沒有加入任何技能，請稍後再查看。"
        />
      ) : (
        <div className="overflow-hidden rounded-lg border border-border bg-card">
          {collection.skills.map((skill, i) => (
            <SkillRow
              key={skill.id}
              skill={skill}
              isLast={i === collection.skills.length - 1}
            />
          ))}
        </div>
      )}
    </AppShell>
  )
}

function SkillRow({ skill, isLast }: { skill: CollectionSkillSummary; isLast: boolean }) {
  return (
    <Link
      to={`/skills/${skill.id}`}
      className={
        'flex items-center gap-3 px-4 py-3 hover:bg-muted/30 ' +
        (isLast ? '' : 'border-b border-border')
      }
    >
      <IconTile name={skill.name} category={skill.category} size="sm" />
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="truncate text-[13px] font-medium">{skill.name}</span>
          <RiskBadge level={skill.riskLevel} />
        </div>
        <p className="mt-0.5 text-[11px] text-muted-foreground">
          {skill.latestVersion ? `v${skill.latestVersion}` : '—'}
        </p>
      </div>
      <span className="text-[11px] text-muted-foreground">{skill.category}</span>
    </Link>
  )
}
