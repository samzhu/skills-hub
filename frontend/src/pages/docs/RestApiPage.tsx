import { Link } from 'react-router'
import { DocsLayout } from '@/components/DocsLayout'

/**
 * S098f3 — `/docs/rest-api` REST 參考。
 *
 * Stub 引到 OpenAPI Swagger UI（已 ship 於 SpringDoc）；同時列出主要端點供 quick reference。
 */
export function RestApiPage() {
  return (
    <DocsLayout>
      <p className="mb-1 text-[12px] text-[#A8A49C]">
        文件 <span className="mx-1 text-[#5E5B55]">/</span>
        API 與 Webhook <span className="mx-1 text-[#5E5B55]">/</span>
        <span className="text-[#EEECEA]">REST 參考</span>
      </p>
      <h1 className="text-[28px] font-semibold tracking-tight text-[#EEECEA]">REST 參考</h1>
      <p className="mt-3 text-[15px] leading-relaxed text-[#A8A49C]">
        Skills Hub 提供 OpenAPI 3.0 spec 與 Swagger UI — 完整 schema 在
        <a href="/v3/api-docs" className="ml-1 text-[#C9C5F2] hover:underline">/v3/api-docs</a> (raw JSON) 或
        <a href="/swagger-ui.html" className="ml-1 text-[#C9C5F2] hover:underline">/swagger-ui.html</a>（互動 UI）。
        本頁是 quick reference。
      </p>

      <H2>主要端點</H2>
      <EndpointGroup
        title="技能瀏覽"
        rows={[
          { method: 'GET', path: '/api/v1/skills', note: '分頁列出所有 PUBLISHED；query: keyword / category / author / page / size' },
          { method: 'GET', path: '/api/v1/skills/{id}', note: 'by UUID 取單一 skill 完整 metadata' },
          { method: 'GET', path: '/api/v1/skills/{author}/{name}', note: '依 (author, name) canonical route 取 skill (per ADR-003)' },
          { method: 'GET', path: '/api/v1/skills/{id}/versions', note: '版本列表（publishedAt DESC）' },
          { method: 'GET', path: '/api/v1/skills/{id}/files', note: '檔案結構樹' },
          { method: 'GET', path: '/api/v1/skills/{id}/stats?period=', note: '近 7d/30d/90d 下載趨勢點陣' },
        ]}
      />
      <EndpointGroup
        title="技能發佈"
        rows={[
          { method: 'POST', path: '/api/v1/skills', note: 'multipart upload (file + metadata)；回 201 + 新 skill id' },
          { method: 'POST', path: '/api/v1/skills/{id}/versions', note: '新增版本' },
          { method: 'GET', path: '/api/v1/skills/{id}/versions/{ver}/download', note: 'zip 下載；Content-Disposition: attachment' },
        ]}
      />
      <EndpointGroup
        title="搜尋與聚合"
        rows={[
          { method: 'GET', path: '/api/v1/search/semantic?q=', note: '語意搜尋（k=20 default）' },
          { method: 'GET', path: '/api/v1/categories', note: '分類列表（含 count）' },
          { method: 'GET', path: '/api/v1/stats', note: '全站統計（totalSkills / downloads30d / autoPublishPct / activePublishers）' },
        ]}
      />

      <Callout>
        <strong className="text-[#EEECEA]">認證：</strong> MVP 階段 Spring Security permitAll；正式環境啟用後將以 OAuth2 bearer token 介接。
      </Callout>

      <nav className="mt-10 flex items-center justify-between border-t border-[rgba(255,255,255,0.06)] pt-5 text-[12px]">
        <Link to="/docs/semantic-search" className="text-[#A8A49C] hover:text-[#EEECEA]">← 語意搜尋</Link>
        <Link to="/docs/event-payload" className="text-[#A8A49C] hover:text-[#EEECEA]">Event payload →</Link>
      </nav>
    </DocsLayout>
  )
}

function H2({ children }: { children: React.ReactNode }) {
  return <h2 className="mt-10 text-[18px] font-semibold tracking-tight text-[#EEECEA]">{children}</h2>
}

function EndpointGroup({ title, rows }: { title: string; rows: Array<{ method: string; path: string; note: string }> }) {
  return (
    <div className="mt-4">
      <h3 className="text-[14px] font-medium text-[#EEECEA]">{title}</h3>
      <div className="mt-2 overflow-hidden rounded-md border border-[rgba(255,255,255,0.06)]">
        <table className="w-full text-[13px]">
          <tbody>
            {rows.map((r, i) => (
              <tr key={i} className={i > 0 ? 'border-t border-[rgba(255,255,255,0.06)]' : ''}>
                <td className="px-3 py-2 align-top">
                  <span className={`rounded px-1.5 py-0.5 font-mono text-[10.5px] font-semibold ${r.method === 'GET' ? 'bg-[rgba(55,138,221,0.14)] text-[#B0D5F2]' : 'bg-[rgba(29,158,117,0.14)] text-[#6FD8B0]'}`}>{r.method}</span>
                </td>
                <td className="px-3 py-2 align-top font-mono text-[12px] text-[#EEECEA]">{r.path}</td>
                <td className="px-3 py-2 align-top text-[12.5px] leading-relaxed text-[#A8A49C]">{r.note}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function Callout({ children }: { children: React.ReactNode }) {
  return (
    <div className="mt-4 rounded-md border p-3 text-[13px] leading-relaxed text-[#A8A49C]" style={{ backgroundColor: 'rgba(239,159,39,0.08)', borderColor: 'rgba(239,159,39,0.20)' }}>
      {children}
    </div>
  )
}
