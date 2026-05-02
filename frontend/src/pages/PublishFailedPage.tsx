import { Link, useSearchParams } from 'react-router'
import { AlertOctagon, RefreshCw, ArrowLeft } from 'lucide-react'
import { AppShell } from '@/components/AppShell'

/**
 * S098b — `/publish/failed?id=&state=&msg=` dedicated failure page.
 *
 * 對齊 docs/grimo/ui/prototype/Skills Hub Publish Failures.html 兩個 state：
 * - State A：frontmatter / 上傳 validation 失敗（紅色 callout, blocked, 須修檔重傳）
 * - State B：scan 完成 risk_level=HIGH（橘色 callout, 進入人工審核佇列）
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
  const stateRaw = params.get('state')
  const state: FailedState = stateRaw === 'B' ? 'B' : 'A' // default A
  const id = params.get('id')
  const msg = params.get('msg')

  return (
    <AppShell>
      <div className="mx-auto max-w-2xl">
        <div className="mb-[14px]">
          <h1 className="m-0 text-[22px] font-medium leading-[1.2]">
            {state === 'A' ? '發佈未通過驗證' : '高風險技能 — 已送審'}
          </h1>
          <p className="mt-1 text-[13px] text-muted-foreground">
            {state === 'A'
              ? '請依下方提示修正 SKILL.md，再重新上傳 zip 套件'
              : '掃描偵測到 HIGH 級風險，技能已進入人工審核佇列；reviewer 將通知作者'}
          </p>
        </div>

        {state === 'A' ? <StateAFrontmatterError msg={msg} /> : <StateBHighRiskReview id={id} />}

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

function StateAFrontmatterError({ msg }: { msg: string | null }) {
  return (
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
          {msg && (
            <pre className="mt-3 overflow-x-auto rounded-md bg-[#0F0F12] p-3 font-mono text-[12px] leading-relaxed text-[#EEECEA]">
              {msg}
            </pre>
          )}
        </div>
      </div>
    </div>
  )
}

// ============== State B — High risk submitted for review ==============

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
          <h2 className="text-[14px] font-semibold text-[#FAC775]">技能掃出 HIGH 級風險 — 已寫入審核佇列。</h2>
          <p className="mt-1 text-[13px] leading-relaxed text-[#A8A49C]">
            掃描 detector 觸發 HIGH 級風險規則（如 <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[12px]">curl | bash</code>、
            存取 <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[12px]">~/.ssh</code> / <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[12px]">~/.aws</code> 等）。
            技能未即時上架；reviewer 會在 24 小時內審核並通知作者。
          </p>
          {id && (
            <p className="mt-3 text-[12px] text-[#A8A49C]">
              技能 ID：<code className="rounded bg-[#171719] px-1.5 py-0.5 font-mono text-[12px] text-[#EEECEA]">{id}</code>
            </p>
          )}
        </div>
      </div>
    </div>
  )
}
