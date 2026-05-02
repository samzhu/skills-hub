import { useEffect } from 'react'
import { Link, useSearchParams, useNavigate } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { Loader2, AlertCircle, Check } from 'lucide-react'
import { AppShell } from '@/components/AppShell'
import { fetchSkillById } from '@/api/skills'
import type { Skill } from '@/types/skill'

/**
 * S098a — `/publish/validate?id={skillId}` Step 2 中介驗證頁。
 *
 * 對齊 docs/grimo/ui/prototype/Skills Hub Publish Step 2.html。
 * 4-step stepper：Upload → Validate (active) → Review → Live。
 * Poll `/skills/{id}` 每 2s 直到 risk_level 設值即 navigate `/publish/review?id=X`。
 *
 * Trim from M(10) → XS(5)：
 * - ✅ stepper UI (4 step status icons + connection lines)
 * - ✅ auto-poll + auto-navigate
 * - ⏸ S098a2: 真 SSE 事件串流（per-step 即時 done 動畫；需 backend 三 events）
 * - ⏸ S098a3: upload-strip file detail (filename / size / N files)
 *
 * 取代 PublishPage onSuccess 直接 → /publish/review；插入 validate 中介頁讓
 * user 看到「掃描進行中」的 stepper feedback 而非 review 頁的 spinner。
 */

type StepStatus = 'done' | 'active' | 'future'
const STEPS: { num: number; label: string }[] = [
  { num: 1, label: '上傳' },
  { num: 2, label: '驗證' },
  { num: 3, label: '審視' },
  { num: 4, label: '上架' },
]

export function PublishValidatePage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const skillId = params.get('id') ?? ''

  const { data: skill, error, isLoading } = useQuery<Skill>({
    queryKey: ['skills', skillId],
    queryFn: () => fetchSkillById(skillId),
    enabled: !!skillId,
    refetchInterval: (query) => {
      const data = query.state.data as Skill | undefined
      return data && data.riskLevel == null ? 2000 : false
    },
    refetchIntervalInBackground: false,
  })

  // S098a: scan 完成（riskLevel 設值）即 navigate 到 review；replace mode 避免 back-button 循環
  useEffect(() => {
    if (skill?.riskLevel != null && skillId) {
      navigate(`/publish/review?id=${skillId}`, { replace: true })
    }
  }, [skill?.riskLevel, skillId, navigate])

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

  // 各步狀態：scanning 中 = (Upload done, Validate active, Review/Live future)；
  // error = 同步顯示但下方加 callout
  const stepStatus = (idx: number): StepStatus => {
    if (idx === 0) return 'done' // Upload 已完（既然到此頁）
    if (idx === 1) return 'active' // Validate 進行中
    return 'future' // Review / Live
  }

  return (
    <AppShell>
      <div className="mx-auto max-w-2xl">
        <p className="text-[12px] font-semibold uppercase tracking-wider text-muted-foreground">發佈流程</p>
        <h1 className="mt-1 text-[22px] font-semibold tracking-tight">驗證進行中</h1>
        <p className="mt-1 text-[13px] text-muted-foreground">
          系統正在掃描你的 bundle — 通常需要 5-15 秒。完成後自動跳轉至審視頁面。
        </p>

        {/* Stepper */}
        <div className="mt-6 flex items-center gap-1.5">
          {STEPS.map((step, idx) => {
            const status = stepStatus(idx)
            return (
              <div key={step.num} className="flex flex-1 items-center gap-1.5">
                <StepDot status={status} num={step.num} />
                <span className={`text-[12px] ${status === 'active' ? 'font-medium text-foreground' : 'text-muted-foreground'}`}>
                  {step.label}
                </span>
                {idx < STEPS.length - 1 && (
                  <div
                    className="h-px flex-1"
                    style={{
                      backgroundColor:
                        status === 'done' ? 'rgba(29,158,117,0.40)' : 'rgba(255,255,255,0.10)',
                    }}
                  />
                )}
              </div>
            )
          })}
        </div>

        {/* Status callout */}
        <div className="mt-6">
          {isLoading ? (
            <StatusCallout
              tone="info"
              icon={<Loader2 className="h-4 w-4 animate-spin" />}
              text="載入 skill 資料中..."
            />
          ) : error || !skill ? (
            <StatusCallout
              tone="danger"
              icon={<AlertCircle className="h-4 w-4" />}
              text={`無法載入 skill (id=${skillId}) — 可能仍在處理或已被刪除`}
            />
          ) : skill.riskLevel == null ? (
            <StatusCallout
              tone="warning"
              icon={<Loader2 className="h-4 w-4 animate-spin" />}
              text={`「${skill.name}」 — 風險掃描進行中，每 2 秒自動更新（無需手動重新整理）`}
            />
          ) : (
            <StatusCallout
              tone="success"
              icon={<Check className="h-4 w-4" />}
              text={`掃描完成（risk: ${skill.riskLevel}）— 即將跳轉至審視頁面...`}
            />
          )}
        </div>
      </div>
    </AppShell>
  )
}

function StepDot({ status, num }: { status: StepStatus; num: number }) {
  if (status === 'done') {
    return (
      <div
        className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-[11px] font-medium"
        style={{ backgroundColor: 'rgba(29,158,117,0.18)', color: '#6FD8B0' }}
      >
        ✓
      </div>
    )
  }
  if (status === 'active') {
    return (
      <div className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-foreground text-[11px] font-medium text-background">
        {num}
      </div>
    )
  }
  return (
    <div
      className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-[11px] font-medium text-muted-foreground"
      style={{ backgroundColor: '#171719', borderColor: 'rgba(255,255,255,0.10)', borderWidth: '0.5px' }}
    >
      {num}
    </div>
  )
}

function StatusCallout({
  tone,
  icon,
  text,
}: {
  tone: 'info' | 'warning' | 'success' | 'danger'
  icon: React.ReactNode
  text: string
}) {
  const palette = {
    info: { bg: 'rgba(255,255,255,0.04)', fg: '#A8A49C' },
    warning: { bg: 'rgba(239,159,39,0.10)', fg: '#FAC775' },
    success: { bg: 'rgba(29,158,117,0.10)', fg: '#6FD8B0' },
    danger: { bg: 'rgba(226,75,74,0.10)', fg: '#F2A6A6' },
  }[tone]
  return (
    <div
      className="flex items-start gap-2.5 rounded-md p-3 text-[13px] leading-relaxed"
      style={{ backgroundColor: palette.bg, color: palette.fg }}
    >
      <span className="mt-0.5 shrink-0">{icon}</span>
      <p className="flex-1">{text}</p>
    </div>
  )
}
