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
  it('AC-S142a-8: PASS → hero title "未發現安全問題" (green)', () => {
    render(<SecurityTab report={passReport} />)
    expect(screen.getByTestId('security-hero-title').textContent).toBe('未發現安全問題')
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

  it('AC-S142a-8: WARN → "N 個問題需要審查" + amber color', () => {
    render(<SecurityTab report={warnReport} />)
    expect(screen.getByTestId('security-hero-title').textContent).toContain('審查')
    expect(screen.getByTestId('security-hero-title').style.color).toContain('FAC775')
  })

  it('AC-S142a-8: FAIL → "N 個問題需要注意" + red color', () => {
    render(<SecurityTab report={failReport} />)
    expect(screen.getByTestId('security-hero-title').textContent).toContain('注意')
    expect(screen.getByTestId('security-hero-title').style.color).toContain('F08080')
  })

  it('PASS quad shows "✓ 通過" status', () => {
    render(<SecurityTab report={passReport} />)
    expect(screen.getAllByText('✓ 通過').length).toBe(4)
  })

  it('WARN quad shows "! 需審查" status', () => {
    render(<SecurityTab report={warnReport} />)
    expect(screen.getByText('! 需審查')).toBeTruthy()
  })

  it('report=null → 尚未掃描 fallback', () => {
    render(<SecurityTab report={null} />)
    expect(screen.getByTestId('security-tab-empty')).toBeTruthy()
    expect(screen.getByText('尚未進行安全掃描')).toBeTruthy()
  })

  it('quad detail text rendered', () => {
    render(<SecurityTab report={passReport} />)
    expect(screen.getByText('No shell scripts')).toBeTruthy()
    expect(screen.getByText('No sensitive paths')).toBeTruthy()
  })
})
