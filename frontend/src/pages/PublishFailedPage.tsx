import { Link, useSearchParams, useLocation } from 'react-router'
import { AlertOctagon, RefreshCw, ArrowLeft, X, AlertTriangle } from 'lucide-react'
import { AppShell } from '@/components/AppShell'
import { EmptyState } from '@/components/EmptyState'
import type { ValidationFinding } from '@/types/skill'

/**
 * S098b — `/publish/failed?id=&state=&msg=` dedicated failure page.
 *
 * 對齊 docs/grimo/ui/prototype/Skills Hub Publish Failures.html 兩個 state：
 * - State A：frontmatter / 上傳 validation 失敗（紅色 callout, blocked, 須修檔重傳）
 * - State B：scan 完成 risk_level=HIGH（橘色 callout, 顯示安全報告與重傳動作）
 *
 * Trim from S(8) → XS(4)：本 commit 只 ship State A flow（PublishPage onError →
 * navigate /publish/failed?state=A&msg=...）。State B redirect from
 * PublishReviewPage 為 S098b2 follow-up。
 *
 * 設計理由：把 error/blocked state 從 PublishPage inline 抽出獨立 route，
 * 方便分享 / bookmark / 客服協助；PublishPage 本體保持 form-only 心智。
 */

type FailedState = 'A' | 'B'

export function PublishFailedPage() {
  const [params] = useSearchParams()
  const location = useLocation()
  const stateRaw = params.get('state')
  const state: FailedState = stateRaw === 'B' ? 'B' : 'A' // default A
  const id = params.get('id')
  // S098b3-2: findings from router state (structured); fallback to ?msg= URL param (backward compat)
  const routerState = location.state as { findings?: ValidationFinding[]; msg?: string } | null
  const findings = routerState?.findings
  const msg = routerState?.msg ?? params.get('msg')

  // S155 #3: 直訪 /publish/failed 但缺所有錯誤 context → 改顯 EmptyState 引導回 /publish，
  // 取代原本「驗證失敗 / 0 error · 0 warning」自相矛盾的 fallback 顯示。
  // 觸發條件：沒 router state findings、沒 query msg、沒 id —— user 是 bookmark / typo 直訪。
  const hasErrorContext = (findings && findings.length > 0) || !!msg || !!id
  if (!hasErrorContext) {
    return (
      <AppShell>
        <div className="mx-auto max-w-2xl py-10">
          <EmptyState
            tone="redirect"
            headline="沒有失敗紀錄可顯示"
            sub="此頁面僅在發佈流程觸發失敗時自動導入。請從上傳開始；若你以為應該有失敗紀錄，請回到上傳頁重新發佈。"
            primaryAction={{ label: '前往上傳', href: '/publish' }}
            secondaryAction={{ label: '返回瀏覽', href: '/browse' }}
          />
        </div>
      </AppShell>
    )
  }

  return (
    <AppShell>
      <div className="mx-auto max-w-2xl">
        <div className="mb-[14px]">
          <h1 className="m-0 text-[22px] font-medium leading-[1.2]">
            {state === 'A' ? '發佈未通過驗證' : '高風險掃描完成'}
          </h1>
          <p className="mt-1 text-[13px] text-muted-foreground">
            {state === 'A'
              ? '請依下方提示修正 SKILL.md，再重新上傳 zip 套件'
              : '掃描偵測到 HIGH 級風險。平台目前沒有人工核准流程；請查看安全報告，或修正套件後重新上傳。'}
          </p>
        </div>

        {state === 'A' ? <StateAFrontmatterError msg={msg} findings={findings} /> : <StateBHighRiskReview id={id} />}

        <div className="mt-6 flex flex-wrap gap-3">
          <Link
            to="/publish"
            className="inline-flex items-center gap-1.5 rounded-md bg-[#EEECEA] px-4 py-2 text-[13px] font-medium text-[#08080A]"
          >
            <RefreshCw className="h-3 w-3" />
            重新上傳
          </Link>
          <Link
            to="/browse"
            className="inline-flex items-center gap-1.5 rounded-md border border-[rgba(255,255,255,0.10)] bg-[#171719] px-4 py-2 text-[13px] font-medium text-[#EEECEA] hover:bg-[#1F1F22]"
          >
            <ArrowLeft className="h-3 w-3" />
            返回瀏覽
          </Link>
        </div>
      </div>
    </AppShell>
  )
}

// ============== State A — Validation / format error ==============

/**
 * S098b3: 結構化 ErrRow shape，未來 backend 結構化 findings payload 可填多筆。
 * 目前 backend 只送 flat msg → 派生為 single ErrRow（severity='error'）。
 */
type ErrRow = {
  severity: 'error' | 'warning'
  title: string
  hint?: string
}

