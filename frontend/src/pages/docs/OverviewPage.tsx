import { Link } from 'react-router'
import { ArrowRight, Boxes, KeySquare, ShieldCheck, Search } from 'lucide-react'
import { DocsLayout } from '@/components/DocsLayout'
import { BeamFrame } from '@/components/BeamFrame'

/**
 * S098f — `/docs/overview` 入門概覽。
 *
 * 給新使用者第一個 docs 落地頁：
 * - Skills Hub 是什麼 / 為什麼存在
 * - 核心三個機制：自動風險掃描 / 語意搜尋 / 開放標準（agentskills.io）
 * - 引導下一步：撰寫第一個技能 / 瀏覽現有技能
 *
 * 內容刻意精簡 — overview 不解釋 SKILL.md spec / frontmatter 細節（那是 Reference 群組工作）。
 */
export function OverviewPage() {
  return (
    <DocsLayout>
      <p className="mb-1 text-[14px] text-[#A8A49C]">
        文件 <span className="mx-1 text-[#5E5B55]">/</span>
        入門 <span className="mx-1 text-[#5E5B55]">/</span>
        <span className="text-[#EEECEA]">概覽</span>
      </p>
      <h1 className="text-[28px] font-semibold tracking-tight text-[#EEECEA]">Skills Hub 概覽</h1>
      <p className="mt-3 text-[16px] leading-relaxed text-[#A8A49C]">
        Skills Hub 是企業內部的 AI agent 技能登錄中心 — 用同一份 SKILL.md
        bundle 在 Claude Code、Cursor、Gemini CLI 等任一相容 agent 之間共享，
        每次上傳都自動風險評分，讓團隊「分享技能像分享 npm 套件」一樣安全。
      </p>

      <H2>三個核心機制</H2>
      <div className="mt-4 grid gap-3 md:grid-cols-3">
        <FeatureCard
          icon={<ShieldCheck className="h-4 w-4 text-[#6FD8B0]" />}
          title="自動風險評分"
          body="每次發佈跑掃描器，分四級（NONE/LOW/MEDIUM/HIGH）；HIGH 進審核佇列，其餘自動上架。"
        />
        <FeatureCard
          icon={<Search className="h-4 w-4 text-[#7F77DD]" />}
          title="語意搜尋"
          body="自然語言描述需求，由 Gemini embedding + pgvector 比對技能 description；不需精確關鍵字。"
        />
        <FeatureCard
          icon={<Boxes className="h-4 w-4 text-[#FAC775]" />}
          title="開放標準"
          body="agentskills.io v1.2 規範相容；同一 bundle 任一 25+ 相容工具皆可載入，無廠商鎖定。"
        />
      </div>

      <H2>下一步</H2>
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <BeamFrame>
          <Link
            to="/docs/your-first-skill"
            className="inline-flex items-center gap-1.5 rounded-md bg-[#EEECEA] px-4 py-2 text-[14px] font-medium text-[#08080A]"
          >
            撰寫第一個技能
            <ArrowRight className="h-3 w-3" />
          </Link>
        </BeamFrame>
        <Link
          to="/docs/risk-tiers"
          className="inline-flex items-center gap-1.5 rounded-md border border-[rgba(255,255,255,0.10)] bg-[#171719] px-4 py-2 text-[14px] font-medium text-[#EEECEA] hover:bg-[#1F1F22]"
        >
          <KeySquare className="h-3 w-3" />
          了解風險層級
        </Link>
      </div>

      <p className="mt-10 text-[14px] text-[#5E5B55]">
        想看實際技能？前往 <Link to="/browse" className="text-[#C9C5F2] hover:underline">瀏覽技能登錄</Link>。
      </p>
    </DocsLayout>
  )
}

function H2({ children }: { children: React.ReactNode }) {
  return (
    <h2 className="mt-10 text-[20px] font-semibold tracking-tight text-[#EEECEA]">{children}</h2>
  )
}

function FeatureCard({
  icon,
  title,
  body,
}: {
  icon: React.ReactNode
  title: string
  body: string
}) {
  return (
    <div className="rounded-md border border-[rgba(255,255,255,0.06)] bg-[#0F0F12] p-4">
      <div className="mb-2 flex items-center gap-2">
        {icon}
        <h3 className="text-[14px] font-medium text-[#EEECEA]">{title}</h3>
      </div>
      <p className="text-[14px] leading-relaxed text-[#A8A49C]">{body}</p>
    </div>
  )
}
