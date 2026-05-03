import { Link } from 'react-router'
import { ArrowRight, FileText, Info, AlertTriangle, Check, X, Edit } from 'lucide-react'
import { DocsLayout } from '@/components/DocsLayout'
import { BeamFrame } from '@/components/BeamFrame'

/**
 * S094d → S098h: `/docs/your-first-skill` walkthrough page (dark theme migration).
 *
 * 對齊 docs/grimo/ui/prototype/Skills Hub Docs.html (v2 dark theme)。
 * S098h: bug fix — 原 light-theme inline hex (`#181818` text on `bg-white`)
 * 在 v2 dark page bg `#08080A` 上呈 black-on-near-black (user 截圖回報)。
 * Migration map：page `--bg`(#08080A) / card `--bg-2`(#0F0F12) / elevated
 * `--bg-3`(#171719) / ink `--ink`(#EEECEA) / muted `--ink-2`(#A8A49C) / `--line`
 * (rgba(255,255,255,0.06))。Tests are DOM-shape only — color swap doesn't break them.
 */
export function YourFirstSkillPage() {
  return (
    <DocsLayout>
      <p className="mb-1 text-[14px] text-[#A8A49C]">
        文件 <span className="mx-1 text-[#5E5B55]">/</span>
        入門 <span className="mx-1 text-[#5E5B55]">/</span>
        <span className="text-[#EEECEA]">撰寫第一個技能</span>
      </p>
      <h1 className="text-[28px] font-semibold tracking-tight text-[#EEECEA]">撰寫你的第一個技能</h1>

      <div className="mt-2 flex flex-wrap items-center gap-2 text-[14px] text-[#A8A49C]">
        <span>2 週前更新</span>
        <Dot />
        <span>閱讀 5 分鐘</span>
        <Dot />
        <span>依 agentskills.io v1.2</span>
        <a
          href="https://github.com/samzhu/skills-hub"
          className="ml-auto inline-flex items-center gap-1 text-[#A8A49C] hover:text-[#EEECEA]"
        >
          <Edit className="h-3 w-3" />
          在 GitHub 編輯
        </a>
      </div>

      <p className="mt-5 text-[14.5px] leading-relaxed text-[#A8A49C]">
        這份是給沒發過技能的作者看的 walkthrough。看完你會帶走一份能用的{' '}
        <Code>SKILL.md</Code> 與一個對「上傳時自動掃描器在檢查什麼」的心智模型。
        本頁不綁特定 agent — agentskills.io 標準下任何相容工具都通用。
      </p>

      {/* §1 Minimum viable skill */}
      <H2 anchor="minimum">最小可行技能</H2>
      <P>
        一份合法 bundle 只需要 1 個檔案：<Code>SKILL.md</Code>，含 YAML frontmatter
        與 markdown 內文。其他全部 optional（<Code>scripts/</Code>、
        <Code>references/</Code>、<Code>assets/</Code>）。從這裡開始：
      </P>
      <CodeBlock filename="date-formatter/SKILL.md">{`---
name: date-formatter
description: Convert between common date formats. Use when the
  user pastes a date string and asks to reformat, or
  when producing structured date output.
version: 1.0.0
license: MIT
---

# Date formatter

Invoke this skill whenever the user needs to convert a
date between formats. Accepts free-form input like
"Jan 3, 2026" or "2026-01-03T08:00Z" and produces any
ISO 8601 or RFC 2822 variant.

## When to use
- The user pastes a date and asks to reformat it
- Output needs a specific timezone or locale`}</CodeBlock>

      <Callout tone="info">
        <strong>只有 <Code>name</Code> 與 <Code>description</Code> 是必填。</strong>{' '}
        其他全部 optional。此 bundle 通過驗證 + auto-publish 為 LOW risk —
        因為沒有 <Code>scripts/</Code> 資料夾。
      </Callout>

      {/* §2 Bundle structure */}
      <H2 anchor="bundle">Bundle 結構</H2>
      <P>
        當單檔不夠用時，把 bundle 組成下方四個慣例資料夾。只有 <Code>SKILL.md</Code>{' '}
        必填，其他由 agent 在執行時或載入時依需求取用。
      </P>
      <CodeBlock filename="date-formatter/">{`date-formatter/
├── SKILL.md      [required]
├── scripts/      [triggers scan]
│   └── normalize.sh
├── references/   [optional]
│   └── iso-8601.md
└── assets/       [optional]
    └── examples.yaml`}</CodeBlock>

      {/* §3 Required fields */}
      <H2 anchor="required">必填欄位</H2>
      <FieldCard name="name" tags={['required', 'string', '≤ 64 chars']}>
        Lowercase + hyphen 連接；同一 org 內 unique — 若同名再 publish，會 bump 版本而非建新 skill。
        用於 URL、CLI 指令、skill imports。範例：<Code>docker-compose-helper</Code>
      </FieldCard>
      <FieldCard name="description" tags={['required', 'string', '≤ 1024 chars']}>
        一段簡潔段落描述「skill 做什麼 + agent 何時該呼叫」。Semantic search 對此欄位做 embedding —
        寫成 trigger 條件，不是行銷文案。下一節有範例。
      </FieldCard>
      <p className="mt-3 text-[12.5px] text-[#A8A49C]">
        選用欄位：<Code>version</Code>、<Code>author</Code>、<Code>license</Code>、{' '}
        <Code>compatibility</Code>、<Code>metadata</Code>、<Code>allowed-tools</Code>。
      </p>

      {/* §4 Writing description */}
      <H2 anchor="description">撰寫有效的 description</H2>
      <P>
        Semantic search 用 Gemini 把你寫的 description embed 成向量，與 user query 比對。
        模型認得具體動詞、trigger 條件、領域名詞 — 它忽略行銷形容詞。同一 skill 兩種寫法：
      </P>
      <div className="mt-3 grid gap-3 sm:grid-cols-2">
        <CompareCard tone="good">
          <p className="font-mono text-[12.5px] text-[#EEECEA]">
            Generate and validate docker-compose files with multi-service awareness. Use when the user edits compose.yml or needs a new stack.
          </p>
          <p className="mt-3 border-t border-dashed border-[rgba(29,158,117,0.20)] pt-3 text-[11.5px] leading-relaxed text-[#5E5B55]">
            具體動詞（"generate"、"validate"）、領域名詞（"compose.yml"）、trigger 條件（"when the user edits"）。配對 Docker、orchestration、YAML、stack 各種查詢。
          </p>
        </CompareCard>
        <CompareCard tone="bad">
          <p className="font-mono text-[12.5px] text-[#EEECEA]">
            Powerful and elegant container orchestration helper that delivers seamless productivity gains.
          </p>
          <p className="mt-3 border-t border-dashed border-[rgba(226,75,74,0.20)] pt-3 text-[11.5px] leading-relaxed text-[#5E5B55]">
            形容詞無 content（"powerful"、"elegant"、"seamless"）— embedding 認不出 trigger 條件，semantic search 不到。Marketing blurb，不是 skill description。
          </p>
        </CompareCard>
      </div>

      {/* §5 Risk tiers */}
      <H2 anchor="risk">各風險層級的觸發條件</H2>
      <P>
        每次 publish 都跑 auto-scanner。Tier 決定 skill 是即時上架還是等人工審核。
      </P>
      <div className="mt-4 flex flex-col gap-3">
        <RiskRow tier="LOW" tone="success">
          <strong>立即上架。</strong> 沒 scripts/ 資料夾，或 scripts 只含安全 patterns。
          一般 documentation/utility skill 多落這層。
        </RiskRow>
        <RiskRow tier="MEDIUM" tone="warning">
          <strong>附警告標籤上架。</strong> Scripts 存在但無危險 pattern — 最多 3 個外部 URL
          且都在 trusted-source registry。Consumers 在搜尋時看得到 tier。
        </RiskRow>
        <RiskRow tier="HIGH" tone="danger">
          <strong>暫停上架，等待人工審核。</strong> 偵測到 dangerous patterns（<Code>rm -rf</Code>、
          <Code>curl | bash</Code>、<Code>~/.ssh</Code>、<Code>~/.aws</Code> 等敏感路徑或可疑 shell）。
        </RiskRow>
      </div>
      <Callout tone="warn">
        <strong>HIGH risk 不等於 rejection。</strong> Reviewer 會選一個：approve（含 audit log
        備註）/ ask author to fix specific findings / reject。完整規則目錄之後會在
        <Code>/docs/risk-tiers</Code>。
      </Callout>

      {/* §6 Final CTA */}
      <div className="mt-10 rounded-lg border border-[rgba(255,255,255,0.06)] bg-[#0F0F12] p-5">
        <p className="text-[16px] font-semibold text-[#EEECEA]">準備發佈了嗎？</p>
        <p className="mt-1 text-[14px] text-[#A8A49C]">
          你的 bundle 有了、frontmatter 乾淨、知道 scanner 在檢查什麼。下一步把 zip 傳上來。
        </p>
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <BeamFrame>
            <Link
              to="/publish"
              className="inline-flex items-center gap-1.5 rounded-md bg-[#EEECEA] px-4 py-2 text-[14px] font-medium text-[#08080A]"
            >
              上傳你的 bundle
              <ArrowRight className="h-3 w-3" />
            </Link>
          </BeamFrame>
          <a
            href="https://github.com/anthropics/skills"
            className="inline-flex items-center gap-1.5 rounded-md border border-[rgba(255,255,255,0.10)] bg-[#171719] px-4 py-2 text-[14px] font-medium text-[#EEECEA] hover:bg-[#1F1F22]"
          >
            <FileText className="h-3 w-3" />
            查看更多範例
          </a>
        </div>
      </div>

      {/* §7 Prev/Next nav */}
      <nav className="mt-8 flex items-center justify-between border-t border-[rgba(255,255,255,0.06)] pt-5 text-[14px]">
        <span className="cursor-not-allowed text-[#5E5B55]" title="即將推出">
          ← 概覽
        </span>
        <span className="cursor-not-allowed text-[#5E5B55]" title="即將推出">
          SKILL.md 規範 →
        </span>
      </nav>
    </DocsLayout>
  )
}

