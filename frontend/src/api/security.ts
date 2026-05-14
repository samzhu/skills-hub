import { apiFetch, ApiError } from './client'

/** S142b §4.4 — 單一 security category 的狀態與說明。 */
export interface SecurityCheck {
  /** PASS / WARN / FAIL */
  status: 'PASS' | 'WARN' | 'FAIL'
  detail: string
}

/** S147-T01 — issue-code category summary returned by the new scanner contract. */
export interface SecurityCategorySummary {
  key: string
  label: string
  status: 'PASS' | 'WARN' | 'FAIL'
  findingCount: number
  highestSeverity: 'HIGH' | 'MEDIUM' | 'LOW' | null
}

/** S147-T01 — issue-code finding detail returned by the new scanner contract. */
export interface SecurityFindingSummary {
  ruleId: string
  issueCode: string | null
  severity: 'HIGH' | 'MEDIUM' | 'LOW' | null
  message: string
  remediation: string | null
  confidence: 'HIGH' | 'MEDIUM' | 'LOW' | null
  filePath: string | null
  line: number | null
  evidence: string | null
}

/**
 * S147-T01 — GET /api/v1/skills/{id}/security-report 的回應 shape。
 * 新版 scanner 以 categories/findings 為主；checks 保留給既有 UI 相容。
 */
export interface SecurityReport {
  skillId: string
  skillVersionId: string
  skillVersion: string
  scannedAt: string
  engineVersion: string
  ruleSetVersion: string
  /** 整體結論：PASS / WARN / FAIL */
  overall: 'PASS' | 'WARN' | 'FAIL'
  checks: {
    shell: SecurityCheck
    paths: SecurityCheck
    secrets: SecurityCheck
    deps: SecurityCheck
  }
  categories: SecurityCategorySummary[]
  findings: SecurityFindingSummary[]
}

/**
 * S142b — 取得技能安全報告。
 * 404 SECURITY_NOT_SCANNED → return null（尚未掃描，屬正常狀態）。
 * 其他錯誤 → rethrow 讓 React Query 處理重試。
 */
export async function fetchSecurityReport(skillId: string): Promise<SecurityReport | null> {
  try {
    return await apiFetch<SecurityReport>(`/skills/${skillId}/security-report`)
  } catch (err) {
    if (ApiError.is(err) && err.status === 404) return null
    throw err
  }
}
