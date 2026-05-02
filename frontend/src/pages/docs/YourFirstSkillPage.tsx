import { Link } from 'react-router'
import { ArrowRight, FileText, Info, AlertTriangle, Check, X, Edit } from 'lucide-react'
import { DocsLayout } from '@/components/DocsLayout'
import { BeamFrame } from '@/components/BeamFrame'

/**
 * S094d — `/docs/your-first-skill` walkthrough page.
 *
 * 對齊 docs/grimo/ui/prototype/docs_page_write_your_first_skill.html.
 * 設計目的（per README ll.245-279）：把 Skills Hub 三個核心機制（frontmatter
 * validation / semantic search indexing / risk tier classification）在一頁內
 * 讓作者從「不知道」變成「有心智模型」— P2-P5 SBE 的 prerequisite。
 *
 * 不用 react-markdown 是刻意選擇：單頁靜態內容直接 JSX 比解析 .md 快、bundle
 * 小、可直接 lint；後續 docs spec 若需多頁 + 動態檢索可再考慮 markdown parser。
 */
export function YourFirstSkillPage() {
  return (
    <DocsLayout>
      <p className="mb-1 text-[12px] text-[#5C5751]">
        Docs <span className="mx-1 text-[#C5C0BC]">/</span>
        Getting started <span className="mx-1 text-[#C5C0BC]">/</span>
        <span className="text-[#181818]">Your first skill</span>
      </p>
      <h1 className="text-[28px] font-semibold tracking-tight text-[#181818]">Write your first skill</h1>

      <div className="mt-2 flex flex-wrap items-center gap-2 text-[12px] text-[#5C5751]">
        <span>Updated 2 weeks ago</span>
        <Dot />
        <span>5 min read</span>
        <Dot />
        <span>Based on agentskills.io v1.2</span>
        <a
          href="https://github.com/samzhu/skills-hub"
          className="ml-auto inline-flex items-center gap-1 text-[#5C5751] hover:text-[#181818]"
        >
          <Edit className="h-3 w-3" />
          Edit on GitHub
        </a>
      </div>

      <p className="mt-5 text-[14.5px] leading-relaxed text-[#181818]">
        這份是給沒發過技能的作者看的 walkthrough。看完你會帶走一份能用的{' '}
        <Code>SKILL.md</Code> 與一個對「上傳時自動掃描器在檢查什麼」的心智模型。
        本頁不綁特定 agent — agentskills.io 標準下任何相容工具都通用。
      </p>

      {/* §1 Minimum viable skill */}
      <H2 anchor="minimum">The minimum viable skill</H2>
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
      <H2 anchor="bundle">The bundle</H2>
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
      <H2 anchor="required">Required fields</H2>
      <FieldCard name="name" tags={['required', 'string', '≤ 64 chars']}>
        Lowercase + hyphen 連接；同一 org 內 unique — 若同名再 publish，會 bump 版本而非建新 skill。
        用於 URL、CLI 指令、skill imports。範例：<Code>docker-compose-helper</Code>
      </FieldCard>
      <FieldCard name="description" tags={['required', 'string', '≤ 1024 chars']}>
        一段簡潔段落描述「skill 做什麼 + agent 何時該呼叫」。Semantic search 對此欄位做 embedding —
        寫成 trigger 條件，不是行銷文案。下一節有範例。
      </FieldCard>
      <p className="mt-3 text-[12.5px] text-[#A09B96]">
        Optional fields: <Code>version</Code>、<Code>author</Code>、<Code>license</Code>、{' '}
        <Code>compatibility</Code>、<Code>metadata</Code>、<Code>allowed-tools</Code>。
      </p>

      {/* §4 Writing description */}
      <H2 anchor="description">Writing a description that works</H2>
      <P>
        Semantic search 用 Gemini 把你寫的 description embed 成向量，與 user query 比對。
        模型認得具體動詞、trigger 條件、領域名詞 — 它忽略行銷形容詞。同一 skill 兩種寫法：
      </P>
      <div className="mt-3 grid gap-3 sm:grid-cols-2">
        <CompareCard tone="good">
          <p className="font-mono text-[12.5px] text-[#181818]">
            Generate and validate docker-compose files with multi-service awareness. Use when the user edits compose.yml or needs a new stack.
          </p>
          <p className="mt-3 border-t border-dashed border-[#C5DDC9] pt-3 text-[11.5px] leading-relaxed text-[#27500A]">
            具體動詞（"generate"、"validate"）、領域名詞（"compose.yml"）、trigger 條件（"when the user edits"）。配對 Docker、orchestration、YAML、stack 各種查詢。
          </p>
        </CompareCard>
        <CompareCard tone="bad">
          <p className="font-mono text-[12.5px] text-[#181818]">
            Powerful and elegant container orchestration helper that delivers seamless productivity gains.
          </p>
          <p className="mt-3 border-t border-dashed border-[#F5C2C2] pt-3 text-[11.5px] leading-relaxed text-[#791F1F]">
            形容詞無 content（"powerful"、"elegant"、"seamless"）— embedding 認不出 trigger 條件，semantic search 不到。Marketing blurb，不是 skill description。
          </p>
        </CompareCard>
      </div>

      {/* §5 Risk tiers */}
      <H2 anchor="risk">What triggers each risk tier</H2>
      <P>
        每次 publish 都跑 auto-scanner。Tier 決定 skill 是即時上架還是等人工審核。
      </P>
      <div className="mt-4 flex flex-col gap-3">
        <RiskRow tier="LOW" tone="success">
          <strong>Publishes immediately.</strong> 沒 scripts/ 資料夾，或 scripts 只含安全 patterns。
          一般 documentation/utility skill 多落這層。
        </RiskRow>
        <RiskRow tier="MEDIUM" tone="warning">
          <strong>Publishes with a warning badge.</strong> Scripts 存在但無危險 pattern — 最多 3 個外部 URL
          且都在 trusted-source registry。Consumers 在搜尋時看得到 tier。
        </RiskRow>
        <RiskRow tier="HIGH" tone="danger">
          <strong>Blocked until reviewer approves.</strong> 偵測到 dangerous patterns（<Code>rm -rf</Code>、
          <Code>curl | bash</Code>、<Code>~/.ssh</Code>、<Code>~/.aws</Code> 等敏感路徑或可疑 shell）。
        </RiskRow>
      </div>
      <Callout tone="warn">
        <strong>HIGH risk 不等於 rejection。</strong> Reviewer 會選一個：approve（含 audit log
        備註）/ ask author to fix specific findings / reject。完整規則目錄之後會在
        <Code>/docs/risk-tiers</Code>。
      </Callout>

      {/* §6 Final CTA */}
      <div className="mt-10 rounded-lg border border-[#E6E1D9] bg-[#F9F8F4] p-5">
        <p className="text-[15px] font-semibold text-[#181818]">Ready to publish?</p>
        <p className="mt-1 text-[13px] text-[#5C5751]">
          你的 bundle 有了、frontmatter 乾淨、知道 scanner 在檢查什麼。下一步把 zip 傳上來。
        </p>
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <BeamFrame>
            <Link
              to="/publish"
              className="inline-flex items-center gap-1.5 rounded-md bg-[#181818] px-4 py-2 text-[13px] font-medium text-white"
            >
              Upload your bundle
              <ArrowRight className="h-3 w-3" />
            </Link>
          </BeamFrame>
          <a
            href="https://github.com/anthropics/skills"
            className="inline-flex items-center gap-1.5 rounded-md border border-[#E6E1D9] bg-white px-4 py-2 text-[13px] font-medium text-[#181818] hover:bg-[#F5F4ED]"
          >
            <FileText className="h-3 w-3" />
            See more examples
          </a>
        </div>
      </div>

      {/* §7 Prev/Next nav */}
      <nav className="mt-8 flex items-center justify-between border-t border-[#E6E1D9] pt-5 text-[12px]">
        <span className="cursor-not-allowed text-[#C5C0BC]" title="Coming soon">
          ← Overview
        </span>
        <span className="cursor-not-allowed text-[#C5C0BC]" title="Coming soon">
          SKILL.md spec →
        </span>
      </nav>
    </DocsLayout>
  )
}

