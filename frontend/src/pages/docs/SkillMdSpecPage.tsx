import { Link } from 'react-router'
import { DocsLayout } from '@/components/DocsLayout'

/**
 * S098f2 — `/docs/skill-md-spec` SKILL.md 規範總覽。
 *
 * agentskills.io v1.2 標準的精煉 reference；不取代上游 spec，僅作為
 * Skills Hub publisher 在地化說明 + 連回外部規範。
 */
export function SkillMdSpecPage() {
  return (
    <DocsLayout>
      <p className="mb-1 text-[16px] text-[#A8A49C]">
        文件 <span className="mx-1 text-[#5E5B55]">/</span>
        參考 <span className="mx-1 text-[#5E5B55]">/</span>
        <span className="text-[#EEECEA]">SKILL.md 規範</span>
      </p>
      <h1 className="text-[30px] font-semibold tracking-tight text-[#EEECEA]">SKILL.md 規範</h1>
      <p className="mt-3 text-[18px] leading-relaxed text-[#A8A49C]">
        Skills Hub 採用開放標準{' '}
        <a href="https://agentskills.io" className="text-[#C9C5F2] hover:underline">
          agentskills.io v1.2
        </a>{' '}
        — 同份 SKILL.md bundle 在 Claude Code、Cursor、Gemini CLI 等任一相容
        agent 都能載入。本頁是在地化的精煉 reference，完整規範請以 agentskills.io
        為準。
      </p>

      <H2>SKILL.md 結構</H2>
      <P>
        合法 SKILL.md 由兩個段落組成：
      </P>
      <ol className="mt-2 list-decimal space-y-1 pl-5 text-[16px] text-[#A8A49C]">
        <li><strong className="text-[#EEECEA]">YAML frontmatter</strong> — 以 <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">---</code> 包夾的 metadata 區塊，宣告 name / description / 版本等</li>
        <li><strong className="text-[#EEECEA]">Markdown 內文</strong> — 給 agent 讀的 instruction 主文，描述工具行為、適用條件、輸入輸出</li>
      </ol>

      <H2>核心約束</H2>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-[16px] text-[#A8A49C]">
        <li>檔名必須為 <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">SKILL.md</code>（大小寫敏感）</li>
        <li>frontmatter 必填：<code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">name</code>、<code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">description</code></li>
        <li>name 為 <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">[a-z0-9-]</code> + ≤64 字元；同 org 內 unique</li>
        <li>description 為單段純文字 + ≤1024 字元；用於 semantic search embedding</li>
        <li>檔案大小限制：bundle zip ≤ 5MB（Skills Hub 上傳限制）</li>
      </ul>

      <H2>進階：optional 欄位與資料夾</H2>
      <P>
        <Link to="/docs/frontmatter" className="text-[#C9C5F2] hover:underline">
          Frontmatter 欄位
        </Link>{' '}
        頁列出所有 optional metadata。<Link to="/docs/bundle" className="text-[#C9C5F2] hover:underline">
          Bundle 結構
        </Link>{' '}
        頁說明 <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">scripts/</code>、
        <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">references/</code>、
        <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">assets/</code> 三個慣例資料夾。
      </P>

      <Callout>
        想看完整教學？前往 <Link to="/docs/your-first-skill" className="text-[#C9C5F2] hover:underline">撰寫第一個技能</Link> walkthrough，
        從 minimum viable bundle 開始一步步建。
      </Callout>

      <nav className="mt-10 flex items-center justify-between border-t border-[rgba(255,255,255,0.06)] pt-5 text-[16px]">
        <Link to="/docs/your-first-skill" className="text-[#A8A49C] hover:text-[#EEECEA]">
          ← 撰寫第一個技能
        </Link>
        <Link to="/docs/frontmatter" className="text-[#A8A49C] hover:text-[#EEECEA]">
          Frontmatter 欄位 →
        </Link>
      </nav>
    </DocsLayout>
  )
}

function H2({ children }: { children: React.ReactNode }) {
  return <h2 className="mt-10 text-[22px] font-semibold tracking-tight text-[#EEECEA]">{children}</h2>
}
function P({ children }: { children: React.ReactNode }) {
  return <p className="mt-3 text-[16px] leading-relaxed text-[#A8A49C]">{children}</p>
}
function Callout({ children }: { children: React.ReactNode }) {
  return (
    <div
      className="mt-4 rounded-md border p-3 text-[16px] leading-relaxed text-[#A8A49C]"
      style={{ backgroundColor: 'rgba(127,119,221,0.08)', borderColor: 'rgba(127,119,221,0.20)' }}
    >
      {children}
    </div>
  )
}
