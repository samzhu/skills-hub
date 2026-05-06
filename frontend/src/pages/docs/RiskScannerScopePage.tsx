import { Link } from 'react-router'
import { Shield, AlertOctagon, AlertTriangle, Check, X } from 'lucide-react'
import { DocsLayout } from '@/components/DocsLayout'

/**
 * S099e5 — `/docs/risk-scanner-scope` 公開「我們做什麼 / 不做什麼」mapping。
 *
 * 對齊 [OWASP LLM Top 10 v1.1 (2023)](https://owasp.org/www-project-top-10-for-large-language-model-applications/)；
 * 對 consumer 透明列出 Skills Hub 的 risk scanner 涵蓋範圍與已知 limitation。
 *
 * 對應 S099 META §6 Gap D 計劃。
 */
export function RiskScannerScopePage() {
  return (
    <DocsLayout>
      <p className="mb-1 text-[16px] text-[#A8A49C]">
        文件 <span className="mx-1 text-[#5E5B55]">/</span>
        參考 <span className="mx-1 text-[#5E5B55]">/</span>
        <span className="text-[#EEECEA]">Risk Scanner 涵蓋範圍</span>
      </p>
      <h1 className="text-[30px] font-semibold tracking-tight text-[#EEECEA]">Risk Scanner 涵蓋範圍與限制</h1>
      <p className="mt-3 text-[18px] leading-relaxed text-[#A8A49C]">
        Skills Hub 的自動風險評分系統對齊{' '}
        <a href="https://owasp.org/www-project-top-10-for-large-language-model-applications/" className="text-[#C9C5F2] hover:underline">
          OWASP LLM Top 10 v1.1
        </a>
        。本頁透明列出我們對每項威脅的涵蓋程度，給 consumer 安裝前判斷風險的參考。「Pass」不等於 100% 安全 — 仍須對 SKILL.md 內容做最後審視。
      </p>

      <H2>OWASP LLM01-10 對應表</H2>

      <Item
        id="llm01"
        title="LLM01 — Prompt Injection"
        threat="惡意輸入操控 LLM 導致未授權存取或決策被劫持。"
        coverage="covered"
        details="Risk scanner 偵測明顯 RCE patterns（curl | bash 等）。SKILL.md instructions 內隱藏的 jailbreak / instruction-override patterns（ignore previous instructions 等 8 HIGH + 6 MEDIUM 模式）已整合至掃描器。"
      />

      <Item
        id="llm02"
        title="LLM02 — Insecure Output Handling"
        threat="未驗證 LLM 輸出可能導致下游 code execution / XSS。"
        coverage="oos"
        details="Skills Hub 是中介市集；不執行 agent 也不處理 LLM 輸出。Consumer agent 端責任。"
      />

      <Item
        id="llm03"
        title="LLM03 — Training Data Poisoning"
        threat="污染訓練資料影響 model 行為。"
        coverage="oos"
        details="Skills Hub 不訓練 model；scope 之外。"
      />

      <Item
        id="llm04"
        title="LLM04 — Model Denial of Service"
        threat="資源耗盡攻擊使 LLM 服務中斷。"
        coverage="partial"
        details="已整合 resource-hint scanner：fork bomb、/dev/zero、infinite loop 等 3 HIGH + 3 MEDIUM 模式。複雜的 memory allocation 分析（如 mmap 參數計算）尚未涵蓋。"
      />

      <Item
        id="llm05"
        title="LLM05 — Supply Chain Vulnerabilities"
        threat="依賴 compromised components 致系統失守。"
        coverage="partial"
        details="Risk scanner 偵測 curl 來源未驗證；已整合依賴漏洞掃描（requirements.txt / package.json 對 OSV.dev querybatch）。SBOM 產生尚未涵蓋。"
      />

      <Item
        id="llm06"
        title="LLM06 — Sensitive Information Disclosure"
        threat="LLM 輸出意外洩漏敏感資訊。"
        coverage="covered"
        details="偵測 ~/.ssh、~/.aws 等敏感路徑存取 → 直接判 HIGH。已整合 hardcoded credentials 偵測（API key / OAuth token / password 等 6 類模式掃描 scripts）。"
      />

      <Item
        id="llm07"
        title="LLM07 — Insecure Plugin Design"
        threat="LLM 插件處理未信任輸入或缺乏存取控制。"
        coverage="partial"
        details="allowed-tools 宣告觸發 risk scan；尚未做 plugin-to-plugin composability 互動分析。Post-MVP 候選。"
      />

      <Item
        id="llm08"
        title="LLM08 — Excessive Agency"
        threat="LLM 被授予過多自主權致非預期行為。"
        coverage="covered"
        details="allowed-tools 越多分越高；HIGH 自動進審核。Consumer 詳情頁顯 allowed-tools 列表方便評估 agency budget。"
      />

      <Item
        id="llm09"
        title="LLM09 — Overreliance"
        threat="盲信 LLM 輸出導致決策失誤 / 法律風險。"
        coverage="oos"
        details="Skills Hub 不參與 agent 決策；scope 之外。docs 各風險層級頁明示「scan ≠ certified safe」(NONE tooltip 也明示)。"
      />

      <Item
        id="llm10"
        title="LLM10 — Model Theft"
        threat="未授權存取專有 LLM model weights。"
        coverage="oos"
        details="Skills Hub 不託管 model weights；scope 之外。"
      />

      <H2>覆蓋率總覽</H2>
      <div className="mt-3 grid gap-3 sm:grid-cols-2">
        <SummaryCard label="✅ Covered" count={3} note="LLM01 / LLM06 / LLM08" />
        <SummaryCard label="🟡 Partial" count={3} note="LLM04 / LLM05 / LLM07" />
        <SummaryCard label="❌ Gap" count={0} note="—" />
        <SummaryCard label="◯ Out of Scope" count={4} note="LLM02 / LLM03 / LLM09 / LLM10" />
      </div>

      <Callout>
        <strong className="text-[#EEECEA]">免責聲明：</strong>
        OWASP LLM Top 10 涵蓋率 ≠ 100% 安全保證。Skills Hub 的掃描器偵測「known patterns」；novel attack 或 SKILL.md instructions 內語意層級風險仍須 consumer 自行評估。建議結合 risk-tier badge + 內文 review + 來源信任綜合判斷。
      </Callout>

      <nav className="mt-10 flex items-center justify-between border-t border-[rgba(255,255,255,0.06)] pt-5 text-[16px]">
        <Link to="/docs/risk-tiers" className="text-[#A8A49C] hover:text-[#EEECEA]">← 風險層級</Link>
        <span className="text-[#5E5B55]">完 ✓</span>
      </nav>
    </DocsLayout>
  )
}