// ============= Inline helpers =============

function Dot() {
  return <span className="inline-block h-1 w-1 rounded-full bg-[#5E5B55]" />
}

function H2({ anchor, children }: { anchor: string; children: React.ReactNode }) {
  return (
    <h2 id={anchor} className="mt-10 text-[20px] font-semibold tracking-tight text-[#EEECEA]">
      {children} <span className="ml-1 text-[#5E5B55]">#</span>
    </h2>
  )
}

function P({ children }: { children: React.ReactNode }) {
  return <p className="mt-3 text-[14px] leading-relaxed text-[#A8A49C]">{children}</p>
}

function Code({ children }: { children: React.ReactNode }) {
  return (
    <code className="rounded-sm bg-[#171719] px-1 py-0.5 font-mono text-[12.5px] text-[#EEECEA]">
      {children}
    </code>
  )
}

function CodeBlock({ filename, children }: { filename: string; children: string }) {
  return (
    <div className="mt-3 overflow-hidden rounded-md border border-[rgba(255,255,255,0.06)]">
      <div className="flex items-center justify-between border-b border-[rgba(255,255,255,0.06)] bg-[#0F0F12] px-3 py-2 text-[11.5px] text-[#A8A49C]">
        <span className="font-mono">{filename}</span>
        <span className="text-[10px] uppercase tracking-wider text-[#5E5B55]">copy</span>
      </div>
      <pre className="overflow-x-auto bg-[#0F0F12] p-3 text-[14px] leading-relaxed">
        <code className="font-mono text-[#EEECEA]">{children}</code>
      </pre>
    </div>
  )
}

