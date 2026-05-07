import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { SecurityTab } from './SecurityTab'
import type { SecurityReport } from '@/api/security'

const passReport: SecurityReport = {
  skillId: 's1', skillVersionId: 'v1', skillVersion: '1.0.0',
  scannedAt: '2026-05-07T00:00:00Z', engineVersion: 'v1.0', ruleSetVersion: '2026-05',
  overall: 'PASS',
  checks: {
    shell:   { status: 'PASS', detail: 'No shell scripts' },
    paths:   { status: 'PASS', detail: 'No sensitive paths' },
    secrets: { status: 'PASS', detail: 'No hardcoded secrets' },
    deps:    { status: 'PASS', detail: 'No dependencies' },
  },
}

const warnReport: SecurityReport = {
  ...passReport,
  overall: 'WARN',
  checks: {
    shell:   { status: 'PASS', detail: 'No shell scripts' },
    paths:   { status: 'WARN', detail: 'Sensitive path access detected' },
    secrets: { status: 'PASS', detail: 'No hardcoded secrets' },
    deps:    { status: 'PASS', detail: 'No dependencies' },
  },
}

const failReport: SecurityReport = {
  ...passReport,
  overall: 'FAIL',
  checks: {
    shell:   { status: 'FAIL', detail: 'Dangerous shell command found' },
    paths:   { status: 'PASS', detail: 'OK' },
    secrets: { status: 'PASS', detail: 'OK' },
    deps:    { status: 'PASS', detail: 'OK' },
  },
}

describe('SecurityTab', () => {
  it('AC-S142a-8: PASS → hero title "No security issues found" (green)', () => {
    render(<SecurityTab report={passReport} />)
    expect(screen.getByTestId('security-hero-title').textContent).toBe('No security issues found')
    expect(screen.getByTestId('security-hero-title').style.color).toContain('6FD8B0')
  })

  it('AC-S142a-8: 4 quad cards rendered', () => {
    render(<SecurityTab report={passReport} />)
    expect(screen.getByTestId('security-quads').children.length).toBe(4)
    expect(screen.getByTestId('quad-shell')).toBeTruthy()
    expect(screen.getByTestId('quad-paths')).toBeTruthy()
    expect(screen.getByTestId('quad-secrets')).toBeTruthy()
    expect(screen.getByTestId('quad-deps')).toBeTruthy()
  })

  it('AC-S142a-8: WARN → "1 issue requires review" + amber color', () => {
    render(<SecurityTab report={warnReport} />)
    expect(screen.getByTestId('security-hero-title').textContent).toContain('review')
    expect(screen.getByTestId('security-hero-title').style.color).toContain('FAC775')
  })

  it('AC-S142a-8: FAIL → "N issues require attention" + red color', () => {
    render(<SecurityTab report={failReport} />)
    expect(screen.getByTestId('security-hero-title').textContent).toContain('attention')
    expect(screen.getByTestId('security-hero-title').style.color).toContain('F08080')
  })

  it('PASS quad shows "✓ Passed" status', () => {
    render(<SecurityTab report={passReport} />)
    expect(screen.getAllByText('✓ Passed').length).toBe(4)
  })

  it('WARN quad shows "! Review" status', () => {
    render(<SecurityTab report={warnReport} />)
    expect(screen.getByText('! Review')).toBeTruthy()
  })

  it('report=null → 尚未掃描 fallback', () => {
    render(<SecurityTab report={null} />)
    expect(screen.getByTestId('security-tab-empty')).toBeTruthy()
    expect(screen.getByText('Security report 尚未掃描')).toBeTruthy()
  })

  it('quad detail text rendered', () => {
    render(<SecurityTab report={passReport} />)
    expect(screen.getByText('No shell scripts')).toBeTruthy()
    expect(screen.getByText('No sensitive paths')).toBeTruthy()
  })
})
