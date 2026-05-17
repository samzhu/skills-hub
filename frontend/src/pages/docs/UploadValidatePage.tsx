import { Link } from 'react-router'
import { DocsLayout } from '@/components/DocsLayout'

/**
 * S098f3 — `/docs/upload-validate` 上傳與驗證流程說明。
 */
export function UploadValidatePage() {
  return (
    <DocsLayout>
      <p className="mb-1 text-[16px] text-[#A8A49C]">
        文件 <span className="mx-1 text-[#5E5B55]">/</span>
        發佈 <span className="mx-1 text-[#5E5B55]">/</span>
        <span className="text-[#EEECEA]">上傳與驗證</span>
      </p>
      <h1 className="text-[30px] font-semibold tracking-tight text-[#EEECEA]">上傳與驗證</h1>
      <p className="mt-3 text-[18px] leading-relaxed text-[#A8A49C]">
        Skills Hub 的發佈流程分四步：<strong className="text-[#EEECEA]">上傳 → 驗證 → 審視 → 上架</strong>。
        作者只負責第 1 步，其餘三步系統自動完成；風險等級決定上架還是進入人工審核。
      </p>

      <H2>四步流程</H2>
      <Step n={1} title="上傳 zip 套件" body="作者在 /publish 拖放 bundle zip + 填寫分類；版本標籤可留白讓系統自動產生。檔案大小 ≤ 5MB；解壓檔案數 ≤ 50。" />
      <Step n={2} title="驗證 frontmatter" body="系統解析 SKILL.md frontmatter，檢查 name / description 必填欄位 + 格式（lowercase-hyphen / 字數限制）。失敗即進「上傳失敗」頁，作者修檔重傳。" />
      <Step n={3} title="風險掃描" body="掃描器會看 SKILL.md、scripts/、allowed-tools 與其他文字檔。findings=[] 且沒有 scripts/、沒有 allowed-tools → NONE；findings=[] 但有 scripts/ 或 allowed-tools → LOW，安全頁會顯示原因。" />
      <Step n={4} title="上架或審核" body="LOW/NONE/MEDIUM 自動上架（MEDIUM 附警告標）；HIGH 進審核佇列等 reviewer 決定。" />

      <H2>常見錯誤</H2>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-[16px] text-[#A8A49C]">
        <li>frontmatter 缺 <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">name</code> 或 <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">description</code></li>
        <li>name 含大寫或特殊字元（規範要求 <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">[a-z0-9-]</code>）</li>
        <li>檔案大小或數量超限 → reject 在 zip 解析階段</li>
        <li>frontmatter YAML 解析錯誤（縮排 / 引號）</li>
      </ul>

      <nav className="mt-10 flex items-center justify-between border-t border-[rgba(255,255,255,0.06)] pt-5 text-[16px]">
        <Link to="/docs/risk-tiers" className="text-[#A8A49C] hover:text-[#EEECEA]">← 風險層級</Link>
        <Link to="/docs/versioning" className="text-[#A8A49C] hover:text-[#EEECEA]">版本管理 →</Link>
      </nav>
    </DocsLayout>
  )
}

function H2({ children }: { children: React.ReactNode }) {
  return <h2 className="mt-10 text-[22px] font-semibold tracking-tight text-[#EEECEA]">{children}</h2>
}
function Step({ n, title, body }: { n: number; title: string; body: string }) {
  return (
    <div className="mt-3 flex gap-3 rounded-md border border-[rgba(255,255,255,0.06)] bg-[#0F0F12] p-3">
      <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-foreground text-[16px] font-medium text-background">{n}</div>
      <div>
        <p className="text-[16px] font-medium text-[#EEECEA]">{title}</p>
        <p className="mt-1 text-[16px] leading-relaxed text-[#A8A49C]">{body}</p>
      </div>
    </div>
  )
}
