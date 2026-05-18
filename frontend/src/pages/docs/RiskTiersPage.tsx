import { Link } from 'react-router'
import { Shield, AlertTriangle, AlertOctagon, Sparkles } from 'lucide-react'
import { DocsLayout } from '@/components/DocsLayout'

/**
 * S098f — `/docs/risk-tiers` 風險層級完整說明。
 *
 * 對齊 docs/grimo/specs archived S036/S096c risk policy + SkillDetailPage
 * RISK_DESCRIPTION 訊息文字。Reference group docs 走「policy 公告」風格，
 * 不教使用者怎麼寫，只說「掃描器看到什麼樣的 pattern → 落哪一層」。
 */
export function RiskTiersPage() {
  return (
    <DocsLayout>
      <p className="mb-1 text-[16px] text-[#A8A49C]">
        文件 <span className="mx-1 text-[#5E5B55]">/</span>
        參考 <span className="mx-1 text-[#5E5B55]">/</span>
        <span className="text-[#EEECEA]">風險層級</span>
      </p>
      <h1 className="text-[30px] font-semibold tracking-tight text-[#EEECEA]">風險層級</h1>
      <p className="mt-3 text-[18px] leading-relaxed text-[#A8A49C]">
        每次發佈 skill 都會跑自動掃描器，依以下規則歸類為四級之一。Tier 決定
        consumer 在搜尋結果上看到的標示色與安全報告提示。HIGH 代表掃描器找到
        高風險 pattern；目前不提供人工上架通知流程。
      </p>

      <Tier
        level="NONE"
        icon={<Sparkles className="h-4 w-4 text-[#A8A49C]" />}
        toneBg="rgba(168,164,156,0.10)"
        toneBorder="rgba(168,164,156,0.30)"
        toneText="#A8A49C"
        title="未發現風險"
        body="掃描結果是 findings=[]，且 package 沒有 scripts/、沒有 allowed-tools。常見於只有 SKILL.md 的 prompt-only / reference-only skill。"
        note="注意：未發現風險不是安全保證；它只表示 scanner 沒找到已知問題，也沒有看到工具或腳本能力。"
      />

      <Tier
        level="LOW"
        icon={<Shield className="h-4 w-4 text-[#6FD8B0]" />}
        toneBg="rgba(29,158,117,0.10)"
        toneBorder="rgba(29,158,117,0.30)"
        toneText="#6FD8B0"
        title="自動上架"
        body="掃描結果可以是 findings=[]，但 package 有 scripts/ 或 allowed-tools。安全頁會顯示原因，例如這個技能可以要求 AI 使用 Bash、Write，或 package 內包含 scripts/ 檔案。"
      />

      <Tier
        level="MEDIUM"
        icon={<AlertTriangle className="h-4 w-4 text-[#FAC775]" />}
        toneBg="rgba(239,159,39,0.10)"
        toneBorder="rgba(239,159,39,0.30)"
        toneText="#FAC775"
        title="自動上架（顯警示標）"
        body="Scripts 存在且偵測到中等風險模式（例：寬鬆 file system 寫入、network call 至外部 trusted-source registry）。最多 3 個外部 URL 限制；Consumers 在搜尋與詳情頁上看得到 MEDIUM 標。"
        note="MEDIUM 仍自動上架，但 consumer 端會看到警示色 — 鼓勵安裝前先審視 scripts/ 內容。"
      />

      <Tier
        level="HIGH"
        icon={<AlertOctagon className="h-4 w-4 text-[#F2A6A6]" />}
        toneBg="rgba(226,75,74,0.08)"
        toneBorder="rgba(226,75,74,0.30)"
        toneText="#F2A6A6"
        title="高風險警示"
        body="偵測到 dangerous patterns — rm -rf、curl | bash 等 RCE 模式、~/.ssh / ~/.aws 等敏感路徑存取、可疑 shell 解析行為。頁面會顯示 HIGH 標示與安全報告，作者可修正套件後重新上傳。"
        note="HIGH 不是人工核准狀態。它只代表目前掃描結果需要使用者先看安全報告，再決定是否分享或安裝。"
      />

      <h2 className="mt-10 text-[22px] font-semibold tracking-tight text-[#EEECEA]">遇到 HIGH 風險怎麼辦？</h2>
      <p className="mt-3 text-[16px] leading-relaxed text-[#A8A49C]">
        作者：依錯誤訊息提示修正 scripts/ 後重新上傳；常見修正包含拿掉
        <code className="mx-1 rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">curl | bash</code>、
        替換為固定版本下載、或避免 SSH/AWS 設定檔讀取。
      </p>
      <p className="mt-2 text-[16px] leading-relaxed text-[#A8A49C]">
        消費者：安裝前查看完整掃描報告（每 finding 含 rule ID + 行號），再決定是否信任此技能。
      </p>

      <nav className="mt-10 flex items-center justify-between border-t border-[rgba(255,255,255,0.06)] pt-5 text-[16px]">
        <Link to="/docs/your-first-skill" className="text-[#A8A49C] hover:text-[#EEECEA]">
          ← 撰寫第一個技能
        </Link>
        <span className="cursor-not-allowed text-[#5E5B55]" title="即將推出">
          SKILL.md 規範 →
        </span>
      </nav>
    </DocsLayout>
  )
}

function Tier({
  level,
  icon,
  toneBg,
  toneBorder,
  toneText,
  title,
  body,
  note,
}: {
  level: string
  icon: React.ReactNode
  toneBg: string
  toneBorder: string
  toneText: string
  title: string
  body: string
  note?: string
}) {
  return (
    <div
      className="mt-4 rounded-md border p-4"
      style={{ backgroundColor: toneBg, borderColor: toneBorder }}
    >
      <div className="flex items-center gap-2">
        {icon}
        <span className="font-mono text-[10.5px] font-semibold uppercase tracking-wider" style={{ color: toneText }}>
          {level}
        </span>
        <span className="text-[16px] font-medium text-[#EEECEA]">{title}</span>
      </div>
      <p className="mt-2 text-[16px] leading-relaxed text-[#A8A49C]">{body}</p>
      {note && (
        <p className="mt-2 border-t border-dashed pt-2 text-[16px] leading-relaxed" style={{ borderColor: toneBorder, color: toneText }}>
          {note}
        </p>
      )}
    </div>
  )
}
