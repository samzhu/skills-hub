import { useState } from 'react'
import type { ReactNode } from 'react'
import { Download, FileText, Flag, Search } from 'lucide-react'
import type { SecurityFindingSummary, SecurityReport, SecurityRiskReason } from '@/api/security'
import type { RiskLevel } from '@/types/skill'

interface Props {
  /** undefined = loading；null = 404 SECURITY_NOT_SCANNED */
  report: SecurityReport | null | undefined
  riskLevel: RiskLevel | null | undefined
}

type SeverityFilter = 'ALL' | 'HIGH' | 'MEDIUM' | 'LOW'

function riskLevelLabel(riskLevel: RiskLevel | null | undefined): string {
  if (riskLevel === 'NONE') return '未發現風險'
  if (riskLevel === 'LOW') return '低風險'
  if (riskLevel === 'MEDIUM') return '中風險'
  if (riskLevel === 'HIGH') return '高風險'
  return '未評估'
}

function emptyFindingMessage(riskLevel: RiskLevel | null | undefined): string {
  if (riskLevel === 'NONE') return '未發現安全問題'
  return '沒有需要修改的掃描發現'
}

function severityCounts(findings: SecurityFindingSummary[]): { high: number; medium: number; low: number; total: number } {
  return findings.reduce(
    (counts, finding) => {
      if (finding.severity === 'HIGH') counts.high += 1
      if (finding.severity === 'MEDIUM') counts.medium += 1
      if (finding.severity === 'LOW') counts.low += 1
      counts.total += 1
      return counts
    },
    { high: 0, medium: 0, low: 0, total: 0 },
  )
}

function findingCode(finding: SecurityFindingSummary): string {
  return finding.issueCode ?? 'LEGACY'
}

function findingLocation(finding: SecurityFindingSummary): string {
  if (finding.filePath && finding.line != null) return `${finding.filePath}:${finding.line}`
  if (finding.filePath) return finding.filePath
  return '整個套件'
}

function findingConfidenceLabel(finding: SecurityFindingSummary): string | null {
  if (finding.confidence === 'HIGH') return '高信心'
  if (finding.confidence === 'MEDIUM') return '中信心'
  if (finding.confidence === 'LOW') return '低信心'
  return null
}

function findingRemediation(finding: SecurityFindingSummary): string {
  return finding.remediation ?? '未提供修法建議'
}

const SEVERITY_ORDER: Record<string, number> = { HIGH: 0, MEDIUM: 1, LOW: 2 }

function sortFindings(findings: SecurityFindingSummary[]): SecurityFindingSummary[] {
  return [...findings].sort((a, b) => {
    const severity = (SEVERITY_ORDER[a.severity ?? ''] ?? 3) - (SEVERITY_ORDER[b.severity ?? ''] ?? 3)
    if (severity !== 0) return severity
    const code = (a.issueCode ?? '\uffff').localeCompare(b.issueCode ?? '\uffff')
    if (code !== 0) return code
    const path = (a.filePath ?? '\uffff').localeCompare(b.filePath ?? '\uffff')
    if (path !== 0) return path
    return (a.line ?? Number.MAX_SAFE_INTEGER) - (b.line ?? Number.MAX_SAFE_INTEGER)
  })
}

function filterFindings(findings: SecurityFindingSummary[], filter: SeverityFilter): SecurityFindingSummary[] {
  if (filter === 'ALL') return findings
  return findings.filter(finding => finding.severity === filter)
}

function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleDateString('zh-TW')
}

function SummaryBlock({ label, value, testId }: { label: string; value: string | number; testId?: string }) {
  return (
    <div
      style={{
        padding: '12px 14px',
        border: '0.5px solid var(--line, rgba(255,255,255,0.08))',
        borderRadius: 8,
        background: 'rgba(255,255,255,0.02)',
        minWidth: 0,
      }}
    >
      <div style={{ fontSize: 11, color: 'var(--ink-3, rgba(238,236,234,0.4))', marginBottom: 4 }}>
        {label}
      </div>
      <div data-testid={testId} style={{ fontSize: 18, fontWeight: 600 }}>
        {value}
      </div>
    </div>
  )
}