// ============= Inline helpers =============

function Dot() {
  return <span className="inline-block h-1 w-1 rounded-full bg-[#C5C0BC]" />
}

function H2({ anchor, children }: { anchor: string; children: React.ReactNode }) {
  return (
    <h2 id={anchor} className="mt-10 text-[18px] font-semibold tracking-tight text-[#181818]">
      {children} <span className="ml-1 text-[#A09B96]">#</span>
    </h2>
  )
}

function P({ children }: { children: React.ReactNode }) {
  return <p className="mt-3 text-[14px] leading-relaxed text-[#181818]">{children}</p>
}

function Code({ children }: { children: React.ReactNode }) {
  return (
    <code className="rounded-sm bg-[#F5F4ED] px-1 py-0.5 font-mono text-[12.5px] text-[#181818]">
      {children}
    </code>
  )
}

function CodeBlock({ filename, children }: { filename: string; children: string }) {
  return (
    <div className="mt-3 overflow-hidden rounded-md border border-[#E6E1D9]">
      <div className="flex items-center justify-between border-b border-[#E6E1D9] bg-[#F9F8F4] px-3 py-2 text-[11.5px] text-[#5C5751]">
        <span className="font-mono">{filename}</span>
        <span className="text-[10px] uppercase tracking-wider text-[#A09B96]">copy</span>
      </div>
      <pre className="overflow-x-auto bg-[#FFFFFF] p-3 text-[12px] leading-relaxed">
        <code className="font-mono text-[#181818]">{children}</code>
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
      ? { bg: '#EEEDFE', fg: '#3C3489', icon: <Info className="h-3.5 w-3.5" /> }
      : { bg: '#FAEEDA', fg: '#633806', icon: <AlertTriangle className="h-3.5 w-3.5" /> }
  return (
    <div
      className="mt-4 flex items-start gap-2.5 rounded-md p-3 text-[13px] leading-relaxed"
      style={{ backgroundColor: styles.bg, color: styles.fg }}
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
    <div className="mt-3 rounded-md border border-[#E6E1D9] bg-white p-4">
      <div className="flex flex-wrap items-center gap-2">
        <code className="rounded-sm bg-[#F5F4ED] px-2 py-0.5 font-mono text-[13px] text-[#181818]">
          {name}
        </code>
        {tags.map((t, i) => (
          <span key={i} className="text-[11px] text-[#5C5751]">
            {i > 0 && <span className="mr-2 text-[#C5C0BC]">·</span>}
            {t}
          </span>
        ))}
      </div>
      <p className="mt-2 text-[13px] leading-relaxed text-[#181818]">{children}</p>
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
      ? { border: '#C5DDC9', bg: '#F0F8F0', label: '#27500A', icon: <Check className="h-3 w-3" />, labelText: 'Indexes well' }
      : { border: '#F5C2C2', bg: '#FCEBEB', label: '#791F1F', icon: <X className="h-3 w-3" />, labelText: 'Misses' }
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
    success: { bg: '#E1F5EE', fg: '#085041' },
    warning: { bg: '#FAEEDA', fg: '#633806' },
    danger: { bg: '#FCEBEB', fg: '#791F1F' },
  }[tone]
  return (
    <div className="flex items-start gap-3 rounded-md border border-[#E6E1D9] bg-white p-4">
      <span
        className="mt-0.5 shrink-0 rounded-md px-2 py-0.5 font-mono text-[10.5px] font-semibold uppercase tracking-wider"
        style={{ backgroundColor: styles.bg, color: styles.fg }}
      >
        {tier}
      </span>
      <p className="flex-1 text-[13px] leading-relaxed text-[#181818]">{children}</p>
    </div>
  )
}
