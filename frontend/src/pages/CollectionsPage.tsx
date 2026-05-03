import { useState } from 'react'
import { Boxes, Download } from 'lucide-react'
import { AppShell } from '@/components/AppShell'
import { EmptyState } from '@/components/EmptyState'
import { CreateCollectionModal } from '@/components/CreateCollectionModal'
import { InstallButton } from '@/components/InstallButton'
import { useCollections } from '@/hooks/useCollections'
import type { SkillCollection } from '@/api/skills'

/**
 * S096f2-T04 — Collections full feature at `/collections`.
 *
 * 取代 S096f1 read-only stub：CTA 啟用 + 真資料 list + CreateCollectionModal +
 * 一鍵 install (InstallButton 走 spec §1 Approach C frontend orchestration)。
 *
 * Risk filter (PRD §P7 SBE Scenario 3) defer 至 S096f3 polish；fancy multi-select
 * skill picker / Collection detail page / edit/delete defer per spec §2.6 trim list。
 */
export function CollectionsPage() {
  const { data: collections, isLoading } = useCollections()
  const [showModal, setShowModal] = useState(false)

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
            onClick={() => setShowModal(true)}
            className="inline-flex items-center gap-1.5 rounded-md bg-primary px-4 py-2 text-[13px] font-medium text-primary-foreground hover:bg-primary/90"
          >
            <Boxes className="h-3.5 w-3.5" />
            建立集合
          </button>
        </div>
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-16 text-muted-foreground">載入中...</div>
      ) : !collections || collections.length === 0 ? (
        <EmptyState
          tone="invite"
          headline="目前還沒人建立集合。"
          sub="集合（Collection）讓你把多個技能一次安裝。點上方「建立集合」開始打包你的工作流。"
          secondaryAction={{ label: '回去瀏覽單一技能', href: '/browse' }}
        />
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {collections.map((c) => (
            <CollectionCard key={c.id} collection={c} />
          ))}
        </div>
      )}

      {showModal && <CreateCollectionModal onClose={() => setShowModal(false)} />}
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
        <span>{collection.skillCount} 個技能</span>
        <span className="flex items-center gap-1">
          <Download className="h-3 w-3" />
          {collection.installs.toLocaleString()}
        </span>
      </div>
      <div className="mt-3">
        <InstallButton collectionId={collection.id} skillCount={collection.skillCount} />
      </div>
    </article>
  )
}