function H2({ children }: { children: React.ReactNode }) {
  return <h2 className="mt-10 text-[22px] font-semibold tracking-tight text-[#EEECEA]">{children}</h2>
}

type Coverage = 'covered' | 'partial' | 'gap' | 'oos'

function Item({
  id,
  title,
  threat,
  coverage,
  details,
}: {
  id: string
  title: string
  threat: string
  coverage: Coverage
  details: string
}) {
  const palette = {
    covered: { icon: <Check className="h-3.5 w-3.5" />, label: 'Covered', bg: 'rgba(29,158,117,0.10)', border: 'rgba(29,158,117,0.30)', fg: '#6FD8B0' },
    partial: { icon: <AlertTriangle className="h-3.5 w-3.5" />, label: 'Partial', bg: 'rgba(239,159,39,0.10)', border: 'rgba(239,159,39,0.30)', fg: '#FAC775' },
    gap: { icon: <AlertOctagon className="h-3.5 w-3.5" />, label: 'Gap', bg: 'rgba(226,75,74,0.08)', border: 'rgba(226,75,74,0.30)', fg: '#F2A6A6' },
    oos: { icon: <X className="h-3.5 w-3.5" />, label: 'Out of Scope', bg: 'rgba(168,164,156,0.08)', border: 'rgba(168,164,156,0.20)', fg: '#A8A49C' },
  }[coverage]
  return (
    <div
      id={id}
      className="mt-3 rounded-md border p-4"
      style={{ backgroundColor: palette.bg, borderColor: palette.border }}
    >
      <div className="flex items-start justify-between gap-3">
        <h3 className="text-[16px] font-medium text-[#EEECEA]">{title}</h3>
        <span
          className="inline-flex shrink-0 items-center gap-1 rounded-full px-2 py-0.5 text-[10.5px] font-semibold uppercase tracking-wider"
          style={{ color: palette.fg }}
        >
          {palette.icon}
          {palette.label}
        </span>
      </div>
      <p className="mt-1.5 text-[12.5px] leading-relaxed text-[#A8A49C]">
        <strong className="text-[#EEECEA]">威脅：</strong>{threat}
      </p>
      <p className="mt-1 text-[12.5px] leading-relaxed text-[#A8A49C]">
        <strong className="text-[#EEECEA]">Skills Hub 對應：</strong>{details}
      </p>
    </div>
  )
}

function SummaryCard({ label, count, note }: { label: string; count: number; note: string }) {
  return (
    <div className="rounded-md border border-[rgba(255,255,255,0.06)] bg-[#0F0F12] p-3">
      <p className="text-[11px] font-medium uppercase tracking-wider text-[#A8A49C]">{label}</p>
      <p className="mt-1 font-mono text-[22px] font-medium text-[#EEECEA]">{count}</p>
      <p className="mt-0.5 text-[11px] text-[#A8A49C]">{note}</p>
    </div>
  )
}

function Callout({ children }: { children: React.ReactNode }) {
  return (
    <div className="mt-4 rounded-md border p-3 text-[16px] leading-relaxed text-[#A8A49C]" style={{ backgroundColor: 'rgba(127,119,221,0.08)', borderColor: 'rgba(127,119,221,0.20)' }}>
      <Shield className="mr-1.5 inline-block h-3.5 w-3.5 text-[#C9C5F2]" />
      {children}
    </div>
  )
}
