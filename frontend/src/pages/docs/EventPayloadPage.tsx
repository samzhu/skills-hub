import { Link } from 'react-router'
import { DocsLayout } from '@/components/DocsLayout'

/**
 * S098f3 — `/docs/event-payload` Domain event payload 參考。
 *
 * 對齊 backend `domain_events` table + ADR-002 outbox pattern。
 */
export function EventPayloadPage() {
  return (
    <DocsLayout>
      <p className="mb-1 text-[16px] text-[#A8A49C]">
        文件 <span className="mx-1 text-[#5E5B55]">/</span>
        API 與 Webhook <span className="mx-1 text-[#5E5B55]">/</span>
        <span className="text-[#EEECEA]">Event payload</span>
      </p>
      <h1 className="text-[30px] font-semibold tracking-tight text-[#EEECEA]">Event payload</h1>
      <p className="mt-3 text-[18px] leading-relaxed text-[#A8A49C]">
        Skills Hub 採 Spring Modulith Outbox pattern — 所有 domain mutation 寫
        <code className="mx-1 rounded bg-[#171719] px-1 py-0.5 font-mono text-[16px] text-[#EEECEA]">domain_events</code> 表（JSONB payload + aggregate_id + sequence
        嚴格遞增）。本頁列出 publisher 主要 event 結構供日後 webhook 訂閱使用。
      </p>

      <H2>共用包裝</H2>
      <pre className="mt-3 overflow-x-auto rounded-md border border-[rgba(255,255,255,0.06)] bg-[#0F0F12] p-4 font-mono text-[16px] leading-relaxed text-[#EEECEA]">
{`{
  "eventId": "uuid",
  "eventType": "SkillPublished",
  "aggregateId": "skill-uuid",
  "sequence": 1,
  "occurredAt": "2026-05-02T08:00:00Z",
  "payload": { /* per event */ }
}`}
      </pre>

      <H2>主要 event 類型</H2>

      <Event
        type="SkillPublished"
        body="新 skill 第一次成功 publish"
        payload={`{ "skillId": "uuid", "name": "...", "author": "...", "version": "1.0.0", "riskLevel": "LOW" }`}
      />
      <Event
        type="VersionPublished"
        body="既有 skill 新增版本（不論首版或後續）"
        payload={`{ "skillId": "uuid", "version": "1.1.0", "fileSize": 84321, "previousVersion": "1.0.0" }`}
      />
      <Event
        type="SkillRiskAssessed"
        body="風險掃描完成，riskLevel 寫入 aggregate"
        payload={`{ "skillId": "uuid", "riskLevel": "MEDIUM", "findings": [{"rule": "...", "loc": "..."}] }`}
      />
      <Event
        type="SkillFlagged"
        body="使用者透過 flag 流程回報問題（S098e3 之後 ship）"
        payload={`{ "skillId": "uuid", "flaggerId": "...", "reason": "...", "severity": "warn" }`}
      />
      <Event
        type="SkillSuspended"
        body="Admin 設 SUSPENDED；下載端點停用"
        payload={`{ "skillId": "uuid", "by": "admin-id", "reason": "..." }`}
      />
      <Event
        type="SkillDownloaded"
        body="既有 download 計數事件；30d trend 由此 projection 而來"
        payload={`{ "skillId": "uuid", "version": "1.0.0", "downloaderId": "..." }`}
      />

      <Callout>
        <strong className="text-[#EEECEA]">Webhook 訂閱：</strong> 目前 read-only via API；webhook delivery
        endpoint 規劃中 — 屆時 publisher 可註冊 URL 收 push notification。
      </Callout>

      <nav className="mt-10 flex items-center justify-between border-t border-[rgba(255,255,255,0.06)] pt-5 text-[16px]">
        <Link to="/docs/rest-api" className="text-[#A8A49C] hover:text-[#EEECEA]">← REST 參考</Link>
        <span className="text-[#5E5B55]" title="已是最後一頁">完 ✓</span>
      </nav>
    </DocsLayout>
  )
}

function H2({ children }: { children: React.ReactNode }) {
  return <h2 className="mt-10 text-[22px] font-semibold tracking-tight text-[#EEECEA]">{children}</h2>
}

function Event({ type, body, payload }: { type: string; body: string; payload: string }) {
  return (
    <div className="mt-3 rounded-md border border-[rgba(255,255,255,0.06)] bg-[#0F0F12] p-3">
      <div className="flex items-center gap-2">
        <code className="rounded bg-[#171719] px-2 py-0.5 font-mono text-[12.5px] font-medium text-[#EEECEA]">{type}</code>
      </div>
      <p className="mt-1.5 text-[16px] leading-relaxed text-[#A8A49C]">{body}</p>
      <pre className="mt-2 overflow-x-auto rounded bg-[#08080A] p-2 font-mono text-[11.5px] leading-relaxed text-[#A8A49C]">{payload}</pre>
    </div>
  )
}

function Callout({ children }: { children: React.ReactNode }) {
  return (
    <div className="mt-4 rounded-md border p-3 text-[16px] leading-relaxed text-[#A8A49C]" style={{ backgroundColor: 'rgba(127,119,221,0.08)', borderColor: 'rgba(127,119,221,0.20)' }}>
      {children}
    </div>
  )
}
