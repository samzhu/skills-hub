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

const lowToolsScriptsReport: SecurityReport = {
  ...emptyReport,
  riskReasons: [
    {
      code: 'ALLOWED_TOOLS_DECLARED',
      label: '這個技能可以要求 AI 使用工具',
      detail: '掃描沒有找到需要修改的問題。不過這個技能可以要求 AI 使用工具：Bash、Write、Edit，所以使用前請先確認你接受這些能力。',
      impact: 'LOW',
      evidence: ['Bash', 'Write', 'Edit'],
      action: 'REVIEW_FIRST',
    },
    {
      code: 'SCRIPTS_INCLUDED',
      label: '這個技能包含 scripts/ 程式檔',
      detail: '掃描沒有找到需要修改的問題。不過這個技能包含 scripts/ 程式檔：scripts/check_deps.sh，使用前請先確認你接受這些檔案會被 AI 工作流程使用。',
      impact: 'LOW',
      evidence: ['scripts/check_deps.sh'],
      action: 'REVIEW_FIRST',
    },
  ],
}

const lowToolsOnlyReport: SecurityReport = {
  ...emptyReport,
  riskReasons: [
    {
      code: 'ALLOWED_TOOLS_DECLARED',
      label: '這個技能可以要求 AI 使用工具',
      detail: '掃描沒有找到需要修改的問題。不過這個技能可以要求 AI 使用工具：Read、Glob、Grep、Bash、Write、Edit、WebFetch、WebSearch，所以使用前請先確認你接受這些能力。',
      impact: 'LOW',
      evidence: ['Read', 'Glob', 'Grep', 'Bash', 'Write', 'Edit', 'WebFetch', 'WebSearch'],
      action: 'REVIEW_FIRST',
    },
  ],
}

const noneReport: SecurityReport = {
  ...emptyReport,
  riskReasons: [
    {
      code: 'NO_FINDINGS_NO_CAPABILITIES',
      label: '沒有工具宣告或 scripts/',
      detail: '未發現安全問題，且這個技能沒有工具宣告或 scripts/。這不代表 100% 安全，只表示 scanner 沒有找到已知問題。',
      impact: 'NONE',
      evidence: [],
      action: 'DOWNLOAD_OK',
    },
  ],
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

  it('AC-S183-12: LOW + empty findings 顯示低風險、三個 findings count 都是 0、沒有需要修改的掃描發現', () => {
    render(<SecurityTab riskLevel="LOW" report={emptyReport} />)

    expect(screen.getByTestId('security-current-risk').textContent).toBe('低風險')
    expect(screen.getByTestId('security-high-count').textContent).toBe('0')
    expect(screen.getByTestId('security-medium-count').textContent).toBe('0')
    expect(screen.getByTestId('security-low-count').textContent).toBe('0')
    expect(screen.getByText('沒有需要修改的掃描發現')).toBeTruthy()
  })

  it('AC-S183-13: NONE + empty findings 顯示未發現風險與未發現安全問題', () => {
    render(<SecurityTab riskLevel="NONE" report={noneReport} />)

    expect(screen.getByTestId('security-current-risk').textContent).toBe('未發現風險')
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

  it('AC-S190-1: LOW tools + scripts reasons render non-engineer copy', () => {
    render(<SecurityTab riskLevel="LOW" report={lowToolsScriptsReport} />)

    expect(screen.getByTestId('security-current-risk').textContent).toBe('低風險')
    expect(screen.getByText('為什麼是低風險？')).toBeTruthy()
    expect(screen.getByText('這個技能可以要求 AI 使用工具')).toBeTruthy()
    expect(screen.getAllByText(/掃描沒有找到需要修改的問題/).length).toBeGreaterThan(0)
    expect(screen.getByText(/使用前請先確認你接受這些能力/)).toBeTruthy()
    expect(screen.getByText('Bash')).toBeTruthy()
    expect(screen.getByText('Write')).toBeTruthy()
    expect(screen.getByText('Edit')).toBeTruthy()
    expect(screen.getByText('包含可執行腳本')).toBeTruthy()
    expect(screen.getByText('scripts/check_deps.sh')).toBeTruthy()
  })

  it('AC-S190-1b: allowed-tools-only LOW renders all tool names and not only engineering field label', () => {
    render(<SecurityTab riskLevel="LOW" report={lowToolsOnlyReport} />)

    for (const tool of ['Read', 'Glob', 'Grep', 'Bash', 'Write', 'Edit', 'WebFetch', 'WebSearch']) {
      expect(screen.getByText(tool)).toBeTruthy()
    }
    expect(screen.getByText('這個技能可以要求 AI 使用工具')).toBeTruthy()
    expect(screen.queryByText('allowed-tools')).toBeNull()
  })

  it('AC-S190-2: LOW + empty findings shows no-fix finding copy and caveat', () => {
    render(<SecurityTab riskLevel="LOW" report={lowToolsOnlyReport} />)

    expect(screen.getByText('沒有需要修改的掃描發現')).toBeTruthy()
    expect(screen.getByText(/scanner 沒有找到 issue code/)).toBeTruthy()
  })

  it('AC-S190-3: NONE + empty findings renders 未發現風險 and no-capability reason', () => {
    render(<SecurityTab riskLevel="NONE" report={noneReport} />)

    expect(screen.getByTestId('security-current-risk').textContent).toBe('未發現風險')
    expect(screen.getByText('為什麼是未發現風險？')).toBeTruthy()
    expect(screen.getByText('沒有工具宣告或 scripts/')).toBeTruthy()
    expect(screen.getByText('未發現安全問題')).toBeTruthy()
  })

  it('AC-S190-4: HIGH finding row remains visible with remediation', () => {
    render(<SecurityTab riskLevel="HIGH" report={findingReport} />)

    expect(screen.getByText('W008')).toBeTruthy()
    expect(screen.getByText('scripts/use-openai.sh:3')).toBeTruthy()
    expect(screen.getByText('修法：請移除寫死在 package 文字裡的 secret')).toBeTruthy()
    expect(screen.getByText('先查看掃描發現')).toBeTruthy()
  })

  it('AC-S190-8: LOW actions show download/view/report and download is enabled', () => {
    render(<SecurityTab riskLevel="LOW" report={lowToolsOnlyReport} />)

    expect(screen.getByRole('button', { name: '下載技能' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '查看檔案' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '回報疑慮' })).toBeTruthy()
  })
})
