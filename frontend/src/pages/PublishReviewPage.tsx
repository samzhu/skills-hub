import { Link, useSearchParams } from 'react-router'
import { ArrowRight, CheckCircle2, AlertCircle, Loader2 } from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { AppShell } from '@/components/AppShell'
import { RiskBadge } from '@/components/RiskBadge'
import { fetchSkillById } from '@/api/skills'
import type { Skill } from '@/types/skill'

/**
 * S096d4a — `/publish/review?id={skillId}` post-upload result page.
 *
 * 對齊 prototype `Skills Hub Publish Flow.html` 第 3 步 (Step 3 Review)。
 * 取代 PublishPage inline success card；上傳成功後 navigate 到此 URL，user 可分享 / bookmark.
 *
 * 流程：
 * 1. /publish — file drop form (PublishPage)
 * 2. /publish/review?id=X — 此頁 — 顯 risk + 下載 CTA + 跳轉 detail
 *
 * Trim: defer /publish/validate poll page 與 SSE event stream 到 S096d5；本頁
 * 直接 fetch /skills/{id} 顯當前狀態，無 polling（risk scan async 跑後 user 可
 * 重新整理看最新 risk_level）。
 */
export function PublishReviewPage() {
  const [params] = useSearchParams()
  const skillId = params.get('id') ?? ''
  // S096d5a: auto-poll while risk scan async — refetchInterval 2s 直到 risk_level 設值
  // 取代既有 useSkill（fixed cache）；scan 完成後 React Query 自動停 poll（query 為 callback 驅動）
  const { data: skill, isLoading, error } = useQuery<Skill>({
    queryKey: ['skills', skillId],
    queryFn: () => fetchSkillById(skillId),
    enabled: !!skillId,
    refetchInterval: (query) => {
      // 仍 scanning (riskLevel null) → 每 2s 重抓；scan 完成 → 0 = 停 poll
      const data = query.state.data as Skill | undefined
      return data && data.riskLevel == null ? 2000 : false
    },
    refetchIntervalInBackground: false,
  })

  if (!skillId) {
    return (
      <AppShell>
        <div className="mx-auto max-w-2xl rounded-md p-4 text-[13px]" style={{ backgroundColor: 'rgba(226,75,74,0.14)', color: '#F2A6A6' }}>
          <AlertCircle className="mr-2 inline-block h-4 w-4" />
          缺少 skill id 參數 — 請從 <Link to="/publish" className="underline">/publish</Link> 重新發佈
        </div>
      </AppShell>
    )
  }

  return (
    <AppShell>
      <div className="mx-auto max-w-2xl">
        <p className="text-[12px] font-semibold uppercase tracking-wider text-muted-foreground">發佈完成</p>
        <h1 className="mt-1 text-[22px] font-semibold tracking-tight">技能已上傳 — 檢視結果</h1>
        <p className="mt-1 text-[13px] text-muted-foreground">
          系統已接收套件並啟動風險掃描。風險等級顯示後即可分享連結；HIGH 風險 skill 進入人工審核佇列。
        </p>

        {isLoading ? (
          <div className="mt-6 flex items-center gap-2 rounded-md border border-border bg-card p-4 text-[13px] text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            載入 skill 資料中...
          </div>
        ) : error || !skill ? (
          <div className="mt-6 flex items-start gap-3 rounded-md p-3 text-[13px]" style={{ backgroundColor: 'rgba(226,75,74,0.14)', color: '#F2A6A6' }}>
            <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
            <div>
              <p className="font-medium">無法載入 skill</p>
              <p className="mt-0.5 opacity-90">id={skillId}；可能仍在處理或已被刪除</p>
            </div>
          </div>
        ) : (
          <>
            {/* Success callout */}
            <div className="mt-6 flex items-start gap-3 rounded-md p-4 text-[13px]" style={{ backgroundColor: 'rgba(29,158,117,0.14)', color: '#9FE1CB' }}>
              <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" />
              <div className="flex-1">
                <p className="font-medium">「{skill.name}」 v{skill.latestVersion} 已成功發佈</p>
                <p className="mt-0.5 font-mono text-[11px] opacity-80">id: {skill.id}</p>
              </div>
            </div>

            {/* Skill metadata card */}
            <div className="mt-4 rounded-lg border border-border bg-card p-5">
              <div className="flex items-center gap-3">
                <h2 className="text-[16px] font-medium">{skill.name}</h2>
                <RiskBadge level={skill.riskLevel} />
              </div>
              <p className="mt-2 text-[13px] text-muted-foreground">{skill.description}</p>
              <dl className="mt-4 grid grid-cols-2 gap-x-6 gap-y-2 text-[12px]">
                <Field label="作者" value={skill.author} />
                <Field label="分類" value={skill.category} />
                <Field label="版本" value={`v${skill.latestVersion ?? '—'}`} mono />
                <Field label="狀態" value={skill.status} />
              </dl>
            </div>

            {/* Risk scan note — render based on current risk_level */}
            {skill.riskLevel == null ? (
              <div className="mt-4 flex items-center gap-2 rounded-md p-3 text-[13px]" style={{ backgroundColor: 'rgba(239,159,39,0.14)', color: '#FAC775' }}>
                <Loader2 className="h-4 w-4 animate-spin" />
                風險掃描進行中 — 每 2 秒自動更新（無需手動重新整理）
              </div>
            ) : skill.riskLevel === 'HIGH' ? (
              <div className="mt-4 rounded-md p-3 text-[13px]" style={{ backgroundColor: 'rgba(226,75,74,0.14)', color: '#F2A6A6' }}>
                偵測到高風險模式 — 此 skill 進入人工審核佇列，pubic registry 暫不可下載
              </div>
            ) : (
              <div className="mt-4 rounded-md p-3 text-[13px]" style={{ backgroundColor: 'rgba(29,158,117,0.14)', color: '#9FE1CB' }}>
                {skill.riskLevel === 'NONE' ? '未發現任何 risk patterns — auto-published.' : '低風險自動上架完成'}
              </div>
            )}

            {/* Actions */}
            <div className="mt-6 flex flex-wrap items-center gap-3">
              <Link
                to={`/skills/${skill.id}`}
                className="inline-flex items-center gap-1.5 rounded-md bg-primary px-4 py-2 text-[13px] font-medium text-primary-foreground"
              >
                查看技能詳情頁
                <ArrowRight className="h-3.5 w-3.5" />
              </Link>
              <Link
                to="/publish"
                className="inline-flex items-center gap-1.5 rounded-md border border-border bg-card px-4 py-2 text-[13px] font-medium hover:border-[rgba(255,255,255,0.10)]"
              >
                發佈下一個技能
              </Link>
            </div>
          </>
        )}
      </div>
    </AppShell>
  )
}

function Field({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <>
      <dt className="font-semibold uppercase tracking-wider text-muted-foreground">{label}</dt>
      <dd className={mono ? 'font-mono text-foreground' : 'text-foreground'}>{value}</dd>
    </>
  )
}
