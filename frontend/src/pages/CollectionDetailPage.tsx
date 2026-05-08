import { Link, useParams } from 'react-router'
import { ArrowLeft, Boxes } from 'lucide-react'
import { AppShell } from '@/components/AppShell'
import { EmptyState } from '@/components/EmptyState'
import { InstallButton } from '@/components/InstallButton'
import { RiskBadge } from '@/components/RiskBadge'
import { IconTile } from '@/components/IconTile'
import { useCollection } from '@/hooks/useCollection'
import type { CollectionSkillSummary } from '@/api/skills'

/** S150 — /collections/:id detail page */
export function CollectionDetailPage() {
  const { id } = useParams<{ id: string }>()
  const { data: collection, isLoading, isError } = useCollection(id)

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
        <div className="shrink-0">
          <InstallButton collectionId={collection.id} skillCount={collection.skills.length} />
        </div>
      </div>

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
