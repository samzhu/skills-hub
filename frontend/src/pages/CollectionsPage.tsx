import { useQuery } from '@tanstack/react-query'
import { Boxes, Download } from 'lucide-react'
import { AppShell } from '@/components/AppShell'
import { EmptyState } from '@/components/EmptyState'
import { fetchCollections, type SkillCollection } from '@/api/skills'

/**
 * S096f1 — Collections read-only stub at `/collections`.
 *
 * 對齊 PRD §P7 + Engineering Handoff §2.11. 本 spec ship 為 read-only：
 * - GET /api/v1/collections → list（backend stub returns []）
 * - 顯 card grid with name / description / skill count / installs / category
 * - 0 results → EmptyState invite tone「目前還沒人建立集合」
 *
 * Defer S096f2: Collection aggregate / POST /collections (create) / POST
 * /collections/:id/install / GET /collections/:id (single) / domain events
 * (CollectionCreated / CollectionInstalled).
 *
 * UX：disabled 「建立集合」 CTA 暗示 feature 未啟用 — per Engineering Handoff §10
 * 「Disable, don't hide, blocked actions」.
 */
export function CollectionsPage() {
  const { data: collections, isLoading } = useQuery<SkillCollection[]>({
    queryKey: ['collections'],
    queryFn: fetchCollections,
    staleTime: 60 * 1000,
  })

  return (
    <AppShell>
      <div className="mb-6">
        <p className="text-[12px] font-semibold uppercase tracking-wider text-muted-foreground">技能集合</p>
        <h1 className="mt-1 text-[22px] font-semibold tracking-tight">精選技能集合</h1>
        <p className="mt-1 text-[13px] text-muted-foreground">
          把多個相關技能打包成集合，一鍵安裝整組工作流。常用範例：DevOps Starter Pack / Frontend Quality Suite / Security Audit Kit。
        </p>
        <div className="mt-3 flex items-center gap-3">
          <button
            type="button"
            disabled
            title="即將開放 — 集合建立功能後續版本推出"
            className="inline-flex cursor-not-allowed items-center gap-1.5 rounded-md border border-border bg-card px-4 py-2 text-[13px] font-medium opacity-50"
          >
            <Boxes className="h-3.5 w-3.5" />
            建立集合（即將開放）
          </button>
        </div>
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-16 text-muted-foreground">載入中...</div>
      ) : !collections || collections.length === 0 ? (
        <EmptyState
          tone="invite"
          headline="目前還沒人建立集合。"
          sub="集合（Collection）讓你把多個技能一次安裝。後續版本推出後可從這裡建立 / 瀏覽 / 一鍵安裝。"
          secondaryAction={{ label: '回去瀏覽單一技能', href: '/browse' }}
        />
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {collections.map((c) => (
            <CollectionCard key={c.id} collection={c} />
          ))}
        </div>
      )}
    </AppShell>
  )
}

function CollectionCard({ collection }: { collection: SkillCollection }) {
  return (
    <article className="flex flex-col rounded-xl border border-border bg-card p-5 transition-colors hover:border-[rgba(255,255,255,0.10)]">
      <div className="mb-2 flex items-center gap-2">
        <Boxes className="h-4 w-4 text-muted-foreground" />
        <span className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">{collection.category}</span>
      </div>
      <h3 className="text-[15px] font-medium">{collection.name}</h3>
      <p className="mt-2 line-clamp-3 flex-1 text-[12.5px] leading-relaxed text-muted-foreground">{collection.description}</p>
      <div className="mt-4 flex items-center justify-between border-t border-border pt-3 text-[11px] text-muted-foreground">
        <span>{collection.skillCount} skills</span>
        <span className="flex items-center gap-1">
          <Download className="h-3 w-3" />
          {collection.installs.toLocaleString()}
        </span>
      </div>
    </article>
  )
}
