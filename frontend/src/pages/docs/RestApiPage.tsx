import { Link } from 'react-router'
import { DocsLayout } from '@/components/DocsLayout'

/**
 * S098f3 → S099a — `/docs/rest-api` REST 參考。
 *
 * Cross-checked 2026-05-02：對齊實際 backend controllers (SkillCommandController /
 * SkillQueryController / SkillAclController / FlagController / SearchController /
 * SearchIntentController / AnalyticsController / FileBrowserController /
 * NotificationController / CollectionController / RequestController / MeController)。
 *
 * Stub-state endpoints 標 ⏸；其他皆已實作。
 */
export function RestApiPage() {
  return (
    <DocsLayout>
      <p className="mb-1 text-[14px] text-[#A8A49C]">
        文件 <span className="mx-1 text-[#5E5B55]">/</span>
        API 與 Webhook <span className="mx-1 text-[#5E5B55]">/</span>
        <span className="text-[#EEECEA]">REST 參考</span>
      </p>
      <h1 className="text-[28px] font-semibold tracking-tight text-[#EEECEA]">REST 參考</h1>
      <p className="mt-3 text-[16px] leading-relaxed text-[#A8A49C]">
        Skills Hub 提供 OpenAPI 3.1 spec 與 Swagger UI（基於 SpringDoc 3.x；
        <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[14px] text-[#EEECEA]">version: openapi_3_1</code> 設定）— local profile 下完整 schema 在
        <a href="/v3/api-docs" className="ml-1 text-[#C9C5F2] hover:underline">/v3/api-docs</a>（raw JSON）或
        <a href="/swagger-ui.html" className="ml-1 text-[#C9C5F2] hover:underline">/swagger-ui.html</a>（互動 UI）。
        Production profile (gcp) 預設 disabled — public spec endpoint 不該暴露。
        本頁是 quick reference，依 controller 群分組。
      </p>

      <H2>Skills 瀏覽（SkillQueryController）</H2>
      <EndpointGroup
        rows={[
          { method: 'GET', path: '/api/v1/skills', note: '分頁列出 PUBLISHED；query: keyword / category / author / page / size' },
          { method: 'GET', path: '/api/v1/skills/{id}', note: '依 UUID 取單一 skill' },
          { method: 'GET', path: '/api/v1/skills/{author}/{name}', note: 'canonical route per ADR-003' },
          { method: 'GET', path: '/api/v1/skills/{id}/versions', note: '版本列表（publishedAt DESC）' },
          { method: 'GET', path: '/api/v1/skills/{id}/download', note: '下載最新版本 zip（Content-Disposition: attachment）' },
          { method: 'GET', path: '/api/v1/skills/{id}/versions/{version}/download', note: '下載特定版本 zip' },
          { method: 'GET', path: '/api/v1/categories', note: '分類列表 + count' },
        ]}
      />

      <H2>Skills 發佈（SkillCommandController）</H2>
      <EndpointGroup
        rows={[
          { method: 'POST', path: '/api/v1/skills', note: 'JSON body 建立 skill（測試/seed 用）；正式發佈用 /upload' },
          { method: 'POST', path: '/api/v1/skills/upload', note: 'multipart：file + version + author + category；回 201 + new id' },
          { method: 'PUT', path: '/api/v1/skills/{id}/versions', note: '為既有 skill 新增版本（multipart：file + version）；@PreAuthorize write' },
          { method: 'POST', path: '/api/v1/skills/{id}/suspend', note: 'admin 停用 skill；@PreAuthorize suspend；body: { reason }' },
          { method: 'POST', path: '/api/v1/skills/{id}/reactivate', note: 'admin 恢復 SUSPENDED skill 為 PUBLISHED；@PreAuthorize reactivate' },
        ]}
      />

      <H2>Skills 檔案瀏覽（FileBrowserController）</H2>
      <EndpointGroup
        rows={[
          { method: 'GET', path: '/api/v1/skills/{id}/files', note: '檔案結構樹（path / size / type）' },
          { method: 'GET', path: '/api/v1/skills/{id}/files/{*path}', note: '取單一檔案內容（text plain-text / binary blob）' },
        ]}
      />

      <H2>Skills ACL（SkillAclController）— Row-level 權限</H2>
      <EndpointGroup
        rows={[
          { method: 'GET', path: '/api/v1/skills/{id}/acl', note: '查詢該 skill 的 acl_entries 清單' },
          { method: 'POST', path: '/api/v1/skills/{id}/acl', note: '加 acl_entry（user/role/group + permission）' },
          { method: 'DELETE', path: '/api/v1/skills/{id}/acl', note: '移除 acl_entry' },
        ]}
      />

      <H2>搜尋（SearchController + SearchIntentController）</H2>
      <EndpointGroup
        rows={[
          { method: 'GET', path: '/api/v1/search/semantic', note: '語意搜尋；query: q / k (default 20)' },
          { method: 'POST', path: '/api/v1/search/intent', note: 'S094b：query 解析意圖摘要 + 概念 chips；body: { query }' },
        ]}
      />

      <H2>Analytics</H2>
      <EndpointGroup
        rows={[
          { method: 'GET', path: '/api/v1/stats', note: 'S096e1：landing 公開統計（totalSkills / downloads30d / autoPublishPct / activePublishers）' },
          { method: 'GET', path: '/api/v1/analytics/overview', note: 'admin/作者 dashboard 全站總覽' },
          { method: 'GET', path: '/api/v1/skills/{id}/stats', note: 'S096d3：單 skill 下載趨勢；query: period=7d|30d|90d' },
        ]}
      />

      <H2>Flags（security/FlagController）</H2>
      <EndpointGroup
        rows={[
          { method: 'POST', path: '/api/v1/skills/{skillId}/flags', note: '使用者回報 skill 問題；body: { reason, severity }' },
          { method: 'GET', path: '/api/v1/skills/{skillId}/flags', note: '列出該 skill 的 flag 紀錄（reviewer 用）' },
        ]}
      />

      <H2>Notifications（NotificationController）— S096h1 stub</H2>
      <EndpointGroup
        rows={[
          { method: 'GET', path: '/api/v1/notifications', note: '⏸ stub returns []；real projection 待 S096h2' },
          { method: 'GET', path: '/api/v1/notifications/unread-count', note: '⏸ stub returns { count: 0 }；30s polled by AppShell bell badge' },
        ]}
      />

      <H2>Collections（CollectionController）— S096f1 stub</H2>
      <EndpointGroup
        rows={[
          { method: 'GET', path: '/api/v1/collections', note: '⏸ stub returns []；aggregate + 3 endpoints (POST create / install / 單個 GET) 待 S096f2' },
        ]}
      />

      <H2>Requests（RequestController）— S096g1 stub</H2>
      <EndpointGroup
        rows={[
          { method: 'GET', path: '/api/v1/requests', note: '⏸ stub returns []；aggregate + 投票/認領 endpoints 待 S096g2' },
        ]}
      />

      <H2>Identity（MeController）</H2>
      <EndpointGroup
        rows={[
          { method: 'GET', path: '/api/v1/me', note: '當前認證 user 資訊（OAuth subject + groups + roles）；MVP permitAll mock' },
        ]}
      />

      <Callout>
        <strong className="text-[#EEECEA]">認證：</strong> MVP 階段 Spring Security permitAll；正式環境啟用後將以 OAuth2 bearer token 介接。Row-level ACL 透過{' '}
        <code className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[14px] text-[#EEECEA]">@PreAuthorize</code>
        + DelegatingPermissionEvaluator 在 controller 層執行。
      </Callout>

      <nav className="mt-10 flex items-center justify-between border-t border-[rgba(255,255,255,0.06)] pt-5 text-[14px]">
        <Link to="/docs/semantic-search" className="text-[#A8A49C] hover:text-[#EEECEA]">← 語意搜尋</Link>
        <Link to="/docs/event-payload" className="text-[#A8A49C] hover:text-[#EEECEA]">Event payload →</Link>
      </nav>
    </DocsLayout>
  )
}

function H2({ children }: { children: React.ReactNode }) {
  return <h2 className="mt-10 text-[20px] font-semibold tracking-tight text-[#EEECEA]">{children}</h2>
}

function EndpointGroup({ rows }: { rows: Array<{ method: string; path: string; note: string }> }) {
  return (
    <div className="mt-3 overflow-hidden rounded-md border border-[rgba(255,255,255,0.06)]">
      <table className="w-full text-[14px]">
        <tbody>
          {rows.map((r, i) => {
            const colorMap: Record<string, string> = {
              GET: 'bg-[rgba(55,138,221,0.14)] text-[#B0D5F2]',
              POST: 'bg-[rgba(29,158,117,0.14)] text-[#6FD8B0]',
              PUT: 'bg-[rgba(239,159,39,0.14)] text-[#FAC775]',
              DELETE: 'bg-[rgba(226,75,74,0.14)] text-[#F2A6A6]',
              PATCH: 'bg-[rgba(127,119,221,0.14)] text-[#C9C5F2]',
            }
            const cls = colorMap[r.method] ?? 'bg-secondary text-foreground'
            return (
              <tr key={i} className={i > 0 ? 'border-t border-[rgba(255,255,255,0.06)]' : ''}>
                <td className="px-3 py-2 align-top">
                  <span className={`rounded px-1.5 py-0.5 font-mono text-[10.5px] font-semibold ${cls}`}>{r.method}</span>
                </td>
                <td className="px-3 py-2 align-top font-mono text-[14px] text-[#EEECEA]">{r.path}</td>
                <td className="px-3 py-2 align-top text-[12.5px] leading-relaxed text-[#A8A49C]">{r.note}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function Callout({ children }: { children: React.ReactNode }) {
  return (
    <div className="mt-4 rounded-md border p-3 text-[14px] leading-relaxed text-[#A8A49C]" style={{ backgroundColor: 'rgba(239,159,39,0.08)', borderColor: 'rgba(239,159,39,0.20)' }}>
      {children}
    </div>
  )
}