function Callout({
  tone,
  children,
}: {
  tone: 'info' | 'warn'
  children: React.ReactNode
}) {
  const styles =
    tone === 'info'
      ? { bg: 'rgba(127,119,221,0.10)', border: 'rgba(127,119,221,0.20)', fg: '#A8A49C', icon: <Info className="h-3.5 w-3.5 text-[#C9C5F2]" /> }
      : { bg: 'rgba(239,159,39,0.08)', border: 'rgba(239,159,39,0.20)', fg: '#A8A49C', icon: <AlertTriangle className="h-3.5 w-3.5 text-[#FAC775]" /> }
  return (
    <div
      className="mt-4 flex items-start gap-2.5 rounded-md border p-3 text-[14px] leading-relaxed"
      style={{ backgroundColor: styles.bg, borderColor: styles.border, color: styles.fg }}
    >
      <span className="mt-0.5 shrink-0">{styles.icon}</span>
      <div className="flex-1">{children}</div>
    </div>
  )
}

function FieldCard({
  name,
  tags,
  children,
}: {
  name: string
  tags: string[]
  children: React.ReactNode
}) {
  return (
    <div className="mt-3 rounded-md border border-[rgba(255,255,255,0.06)] bg-[#0F0F12] p-4">
      <div className="flex flex-wrap items-center gap-2">
        <code className="rounded-sm bg-[#171719] px-2 py-0.5 font-mono text-[14px] text-[#EEECEA]">
          {name}
        </code>
        {tags.map((t, i) => (
          <span key={i} className="text-[11px] text-[#A8A49C]">
            {i > 0 && <span className="mr-2 text-[#5E5B55]">·</span>}
            {t}
          </span>
        ))}
      </div>
      <p className="mt-2 text-[14px] leading-relaxed text-[#A8A49C]">{children}</p>
    </div>
  )
}