function severityColor(severity: SecurityFindingSummary['severity']): string {
  if (severity === 'HIGH') return 'var(--red-text, #F08080)'
  if (severity === 'MEDIUM') return 'var(--amber-text, #FAC775)'
  if (severity === 'LOW') return 'var(--green-text, #6FD8B0)'
  return 'var(--ink-3, rgba(238,236,234,0.4))'
}

function whyTitle(riskLevel: RiskLevel | null | undefined): string {
  return `為什麼是${riskLevelLabel(riskLevel)}？`
}

function reasonHeading(reason: SecurityRiskReason): string {
  if (reason.code === 'SCRIPTS_INCLUDED') return '包含可執行腳本'
  return reason.label
}

function actionTone(reason: SecurityRiskReason): string {
  if (reason.action === 'FIX_REQUIRED') return '先查看掃描發現'
  if (reason.action === 'REVIEW_FIRST') return '使用前先確認'
  return '可以下載使用'
}

function RiskReasonSection({ reasons, riskLevel }: { reasons: SecurityRiskReason[]; riskLevel: RiskLevel | null | undefined }) {
  if (reasons.length === 0) return null
  return (
    <section style={{ marginBottom: 20 }}>
      <h3 style={{ fontSize: 14, fontWeight: 600, margin: '0 0 10px' }}>
        {whyTitle(riskLevel)}
      </h3>
      <div style={{ display: 'grid', gap: 10 }}>
        {reasons.map(reason => (
          <article
            key={`${reason.code}-${reason.label}`}
            style={{
              padding: 14,
              border: '0.5px solid var(--line, rgba(255,255,255,0.08))',
              borderRadius: 8,
              background: 'rgba(255,255,255,0.02)',
              minWidth: 0,
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap', marginBottom: 8 }}>
              <div style={{ fontSize: 13, fontWeight: 650, overflowWrap: 'anywhere' }}>
                {reasonHeading(reason)}
              </div>
              <span style={{ fontSize: 12, color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>
                {actionTone(reason)}
              </span>
            </div>
            <p style={{ margin: '0 0 10px', fontSize: 13, color: 'var(--ink-2, rgba(238,236,234,0.7))', overflowWrap: 'anywhere' }}>
              {reason.detail}
            </p>
            {reason.evidence.length > 0 && (
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                {reason.evidence.map(item => (
                  <span
                    key={item}
                    style={{
                      padding: '4px 7px',
                      border: '0.5px solid var(--line, rgba(255,255,255,0.08))',
                      borderRadius: 6,
                      background: 'rgba(255,255,255,0.03)',
                      fontFamily: 'monospace',
                      fontSize: 12,
                      overflowWrap: 'anywhere',
                      maxWidth: '100%',
                    }}
                  >
                    {item}
                  </span>
                ))}
              </div>
            )}
          </article>
        ))}
      </div>
    </section>
  )
}

function ActionButton({ children, primary, icon }: { children: string; primary?: boolean; icon: ReactNode }) {
  return (
    <button
      type="button"
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 6,
        padding: '7px 10px',
        borderRadius: 8,
        border: primary ? '0.5px solid rgba(127,119,221,.45)' : '0.5px solid var(--line, rgba(255,255,255,0.08))',
        background: primary ? 'rgba(127,119,221,.16)' : 'rgba(255,255,255,0.03)',
        color: 'var(--ink-1, #EEECEA)',
        fontSize: 12,
        cursor: 'pointer',
      }}
    >
      {icon}
      {children}
    </button>
  )
}

function ActionStrip({ hasFindings }: { hasFindings: boolean }) {
  return (
    <section style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 20 }}>
      {hasFindings ? (
        <ActionButton primary icon={<Search size={14} aria-hidden="true" />}>先查看掃描發現</ActionButton>
      ) : (
        <ActionButton primary icon={<Download size={14} aria-hidden="true" />}>下載技能</ActionButton>
      )}
      <ActionButton icon={<FileText size={14} aria-hidden="true" />}>查看檔案</ActionButton>
      <ActionButton icon={<Flag size={14} aria-hidden="true" />}>回報疑慮</ActionButton>
    </section>
  )
}

