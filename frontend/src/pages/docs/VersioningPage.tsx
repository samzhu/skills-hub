import { Link } from 'react-router'
import { DocsLayout } from '@/components/DocsLayout'

/**
 * S098f3 — `/docs/versioning` 版本管理。
 */
export function VersioningPage() {
  return (
    <DocsLayout>
      <p className="mb-1 text-[16px] text-[#A8A49C]">
        文件 <span className="mx-1 text-[#5E5B55]">/</span>
        發佈 <span className="mx-1 text-[#5E5B55]">/</span>
        <span className="text-[#EEECEA]">版本管理</span>
      </p>
      <h1 className="text-[30px] font-semibold tracking-tight text-[#EEECEA]">版本管理</h1>
      <p className="mt-3 text-[18px] leading-relaxed text-[#A8A49C]">
        Skills Hub 使用版本標籤管理每次發佈。上傳時版本號可留白；首版留白會建立
        <code className="mx-1 rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">1</code>，
        後續留白會建立同一 skill 內下一個純數字流水號。舊版本保留可下載，作者可在技能詳情頁的「版本歷史」
        tab 看完整版本列表，並用「比較版本變化」進入 diff 頁。
      </p>

      <H2>版本標籤規則</H2>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-[16px] text-[#A8A49C]">
        <li>可留白；系統會自動建立 <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">1</code>、<code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">2</code>、<code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">3</code> 這類流水號</li>
        <li>也可自訂，例如 <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">2026.05-hotfix</code>、<code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">release-1</code></li>
        <li>自訂標籤只能用英文字母、數字、點、底線、連字號，最多 20 字元；同一 skill 不能重用既有標籤</li>
      </ul>

      <H2>升版時機</H2>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-[16px] text-[#A8A49C]">
        <li><strong className="text-[#EEECEA]">小幅修改</strong>：description 微調、範例補充、typo、非 breaking 的 prompt 加強</li>
        <li><strong className="text-[#EEECEA]">功能新增</strong>：加 optional 欄位、新功能但向後相容、scripts/ 加新工具但既有皆可用</li>
        <li><strong className="text-[#EEECEA]">破壞性修改</strong>：rename、移除既有 scripts、改 description 致 agent invocation logic 變化；建議自訂清楚標籤</li>
      </ul>

      <Callout>
        舊版本不會被刪除 — consumer 可指定版本下載：
        <code className="ml-1 rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">/api/v1/skills/{'{id}'}/versions/1/download</code>
      </Callout>

      <H2>停用 / 下架</H2>
      <p className="mt-3 text-[16px] leading-relaxed text-[#A8A49C]">
        若整個 skill 需 emergency 下架（嚴重 bug / 違規），admin 可將 skill 設為
        <code className="mx-1 rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">SUSPENDED</code>。
        此時下載端點停用，但版本記錄保留供 audit；未來若 reactivate 從停用前狀態恢復。
      </p>

      <nav className="mt-10 flex items-center justify-between border-t border-[rgba(255,255,255,0.06)] pt-5 text-[16px]">
        <Link to="/docs/upload-validate" className="text-[#A8A49C] hover:text-[#EEECEA]">← 上傳與驗證</Link>
        <Link to="/docs/semantic-search" className="text-[#A8A49C] hover:text-[#EEECEA]">語意搜尋 →</Link>
      </nav>
    </DocsLayout>
  )
}

function H2({ children }: { children: React.ReactNode }) {
  return <h2 className="mt-10 text-[22px] font-semibold tracking-tight text-[#EEECEA]">{children}</h2>
}
function Callout({ children }: { children: React.ReactNode }) {
  return (
    <div className="mt-4 rounded-md border p-3 text-[16px] leading-relaxed text-[#A8A49C]" style={{ backgroundColor: 'rgba(127,119,221,0.08)', borderColor: 'rgba(127,119,221,0.20)' }}>
      {children}
    </div>
  )
}
