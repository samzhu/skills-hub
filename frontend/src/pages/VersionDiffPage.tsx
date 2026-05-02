import { Link, useParams, useSearchParams } from 'react-router'
import { ArrowLeft, ArrowRight, Loader2, AlertCircle, GitCompare } from 'lucide-react'
import { AppShell } from '@/components/AppShell'
import { ErrorState } from '@/components/ErrorState'
import { useSkill } from '@/hooks/useSkill'
import { useVersions } from '@/hooks/useVersions'
import type { SkillVersion } from '@/types/skill'

/**
 * S098c — `/skills/:id/diff?from={v1}&to={v2}` Version 比較頁。
 *
 * 對齊 prototype `Skills Hub Version Diff.html`。Frontend-only trim：
 * 不引入新 backend endpoint；reuse 既有 `/skills/{id}` + `/skills/{id}/versions`
 * 即可顯 from/to 兩版本的 metadata（version / fileSize / publishedAt）side-by-side。
 *
 * Trim from M(12) → S(6)：
 * - ✅ side-by-side metadata (version / size / publishedAt) + delta 計算
 * - ✅ from/to query params + version selector dropdown
 * - ⏸ S098c2: 真 backend `/api/v1/skills/{id}/diff?from=&to=` 含 risk-level delta /
 *   description / scripts hash diff（需 backend SkillVersion 加 riskLevel + sha 欄位
 *   per-version snapshot）
 * - ⏸ S098c3: file content side-by-side diff（需 zip extract + line-level diff）
 */
export function VersionDiffPage() {
  const { id } = useParams<{ id: string }>()
  const [params] = useSearchParams()
  const fromVer = params.get('from')
  const toVer = params.get('to')

  const { data: skill } = useSkill(id ?? '')
  const { data: versions, isLoading, error } = useVersions(id ?? '')

  if (!id) {
    return (
      <AppShell>
        <div className="mx-auto max-w-2xl">
          <ErrorState variant="centered" title="缺少 skill id 參數" />
        </div>
      </AppShell>
    )
  }

  if (isLoading) {
    return (
      <AppShell>
        <div className="mx-auto max-w-3xl flex items-center gap-2 text-[13px] text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" />
          載入版本資料中...
        </div>
      </AppShell>
    )
  }

  if (error || !versions || versions.length < 2) {
    return (
      <AppShell>
        <div className="mx-auto max-w-3xl">
          <div className="rounded-md p-4 text-[13px]" style={{ backgroundColor: 'rgba(239,159,39,0.10)', color: '#FAC775' }}>
            <AlertCircle className="mr-2 inline-block h-4 w-4" />
            技能版本不足 2 個，無法比較。
          </div>
          <Link to={`/skills/${id}`} className="mt-4 inline-flex items-center gap-1.5 text-[13px] text-muted-foreground hover:text-foreground">
            <ArrowLeft className="h-3 w-3" />
            返回技能詳情
          </Link>
        </div>
      </AppShell>
    )
  }

  // 預設值：fromVer = 倒數第二新, toVer = 最新
  const sortedVersions = [...versions].sort((a, b) =>
    new Date(b.publishedAt).getTime() - new Date(a.publishedAt).getTime(),
  )
  const fromVersion = versions.find((v) => v.version === fromVer) ?? sortedVersions[1]
  const toVersion = versions.find((v) => v.version === toVer) ?? sortedVersions[0]

  return (
    <AppShell>
      <div className="mx-auto max-w-4xl">
        <Link to={`/skills/${id}`} className="mb-4 inline-flex items-center gap-1 text-[13px] text-muted-foreground hover:text-foreground">
          <ArrowLeft className="h-4 w-4" />
          返回技能詳情
        </Link>

        <div className="mb-6">
          <p className="text-[12px] font-semibold uppercase tracking-wider text-muted-foreground">版本比較</p>
          <h1 className="mt-1 flex items-center gap-2 text-[22px] font-semibold tracking-tight">
            <GitCompare className="h-5 w-5" />
            {skill?.name ?? '...'}
          </h1>
          <p className="mt-1 text-[13px] text-muted-foreground">
            比對 <code className="rounded bg-secondary px-1 py-0.5 font-mono text-[12px]">v{fromVersion.version}</code> 與
            <code className="ml-1 rounded bg-secondary px-1 py-0.5 font-mono text-[12px]">v{toVersion.version}</code>。
            目前顯示版本 metadata；S098c2 將加入 description / risk-level / 內容 hash diff。
          </p>
        </div>

        {/* Version selector chips */}
        <div className="mb-4 flex flex-wrap items-center gap-2 text-[12px] text-muted-foreground">
          <span>選版本：</span>
          {sortedVersions.map((v) => {
            const isFrom = v.version === fromVersion.version
            const isTo = v.version === toVersion.version
            return (
              <Link
                key={v.id}
                to={`/skills/${id}/diff?from=${isFrom ? v.version : fromVersion.version}&to=${isTo ? v.version : toVersion.version}`}
                className={
                  'rounded-full px-2.5 py-1 font-mono text-[11px] transition-colors ' +
                  (isFrom
                    ? 'border border-[rgba(226,75,74,0.30)] bg-[rgba(226,75,74,0.10)] text-[#F2A6A6]'
                    : isTo
                      ? 'border border-[rgba(29,158,117,0.30)] bg-[rgba(29,158,117,0.10)] text-[#6FD8B0]'
                      : 'border border-transparent text-muted-foreground hover:text-foreground')
                }
                title={isFrom ? 'from（基準版本）' : isTo ? 'to（比對目標）' : '點擊設為比對基準'}
              >
                v{v.version}
              </Link>
            )
          })}
        </div>

        {/* Side-by-side */}
        <div className="grid gap-4 md:grid-cols-[1fr_auto_1fr] md:items-stretch">
          <VersionCard label="from（基準）" version={fromVersion} tone="from" />
          <div className="hidden flex-col items-center justify-center text-muted-foreground md:flex">
            <ArrowRight className="h-4 w-4" />
          </div>
          <VersionCard label="to（比對目標）" version={toVersion} tone="to" />
        </div>

        {/* Delta */}
        <div className="mt-6 rounded-md border border-[rgba(255,255,255,0.06)] bg-[#0F0F12] p-4">
          <h2 className="mb-2 text-[14px] font-medium">變化</h2>
          <Delta from={fromVersion} to={toVersion} />
        </div>
      </div>
    </AppShell>
  )
}