function FindingRow({ finding, index }: { finding: SecurityFindingSummary; index: number }) {
  const confidence = findingConfidenceLabel(finding)
  const code = findingCode(finding)
  const severity = finding.severity ?? '未分級'

  return (
    <article
      data-testid={`security-finding-${index}`}
      style={{
        padding: 14,
        border: '0.5px solid var(--line, rgba(255,255,255,0.08))',
        borderLeft: `3px solid ${severityColor(finding.severity)}`,
        borderRadius: 8,
        background: 'rgba(255,255,255,0.02)',
        minWidth: 0,
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap', marginBottom: 8 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', minWidth: 0 }}>
          <span style={{ fontFamily: 'monospace', fontSize: 13, fontWeight: 700, overflowWrap: 'anywhere' }}>
            {code}
          </span>
          <span style={{ fontSize: 12, color: severityColor(finding.severity), fontWeight: 600 }}>
            {severity}
          </span>
        </div>
        {confidence && (
          <span style={{ fontSize: 12, color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>
            {confidence}
          </span>
        )}
      </div>

      <div style={{ fontSize: 13, marginBottom: 8, overflowWrap: 'anywhere' }}>
        {finding.message}
      </div>

      <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', fontSize: 12, color: 'var(--ink-3, rgba(238,236,234,0.4))', marginBottom: 8 }}>
        <span style={{ fontFamily: 'monospace', overflowWrap: 'anywhere' }}>{findingLocation(finding)}</span>
        <span style={{ fontFamily: 'monospace', overflowWrap: 'anywhere' }}>{finding.ruleId}</span>
      </div>

      {finding.evidence && (
        <pre
          data-testid={`finding-evidence-${index}`}
          style={{
            margin: '8px 0',
            padding: 10,
            borderRadius: 6,
            background: 'rgba(0,0,0,0.18)',
            color: 'var(--ink-2, rgba(238,236,234,0.7))',
            fontSize: 12,
            whiteSpace: 'pre-wrap',
            overflowWrap: 'anywhere',
            fontFamily: 'monospace',
          }}
        >
          {finding.evidence}
        </pre>
      )}

      <div style={{ fontSize: 12, color: 'var(--ink-2, rgba(238,236,234,0.7))', overflowWrap: 'anywhere' }}>
        修法：{findingRemediation(finding)}
      </div>
    </article>
  )
}

function FilterButton({ active, label, onClick }: { active: boolean; label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      style={{
        padding: '6px 10px',
        borderRadius: 8,
        border: active ? '0.5px solid rgba(127,119,221,.45)' : '0.5px solid var(--line, rgba(255,255,255,0.08))',
        background: active ? 'rgba(127,119,221,.16)' : 'rgba(255,255,255,0.03)',
        color: active ? 'var(--ink-1, #EEECEA)' : 'var(--ink-2, rgba(238,236,234,0.7))',
        fontSize: 12,
        cursor: 'pointer',
      }}
    >
      {label}
    </button>
  )
}

/**
 * S183 — Security tab summary for S147 issue-code reports.
 * The current level comes from skill.riskLevel; findings only drive counts and details.
 */
export function SecurityTab({ report, riskLevel }: Props) {
  const [filter, setFilter] = useState<SeverityFilter>('ALL')

  if (report === undefined) {
    return <div data-testid="security-tab-loading" style={{ padding: 24 }}>
      <div className="animate-pulse" style={{ height: 200, background: 'rgba(255,255,255,0.05)', borderRadius: 8 }} />
    </div>
  }

  if (report === null) {
    return (
      <div data-testid="security-tab-empty" style={{ padding: 24, textAlign: 'center', color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>
        <div>尚未進行安全掃描</div>
      </div>
    )
  }

  const counts = severityCounts(report.findings)
  const sortedFindings = sortFindings(report.findings)
  const filteredFindings = filterFindings(sortedFindings, filter)
  const riskReasons = report.riskReasons ?? []

  return (
    <div data-testid="security-tab" style={{ padding: '16px 0', minWidth: 0 }}>
      <section
        style={{
          padding: '18px 0 20px',
          borderBottom: '0.5px solid var(--line, rgba(255,255,255,0.08))',
          marginBottom: 18,
        }}
      >
        <h2 style={{ fontSize: 18, fontWeight: 600, margin: '0 0 6px' }}>
          安全性
        </h2>
        <div style={{ fontSize: 12, color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>
          掃描 {formatDate(report.scannedAt)} · {report.engineVersion || '—'} · 規則集 {report.ruleSetVersion || '—'}
        </div>
      </section>

      <section style={{ marginBottom: 20 }}>
        <h3 style={{ fontSize: 14, fontWeight: 600, margin: '0 0 10px' }}>
          掃描摘要
        </h3>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 150px), 1fr))', gap: 10 }}>
          <SummaryBlock label="目前等級" value={riskLevelLabel(riskLevel)} testId="security-current-risk" />
          <SummaryBlock label="高風險 findings" value={counts.high} testId="security-high-count" />
          <SummaryBlock label="中風險 findings" value={counts.medium} testId="security-medium-count" />
          <SummaryBlock label="低風險 findings" value={counts.low} testId="security-low-count" />
        </div>
      </section>

      <RiskReasonSection reasons={riskReasons} riskLevel={riskLevel} />

      <ActionStrip hasFindings={counts.total > 0} />

      <section>
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap', alignItems: 'center', marginBottom: 10 }}>
          <h3 style={{ fontSize: 14, fontWeight: 600, margin: 0 }}>
            掃描發現
          </h3>
          {counts.total > 0 && (
            <span style={{ fontSize: 12, color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>
              顯示 {filteredFindings.length} / {counts.total} 筆
            </span>
          )}
        </div>
        {counts.total === 0 ? (
          <div
            data-testid="security-findings-empty"
            style={{
              padding: 16,
              border: '0.5px solid var(--line, rgba(255,255,255,0.08))',
              borderRadius: 8,
              color: 'var(--ink-3, rgba(238,236,234,0.4))',
              background: 'rgba(255,255,255,0.02)',
            }}
          >
            <div>{emptyFindingMessage(riskLevel)}</div>
            {riskLevel === 'LOW' && (
              <p style={{ margin: '6px 0 0', fontSize: 12, color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>
                scanner 沒有找到 issue code，不代表技能沒有任何能力風險。
              </p>
            )}
          </div>
        ) : (
          <>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 10 }}>
              <FilterButton active={filter === 'ALL'} label="全部" onClick={() => setFilter('ALL')} />
              <FilterButton active={filter === 'HIGH'} label="高" onClick={() => setFilter('HIGH')} />
              <FilterButton active={filter === 'MEDIUM'} label="中" onClick={() => setFilter('MEDIUM')} />
              <FilterButton active={filter === 'LOW'} label="低" onClick={() => setFilter('LOW')} />
            </div>
            <div style={{ display: 'grid', gap: 10 }}>
              {filteredFindings.map((finding, index) => (
                <FindingRow key={`${finding.ruleId}-${finding.issueCode ?? 'legacy'}-${index}`} finding={finding} index={index} />
              ))}
            </div>
          </>
        )}
      </section>
    </div>
  )
}
