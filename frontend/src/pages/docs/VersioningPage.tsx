import { Link } from 'react-router'
import { DocsLayout } from '@/components/DocsLayout'

/**
 * S098f3 — `/docs/versioning` 版本管理。
 */
export function VersioningPage() {
  return (
    <DocsLayout>
      <p className="mb-1 text-[12px] text-[#A8A49C]">
        文件 <span className="mx-1 text-[#5E5B55]">/</span>
        發佈 <span className="mx-1 text-[#5E5B55]">/</span>
        <span className="text-[#EEECEA]">版本管理</span>
      </p>
      <h1 className="text-[28px] font-semibold tracking-tight text-[#EEECEA]">版本管理</h1>
      <p className="mt-3 text-[15px] leading-relaxed text-[#A8A49C]">
        Skills Hub 採 SemVer 約定。每個 skill 有唯一 (org, name) — 同名重新發佈
        即新增版本而非建新 skill；舊版本保留可下載，作者可在技能詳情頁的「版本歷史」
        tab 看完整版本列表，並用「比較版本變化」進入 diff 頁。
      </p>

      <H2>版本號規則</H2>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-[14px] text-[#A8A49C]">
        <li>格式：<code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[12px] text-[#EEECEA]">MAJOR.MINOR.PATCH</code>（如 <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[12px] text-[#EEECEA]">1.0.0</code>），或附 pre-release tag（<code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[12px] text-[#EEECEA]">2.0.0-rc.1</code>）</li>
        <li>新版本號必須 strictly &gt; 既有最新版（避免時光倒流）</li>
        <li>同 skill 任意 commit 不可重用既有版本號 — 系統會 reject</li>
      </ul>

      <H2>升版時機</H2>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-[14px] text-[#A8A49C]">
        <li><strong className="text-[#EEECEA]">PATCH</strong>：description 微調 / 範例補充 / typo / 非 breaking 的 prompt 加強</li>
        <li><strong className="text-[#EEECEA]">MINOR</strong>：加 optional 欄位 / 新功能但向後相容 / scripts/ 加新工具但既有皆可用</li>
        <li><strong className="text-[#EEECEA]">MAJOR</strong>：rename / breaking 行為改變 / 移除既有 scripts / 改 description 致 agent invocation logic 變化</li>
      </ul>

      <Callout>
        舊版本不會被刪除 — consumer 可指定版本下載：
        <code className="ml-1 rounded bg-[#171719] px-1 py-0.5 font-mono text-[12px] text-[#EEECEA]">/api/v1/skills/{'{id}'}/versions/1.0.0/download</code>
      </Callout>

      <H2>停用 / 下架</H2>
      <p className="mt-3 text-[14px] leading-relaxed text-[#A8A49C]">
        若整個 skill 需 emergency 下架（嚴重 bug / 違規），admin 可將 skill 設為
        <code className="mx-1 rounded bg-[#171719] px-1 py-0.5 font-mono text-[12px] text-[#EEECEA]">SUSPENDED</code>。
        此時下載端點停用，但版本記錄保留供 audit；未來若 reactivate 從停用前狀態恢復。
      </p>

      <nav className="mt-10 flex items-center justify-between border-t border-[rgba(255,255,255,0.06)] pt-5 text-[12px]">
        <Link to="/docs/upload-validate" className="text-[#A8A49C] hover:text-[#EEECEA]">← 上傳與驗證</Link>
        <Link to="/docs/semantic-search" className="text-[#A8A49C] hover:text-[#EEECEA]">語意搜尋 →</Link>
      </nav>
    </DocsLayout>
  )
}

function H2({ children }: { children: React.ReactNode }) {
  return <h2 className="mt-10 text-[18px] font-semibold tracking-tight text-[#EEECEA]">{children}</h2>
}
function Callout({ children }: { children: React.ReactNode }) {
  return (
    <div className="mt-4 rounded-md border p-3 text-[13px] leading-relaxed text-[#A8A49C]" style={{ backgroundColor: 'rgba(127,119,221,0.08)', borderColor: 'rgba(127,119,221,0.20)' }}>
      {children}
    </div>
  )
}