function StateAFrontmatterError({ msg, findings: structuredFindings }: { msg: string | null; findings?: ValidationFinding[] }) {
  // S098b3-2: prefer structured findings from router state; fallback to flat msg as single error row
  const findings: ErrRow[] = structuredFindings && structuredFindings.length > 0
    ? structuredFindings.map((f) => ({ severity: f.severity, title: f.title, hint: f.hint ?? undefined }))
    : msg ? [{ severity: 'error', title: msg }] : []

  return (
    <div className="space-y-4">
      {/* Top callout */}
      <div
        className="rounded-lg border p-4"
        style={{
          backgroundColor: 'rgba(226,75,74,0.07)',
          borderColor: 'rgba(226,75,74,0.20)',
        }}
      >
        <div className="flex items-start gap-3">
          <AlertOctagon className="mt-0.5 h-5 w-5 shrink-0 text-[#F2A6A6]" />
          <div className="flex-1">
            <h2 className="text-[14px] font-semibold text-[#F2A6A6]">驗證在第 2 步停止 — 沒有任何資料寫入。</h2>
            <p className="mt-1 text-[13px] leading-relaxed text-[#A8A49C]">
              你的 bundle 沒通過 SKILL.md 驗證。請依下方錯誤訊息修正後重新上傳；目前 registry 沒有寫入任何記錄。
            </p>
          </div>
        </div>
      </div>

      {/* SKILL.md validation section */}
      <ValidationSection
        title="SKILL.md 驗證失敗"
        sub={`${findings.filter((f) => f.severity === 'error').length} error · ${findings.filter((f) => f.severity === 'warning').length} warning`}
        status="failed"
        rows={findings}
      />

      {/* Bundle structure section — pass (尚未驗證 detail，先示意) */}
      <ValidationSection title="Bundle 結構" sub="尚未驗證 — SKILL.md 通過後執行" status="skipped" rows={[]} />

      {/* Risk scan section — skipped */}
      <ValidationSection title="風險掃描" sub="尚未執行 — 修正 SKILL.md 後再跑" status="skipped" rows={[]} />
    </div>
  )
}

// S098b3: V-section component — header + status badge + (optional) err-rows list
function ValidationSection({
  title,
  sub,
  status,
  rows,
}: {
  title: string
  sub: string
  status: 'failed' | 'pass' | 'skipped'
  rows: ErrRow[]
}) {
  const statusStyle = {
    failed: { bg: 'rgba(226,75,74,0.14)', fg: '#F2A6A6', label: '失敗', iconChar: '✗' },
    pass: { bg: 'rgba(29,158,117,0.14)', fg: '#6FD8B0', label: '通過', iconChar: '✓' },
    skipped: { bg: 'rgba(255,255,255,0.05)', fg: '#5E5B55', label: '略過', iconChar: '—' },
  }[status]
  const opacity = status === 'skipped' ? 'opacity-60' : ''

  return (
    <div className={`rounded-md border border-[rgba(255,255,255,0.06)] bg-[#0F0F12] ${opacity}`}>
      {/* head */}
      <div className="flex items-center justify-between border-b border-[rgba(255,255,255,0.06)] p-3">
        <div className="flex items-center gap-3">
          <div
            className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md text-[14px] font-semibold"
            style={{ backgroundColor: statusStyle.bg, color: statusStyle.fg }}
          >
            {statusStyle.iconChar}
          </div>
          <div>
            <p className="text-[13px] font-medium text-[#EEECEA]">{title}</p>
            <p className="mt-0.5 text-[11px] text-[#A8A49C]">{sub}</p>
          </div>
        </div>
        <span
          className="rounded-full px-2 py-0.5 text-[11px] font-medium"
          style={{ backgroundColor: statusStyle.bg, color: statusStyle.fg }}
        >
          {statusStyle.label}
        </span>
      </div>
      {/* err-rows */}
      {rows.length > 0 && (
        <div className="divide-y divide-[rgba(255,255,255,0.06)]">
          {rows.map((row, i) => (
            <div key={i} className="flex items-start gap-3 p-3">
              <div
                className="flex h-5 w-5 shrink-0 items-center justify-center rounded text-[11px]"
                style={
                  row.severity === 'error'
                    ? { backgroundColor: 'rgba(226,75,74,0.14)', color: '#F2A6A6' }
                    : { backgroundColor: 'rgba(239,159,39,0.14)', color: '#FAC775' }
                }
              >
                {row.severity === 'error' ? <X className="h-3 w-3" /> : <AlertTriangle className="h-3 w-3" />}
              </div>
              <div className="flex-1 min-w-0">
                <p className="text-[13px] font-medium text-[#EEECEA] break-words">{row.title}</p>
                {row.hint && (
                  <p className="mt-0.5 text-[12px] leading-relaxed text-[#A8A49C]">{row.hint}</p>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

// ============== State B — High risk scan warning ==============

function StateBHighRiskReview({ id }: { id: string | null }) {
  return (
    <div
      className="rounded-lg border p-4"
      style={{
        backgroundColor: 'rgba(239,159,39,0.08)',
        borderColor: 'rgba(239,159,39,0.20)',
      }}
    >
      <div className="flex items-start gap-3">
        <AlertOctagon className="mt-0.5 h-5 w-5 shrink-0 text-[#FAC775]" />
        <div className="flex-1">
          <h2 className="text-[14px] font-semibold text-[#FAC775]">技能掃出 HIGH 級風險 — 請先查看安全報告。</h2>
          <p className="mt-1 text-[13px] leading-relaxed text-[#A8A49C]">
            掃描 detector 觸發 HIGH 級風險規則（如 <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[12px]">curl | bash</code>、
            存取 <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[12px]">~/.ssh</code> / <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[12px]">~/.aws</code> 等）。
            平台目前沒有人工核准流程；請查看技能詳情中的安全報告，或修正套件後重新上傳。
          </p>
          {id && (
            <div className="mt-3 flex flex-wrap items-center gap-3 text-[12px] text-[#A8A49C]">
              <p>
                技能 ID：<code className="rounded bg-[#171719] px-1.5 py-0.5 font-mono text-[12px] text-[#EEECEA]">{id}</code>
              </p>
              <Link to={`/skills/${id}`} className="font-medium text-[#FAC775] underline underline-offset-4">
                查看技能詳情
              </Link>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
