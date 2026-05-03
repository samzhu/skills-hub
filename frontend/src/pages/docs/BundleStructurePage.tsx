import { Link } from 'react-router'
import { DocsLayout } from '@/components/DocsLayout'

/**
 * S098f2 — `/docs/bundle` Bundle 結構說明。
 *
 * 解釋 SKILL.md 之外三個慣例 optional 資料夾的用途與對掃描器的影響。
 */
export function BundleStructurePage() {
  return (
    <DocsLayout>
      <p className="mb-1 text-[14px] text-[#A8A49C]">
        文件 <span className="mx-1 text-[#5E5B55]">/</span>
        參考 <span className="mx-1 text-[#5E5B55]">/</span>
        <span className="text-[#EEECEA]">Bundle 結構</span>
      </p>
      <h1 className="text-[28px] font-semibold tracking-tight text-[#EEECEA]">Bundle 結構</h1>
      <p className="mt-3 text-[16px] leading-relaxed text-[#A8A49C]">
        Skills Hub 的 bundle 是 zip 套件 — 解壓後預設按以下結構放檔。除
        <code className="mx-1 rounded bg-[#171719] px-1 py-0.5 font-mono text-[14px] text-[#EEECEA]">SKILL.md</code>
        必填外，其他資料夾全 optional。
      </p>

      <H2>慣例佈局</H2>
      <pre className="mt-3 overflow-x-auto rounded-md border border-[rgba(255,255,255,0.06)] bg-[#0F0F12] p-4 font-mono text-[12.5px] leading-relaxed text-[#EEECEA]">
{`my-skill/
├── SKILL.md          [required]   # 主文件（YAML frontmatter + markdown 內文）
├── scripts/          [optional]   # 可執行 shell / python 腳本
│   └── normalize.sh                 # ⚠ 存在會觸發風險掃描
├── references/       [optional]   # 純文件補充（規範、API spec、範例）
│   └── iso-8601.md
└── assets/           [optional]   # 靜態資源（範例輸入、JSON schema）
    └── examples.yaml`}
      </pre>

      <H2>各資料夾語意</H2>

      <FolderRow
        name="scripts/"
        emphasis="會觸發風險掃描"
        body="放可執行檔案 — agent 在執行 skill 過程中可呼叫。掃描器看到任何 shell / batch / python 檔即啟動 risk scan，最終層級依內容判斷（pure utility = LOW；含 dangerous patterns = HIGH）。"
      />
      <FolderRow
        name="references/"
        body="純 markdown / 文字檔 — agent 不執行只 reference。常見：標準規範、API spec、範例 prompt 模板。對風險掃描中性。"
      />
      <FolderRow
        name="assets/"
        body="靜態資源 — JSON schema、範例輸入、設定範本等。同 references/ 對風險掃描中性。"
      />

      <H2>限制</H2>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-[14px] text-[#A8A49C]">
        <li>整 bundle zip ≤ 5MB</li>
        <li>單檔 ≤ 1MB（避免大 binary）</li>
        <li>檔案數 ≤ 50</li>
        <li>禁止符號連結 / hidden files (.DS_Store / .git/) — Skills Hub 上傳會 reject</li>
      </ul>

      <Callout>
        <strong className="text-[#EEECEA]">想了解掃描層級判定？</strong> 詳見{' '}
        <Link to="/docs/risk-tiers" className="text-[#C9C5F2] hover:underline">風險層級</Link>。
      </Callout>

      <nav className="mt-10 flex items-center justify-between border-t border-[rgba(255,255,255,0.06)] pt-5 text-[14px]">
        <Link to="/docs/frontmatter" className="text-[#A8A49C] hover:text-[#EEECEA]">
          ← Frontmatter 欄位
        </Link>
        <Link to="/docs/risk-tiers" className="text-[#A8A49C] hover:text-[#EEECEA]">
          風險層級 →
        </Link>
      </nav>
    </DocsLayout>
  )
}

function H2({ children }: { children: React.ReactNode }) {
  return <h2 className="mt-10 text-[20px] font-semibold tracking-tight text-[#EEECEA]">{children}</h2>
}

function FolderRow({
  name,
  emphasis,
  body,
}: {
  name: string
  emphasis?: string
  body: string
}) {
  return (
    <div className="mt-3 rounded-md border border-[rgba(255,255,255,0.06)] bg-[#0F0F12] p-3">
      <div className="flex items-center gap-2">
        <code className="rounded bg-[#171719] px-2 py-0.5 font-mono text-[12.5px] text-[#EEECEA]">{name}</code>
        {emphasis && (
          <span
            className="rounded-full px-2 py-0.5 text-[10px] font-medium"
            style={{ backgroundColor: 'rgba(239,159,39,0.14)', color: '#FAC775' }}
          >
            {emphasis}
          </span>
        )}
      </div>
      <p className="mt-2 text-[14px] leading-relaxed text-[#A8A49C]">{body}</p>
    </div>
  )
}

function Callout({ children }: { children: React.ReactNode }) {
  return (
    <div
      className="mt-4 rounded-md border p-3 text-[14px] leading-relaxed text-[#A8A49C]"
      style={{ backgroundColor: 'rgba(127,119,221,0.08)', borderColor: 'rgba(127,119,221,0.20)' }}
    >
      {children}
    </div>
  )
}
