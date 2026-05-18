import { Link } from 'react-router'
import { DocsLayout } from '@/components/DocsLayout'

/**
 * S098f2 — `/docs/frontmatter` Frontmatter 欄位完整對照表。
 *
 * 給作者要查「這個欄位是必填還選填、限制是什麼」時的 reference。
 * 不教使用範例（YourFirstSkillPage 已 cover），只列規格表。
 */
export function FrontmatterPage() {
  return (
    <DocsLayout>
      <p className="mb-1 text-[16px] text-[#A8A49C]">
        文件 <span className="mx-1 text-[#5E5B55]">/</span>
        參考 <span className="mx-1 text-[#5E5B55]">/</span>
        <span className="text-[#EEECEA]">Frontmatter 欄位</span>
      </p>
      <h1 className="text-[30px] font-semibold tracking-tight text-[#EEECEA]">Frontmatter 欄位</h1>
      <p className="mt-3 text-[18px] leading-relaxed text-[#A8A49C]">
        SKILL.md 開頭的 YAML 區塊。本表列出 agentskills.io v1.2 規範的所有欄位
        + Skills Hub 在地化解釋。必填 2 個，其餘皆 optional。
      </p>

      <H2>必填</H2>
      <FieldTable
        rows={[
          {
            name: 'name',
            type: 'string',
            limit: '≤ 64 字元、[a-z0-9-]',
            note: '同一 org 內 unique；同名再 publish 視為 bump 版本，不建新 skill',
          },
          {
            name: 'description',
            type: 'string',
            limit: '≤ 1024 字元',
            note: '一段簡潔描述「skill 做什麼 + agent 何時該呼叫」；semantic search 對此欄位 embedding',
          },
        ]}
      />

      <H2>選填</H2>
      <FieldTable
        rows={[
          { name: 'version', type: 'string', limit: '選填 metadata', note: 'SKILL.md 自帶 metadata；平台發佈版本標籤在 /publish 另填，也可留白自動產生' },
          { name: 'author', type: 'string', limit: '—', note: '署名；可為個人 / team 名' },
          { name: 'license', type: 'string', limit: 'SPDX ID（如 MIT）', note: '建議填 — 顯示於 detail page' },
          { name: 'compatibility', type: 'array<string>', limit: '—', note: '宣告相容 agent（如 ["claude-code", "cursor"]）' },
          { name: 'metadata', type: 'string key/value', limit: '官方格式：value 皆為 string', note: '自訂 KV 對；agent 可讀但 Skills Hub 不額外處理' },
          { name: 'allowed-tools', type: 'string', limit: '官方格式：空白分隔字串', note: '宣告 skill 預期 agent 可使用的 tool；存在時即使 findings=[]，安全頁也會用 LOW 說明原因' },
        ]}
      />

      <Callout>
        Skills Hub 會接受常見非官方格式，例如
        <code className="mx-1 rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">allowed-tools</code>
        YAML list 或
        <code className="mx-1 rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">metadata.tags</code>
        array，但會標成 compatibility warning，並降低品質評分的
        <code className="ml-1 rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">VALIDATION</code>
        分數；滿分只保留給 agentskills.io 官方 frontmatter 格式。
      </Callout>

      <Callout>
        加了 <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">allowed-tools</code> 或
        <code className="ml-1 rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">scripts/</code> 資料夾就會觸發風險掃描；
        如果 scanner 沒找到 issue code，安全頁仍會說明「因為這個技能可以要求 AI 使用哪些工具」或「因為 package 包含 scripts/」。
        詳細層級規則見{' '}
        <Link to="/docs/risk-tiers" className="text-[#C9C5F2] hover:underline">風險層級</Link>。
      </Callout>

      <nav className="mt-10 flex items-center justify-between border-t border-[rgba(255,255,255,0.06)] pt-5 text-[16px]">
        <Link to="/docs/skill-md-spec" className="text-[#A8A49C] hover:text-[#EEECEA]">
          ← SKILL.md 規範
        </Link>
        <Link to="/docs/bundle" className="text-[#A8A49C] hover:text-[#EEECEA]">
          Bundle 結構 →
        </Link>
      </nav>
    </DocsLayout>
  )
}

function H2({ children }: { children: React.ReactNode }) {
  return <h2 className="mt-10 text-[22px] font-semibold tracking-tight text-[#EEECEA]">{children}</h2>
}

function FieldTable({
  rows,
}: {
  rows: Array<{ name: string; type: string; limit: string; note: string }>
}) {
  return (
    <div className="mt-3 overflow-hidden rounded-md border border-[rgba(255,255,255,0.06)]">
      <table className="w-full text-[16px]">
        <thead className="bg-[#0F0F12] text-[11px] uppercase tracking-wider text-[#A8A49C]">
          <tr>
            <th className="px-3 py-2 text-left font-semibold">欄位</th>
            <th className="px-3 py-2 text-left font-semibold">型別</th>
            <th className="px-3 py-2 text-left font-semibold">限制</th>
            <th className="px-3 py-2 text-left font-semibold">說明</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.name} className="border-t border-[rgba(255,255,255,0.06)] align-top">
              <td className="px-3 py-2 font-mono text-[12.5px] text-[#EEECEA]">{r.name}</td>
              <td className="px-3 py-2 font-mono text-[16px] text-[#A8A49C]">{r.type}</td>
              <td className="px-3 py-2 text-[16px] text-[#A8A49C]">{r.limit}</td>
              <td className="px-3 py-2 leading-relaxed text-[#A8A49C]">{r.note}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Callout({ children }: { children: React.ReactNode }) {
  return (
    <div
      className="mt-4 rounded-md border p-3 text-[16px] leading-relaxed text-[#A8A49C]"
      style={{ backgroundColor: 'rgba(239,159,39,0.08)', borderColor: 'rgba(239,159,39,0.20)' }}
    >
      {children}
    </div>
  )
}