function CompareCard({
  tone,
  children,
}: {
  tone: 'good' | 'bad'
  children: React.ReactNode
}) {
  const styles =
    tone === 'good'
      ? { border: 'rgba(29,158,117,0.20)', bg: 'rgba(29,158,117,0.07)', label: '#6FD8B0', icon: <Check className="h-3 w-3" />, labelText: 'Indexes well' }
      : { border: 'rgba(226,75,74,0.20)', bg: 'rgba(226,75,74,0.07)', label: '#F2A6A6', icon: <X className="h-3 w-3" />, labelText: 'Misses' }
  return (
    <div className="rounded-md border p-4" style={{ borderColor: styles.border, backgroundColor: styles.bg }}>
      <p className="mb-2 inline-flex items-center gap-1.5 text-[10.5px] font-semibold uppercase tracking-wider" style={{ color: styles.label }}>
        {styles.icon}
        {styles.labelText}
      </p>
      {children}
    </div>
  )
}

function RiskRow({
  tier,
  tone,
  children,
}: {
  tier: string
  tone: 'success' | 'warning' | 'danger'
  children: React.ReactNode
}) {
  const styles = {
    success: { bg: 'rgba(29,158,117,0.14)', fg: '#6FD8B0' },
    warning: { bg: 'rgba(239,159,39,0.14)', fg: '#FAC775' },
    danger: { bg: 'rgba(226,75,74,0.14)', fg: '#F2A6A6' },
  }[tone]
  return (
    <div className="flex items-start gap-3 rounded-md border border-[rgba(255,255,255,0.06)] bg-[#0F0F12] p-4">
      <span
        className="mt-0.5 shrink-0 rounded-md px-2 py-0.5 font-mono text-[10.5px] font-semibold uppercase tracking-wider"
        style={{ backgroundColor: styles.bg, color: styles.fg }}
      >
        {tier}
      </span>
      <p className="flex-1 text-[14px] leading-relaxed text-[#A8A49C]">{children}</p>
    </div>
  )
}
