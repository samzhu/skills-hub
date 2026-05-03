import { Link } from 'react-router'
import { DocsLayout } from '@/components/DocsLayout'

/**
 * S098f3 — `/docs/semantic-search` 語意搜尋運作原理。
 */
export function SemanticSearchPage() {
  return (
    <DocsLayout>
      <p className="mb-1 text-[14px] text-[#A8A49C]">
        文件 <span className="mx-1 text-[#5E5B55]">/</span>
        發佈 <span className="mx-1 text-[#5E5B55]">/</span>
        <span className="text-[#EEECEA]">語意搜尋</span>
      </p>
      <h1 className="text-[28px] font-semibold tracking-tight text-[#EEECEA]">語意搜尋</h1>
      <p className="mt-3 text-[16px] leading-relaxed text-[#A8A49C]">
        Skills Hub 用 Gemini text-embedding 把每個 skill 的 <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[14px] text-[#EEECEA]">description</code>
        欄位轉成向量，存到 PostgreSQL pgvector。User 用自然語言查詢時也轉同樣
        embedding，cosine similarity 排序回傳最相關的技能。
      </p>

      <H2>什麼樣的 description 容易被找到</H2>
      <p className="mt-3 text-[14px] leading-relaxed text-[#A8A49C]">
        Embedding 認得「具體動詞 + 領域名詞 + trigger 條件」；忽略行銷形容詞。
        詳細寫法見 <Link to="/docs/your-first-skill" className="text-[#C9C5F2] hover:underline">撰寫第一個技能</Link>{' '}
        的「Writing a description that works」段。
      </p>

      <H2>查詢流程</H2>
      <ol className="mt-2 list-decimal space-y-1 pl-5 text-[14px] text-[#A8A49C]">
        <li>User 在搜尋列輸入自然語言（如「我要轉換 ISO 日期到 epoch」）</li>
        <li>後端 <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[14px] text-[#EEECEA]">SemanticSearchService</code> 將 query embed 成 768 維向量</li>
        <li>pgvector <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[14px] text-[#EEECEA]">{`<#>`}</code> operator 計算 cosine similarity（轉 0–1 score）</li>
        <li>取 top-k（default k=20）回傳 + 相符度 % 顯示在 SkillCard</li>
      </ol>

      <H2>Fallback 行為</H2>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-[14px] text-[#A8A49C]">
        <li>Gemini API 不可用 → fallback 回 keyword 模式（regex match name + description）</li>
        <li>0 結果（threshold 太低）→ HomePage 顯 EmptyState redirect tone，建議改用 keyword 或調整描述</li>
        <li>Skill 剛 publish 未完成 indexing → 排序權重較低（embedding sync 是 async）</li>
      </ul>

      <Callout>
        Embedding 模型版本：Gemini <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[14px] text-[#EEECEA]">text-embedding-004</code>。
        若上游 API 升版，舊 embeddings 會逐步 re-embed（背景 job）。
      </Callout>

      <nav className="mt-10 flex items-center justify-between border-t border-[rgba(255,255,255,0.06)] pt-5 text-[14px]">
        <Link to="/docs/versioning" className="text-[#A8A49C] hover:text-[#EEECEA]">← 版本管理</Link>
        <Link to="/docs/rest-api" className="text-[#A8A49C] hover:text-[#EEECEA]">REST 參考 →</Link>
      </nav>
    </DocsLayout>
  )
}

function H2({ children }: { children: React.ReactNode }) {
  return <h2 className="mt-10 text-[20px] font-semibold tracking-tight text-[#EEECEA]">{children}</h2>
}
function Callout({ children }: { children: React.ReactNode }) {
  return (
    <div className="mt-4 rounded-md border p-3 text-[14px] leading-relaxed text-[#A8A49C]" style={{ backgroundColor: 'rgba(127,119,221,0.08)', borderColor: 'rgba(127,119,221,0.20)' }}>
      {children}
    </div>
  )
}
