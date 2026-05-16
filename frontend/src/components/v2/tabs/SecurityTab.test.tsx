import { fireEvent, render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { SecurityTab } from './SecurityTab'
import type { SecurityReport } from '@/api/security'

const emptyReport: SecurityReport = {
  skillId: 's1', skillVersionId: 'v1', skillVersion: '1.0.0',
  scannedAt: '2026-05-07T00:00:00Z', engineVersion: 'risk-scanner v1.0', ruleSetVersion: '2026-05',
  overall: 'PASS',
  checks: {
    shell: { status: 'PASS', detail: 'No shell scripts' },
    paths: { status: 'PASS', detail: 'No sensitive paths' },
    secrets: { status: 'PASS', detail: 'No hardcoded secrets' },
    deps: { status: 'PASS', detail: 'No dependencies' },
  },
  categories: [],
  findings: [],
}

const findingReport: SecurityReport = {
  ...emptyReport,
  overall: 'FAIL',
  findings: [
    {
      issueCode: 'W008',
      ruleId: 'OPENAI_KEY',
      severity: 'HIGH',
      message: 'Hardcoded secret detected',
      remediation: '請移除寫死在 package 文字裡的 secret',
      confidence: 'HIGH',
      filePath: 'scripts/use-openai.sh',
      line: 3,
      evidence: '<img src=x onerror=alert(1)>',
    },
  ],
}

const legacyFindingReport: SecurityReport = {
  ...emptyReport,
  findings: [
    {
      issueCode: null,
      ruleId: 'LEGACY_RULE',
      severity: null,
      message: 'Legacy scanner finding',
      remediation: null,
      confidence: null,
      filePath: null,
      line: null,
      evidence: null,
    },
  ],
}

const multiFindingReport: SecurityReport = {
  ...emptyReport,
  overall: 'FAIL',
  findings: [
    {
      issueCode: 'W008',
      ruleId: 'OPENAI_KEY',
      severity: 'HIGH',
      message: 'Hardcoded secret detected',
      remediation: 'Remove secret',
      confidence: 'HIGH',
      filePath: 'scripts/use-openai.sh',
      line: 3,
      evidence: 'sk-***',
    },
    {
      issueCode: 'W009',
      ruleId: 'DIRECT_PAYMENT',
      severity: 'MEDIUM',
      message: 'Direct financial action',
      remediation: 'Add confirmation step',
      confidence: 'MEDIUM',
      filePath: 'SKILL.md',
      line: 20,
      evidence: 'transfer money',
    },
    {
      issueCode: 'W014',
      ruleId: 'MISSING_SKILL_MD',
      severity: 'LOW',
      message: 'Missing SKILL.md',
      remediation: 'Add SKILL.md',
      confidence: 'LOW',
      filePath: null,
      line: null,
      evidence: null,
    },
  ],
}

describe('SecurityTab', () => {
  it('AC-S183-7: 安全性 tab 不顯示第二組四燈與 Shell/Paths/Secrets/Deps', () => {
    render(<SecurityTab riskLevel="HIGH" report={emptyReport} />)

    expect(screen.queryByTestId('security-quads')).toBeNull()
    expect(screen.queryByText('Shell')).toBeNull()
    expect(screen.queryByText('Paths')).toBeNull()
    expect(screen.queryByText('Secrets')).toBeNull()
    expect(screen.queryByText('Deps')).toBeNull()
    expect(screen.getByText('掃描摘要')).toBeTruthy()
    expect(screen.getByText('掃描發現')).toBeTruthy()
  })

  it('AC-S183-12: LOW + empty findings 顯示低風險、三個 findings count 都是 0、沒有需要處理的掃描發現', () => {
    render(<SecurityTab riskLevel="LOW" report={emptyReport} />)

    expect(screen.getByTestId('security-current-risk').textContent).toBe('低風險')
    expect(screen.getByTestId('security-high-count').textContent).toBe('0')
    expect(screen.getByTestId('security-medium-count').textContent).toBe('0')
    expect(screen.getByTestId('security-low-count').textContent).toBe('0')
    expect(screen.getByText('沒有需要處理的掃描發現')).toBeTruthy()
  })

  it('AC-S183-13: NONE + empty findings 顯示無風險與未發現安全問題', () => {
    render(<SecurityTab riskLevel="NONE" report={emptyReport} />)

    expect(screen.getByTestId('security-current-risk').textContent).toBe('無風險')
    expect(screen.getByText('未發現安全問題')).toBeTruthy()
  })

  it('report=null → 尚未進行安全掃描 fallback', () => {
    render(<SecurityTab riskLevel={null} report={null} />)

    expect(screen.getByTestId('security-tab-empty')).toBeTruthy()
    expect(screen.getByText('尚未進行安全掃描')).toBeTruthy()
  })

  it('AC-S183-5: evidence/remediation 以純文字顯示，不執行 HTML', () => {
    render(<SecurityTab riskLevel="HIGH" report={findingReport} />)

    expect(screen.getByTestId('finding-evidence-0').textContent).toBe('<img src=x onerror=alert(1)>')
    expect(document.querySelector('img[src="x"]')).toBeNull()
    expect(screen.getByText('修法：請移除寫死在 package 文字裡的 secret')).toBeTruthy()
  })

  it('AC-S183-6: finding row 顯示 W008、scripts/use-openai.sh:3、OPENAI_KEY', () => {
    render(<SecurityTab riskLevel="HIGH" report={findingReport} />)

    expect(screen.getByText('W008')).toBeTruthy()
    expect(screen.getByText('scripts/use-openai.sh:3')).toBeTruthy()
    expect(screen.getByText('OPENAI_KEY')).toBeTruthy()
  })

  it('AC-S183-11: null finding fields 顯示 LEGACY、整個套件、未提供修法建議且不 throw', () => {
    render(<SecurityTab riskLevel="LOW" report={legacyFindingReport} />)

    expect(screen.getByText('LEGACY')).toBeTruthy()
    expect(screen.getByText('整個套件')).toBeTruthy()
    expect(screen.getByText('修法：未提供修法建議')).toBeTruthy()
  })

  it('AC-S183-8: 點高只顯 HIGH finding，顯示 1 / 3 筆，沒有重新呼叫 security-report', () => {
    const originalFetch = globalThis.fetch
    const fetchSpy = vi.fn()
    globalThis.fetch = fetchSpy as unknown as typeof fetch

    try {
      render(<SecurityTab riskLevel="HIGH" report={multiFindingReport} />)

      fireEvent.click(screen.getByRole('button', { name: '高' }))

      expect(screen.getByText('W008')).toBeTruthy()
      expect(screen.queryByText('W009')).toBeNull()
      expect(screen.queryByText('W014')).toBeNull()
      expect(screen.getByText('顯示 1 / 3 筆')).toBeTruthy()
      expect(fetchSpy).not.toHaveBeenCalled()
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  it('AC-S183-9: 點全部後三筆 finding 都顯示', () => {
    render(<SecurityTab riskLevel="HIGH" report={multiFindingReport} />)

    fireEvent.click(screen.getByRole('button', { name: '高' }))
    fireEvent.click(screen.getByRole('button', { name: '全部' }))

    expect(screen.getByText('W008')).toBeTruthy()
    expect(screen.getByText('W009')).toBeTruthy()
    expect(screen.getByText('W014')).toBeTruthy()
    expect(screen.getByText('顯示 3 / 3 筆')).toBeTruthy()
  })
})