function VersionCard({
  label,
  version,
  tone,
}: {
  label: string
  version: SkillVersion
  tone: 'from' | 'to'
}) {
  const palette =
    tone === 'from'
      ? { border: 'rgba(226,75,74,0.30)', label: '#F2A6A6', bg: 'rgba(226,75,74,0.05)' }
      : { border: 'rgba(29,158,117,0.30)', label: '#6FD8B0', bg: 'rgba(29,158,117,0.05)' }
  return (
    <div
      className="rounded-md border p-4"
      style={{ borderColor: palette.border, backgroundColor: palette.bg }}
    >
      <p className="text-[10.5px] font-semibold uppercase tracking-wider" style={{ color: palette.label }}>
        {label}
      </p>
      <p className="mt-1 font-mono text-[18px] font-semibold text-foreground">v{version.version}</p>
      <dl className="mt-4 grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-[12px]">
        <dt className="font-semibold uppercase tracking-wider text-muted-foreground">套件大小</dt>
        <dd className="font-mono text-foreground">{formatBytes(version.fileSize)}</dd>
        <dt className="font-semibold uppercase tracking-wider text-muted-foreground">發布時間</dt>
        <dd className="text-foreground">{new Date(version.publishedAt).toLocaleString('zh-TW')}</dd>
      </dl>
    </div>
  )
}

function Delta({ from, to }: { from: SkillVersion; to: SkillVersion }) {
  const sizeDelta = to.fileSize - from.fileSize
  const sizePct = from.fileSize > 0 ? ((sizeDelta / from.fileSize) * 100).toFixed(1) : '—'
  const timeDeltaMs = new Date(to.publishedAt).getTime() - new Date(from.publishedAt).getTime()
  const timeDeltaDays = (timeDeltaMs / (1000 * 60 * 60 * 24)).toFixed(1)

  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-[13px]">
      <dt className="font-semibold uppercase tracking-wider text-muted-foreground">套件大小</dt>
      <dd className="font-mono text-foreground">
        {sizeDelta > 0 ? '+' : ''}{formatBytes(sizeDelta)}
        <span className="ml-2 text-muted-foreground">({sizeDelta > 0 ? '+' : ''}{sizePct}%)</span>
      </dd>
      <dt className="font-semibold uppercase tracking-wider text-muted-foreground">發布間隔</dt>
      <dd className="text-foreground">{timeDeltaDays} 天</dd>
    </dl>
  )
}

function formatBytes(bytes: number): string {
  const sign = bytes < 0 ? '-' : ''
  const abs = Math.abs(bytes)
  if (abs < 1024) return `${sign}${abs} B`
  if (abs < 1024 * 1024) return `${sign}${(abs / 1024).toFixed(1)} KB`
  return `${sign}${(abs / 1024 / 1024).toFixed(2)} MB`
}
