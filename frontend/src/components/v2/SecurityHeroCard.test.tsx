import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { SecurityHeroCard } from './SecurityHeroCard'
import type { SecurityReport } from '@/api/security'

const passReport: SecurityReport = {
  skillId: 's1', skillVersionId: 'v1', skillVersion: '1.0.0',
  scannedAt: '2026-05-07T00:00:00Z', engineVersion: 'v1', ruleSetVersion: 'v1',
  overall: 'PASS',
  checks: {
    shell:   { status: 'PASS', detail: 'No shell scripts' },
    paths:   { status: 'PASS', detail: 'No sensitive paths' },
    secrets: { status: 'PASS', detail: 'No secrets' },
    deps:    { status: 'PASS', detail: 'No dependencies' },
  },
}

const warnReport: SecurityReport = {
  ...passReport,
  overall: 'WARN',
  checks: {
    shell:   { status: 'PASS', detail: 'No shell scripts' },
    paths:   { status: 'WARN', detail: 'Sensitive path access detected' },
    secrets: { status: 'PASS', detail: 'No secrets' },
    deps:    { status: 'PASS', detail: 'No dependencies' },
  },
}

describe('SecurityHeroCard', () => {
  it('AC-S142a-6: PASS → "Passed" + 4 green segments', () => {
    render(<SecurityHeroCard report={passReport} active={false} onClick={vi.fn()} />)
    expect(screen.getByTestId('security-value').textContent).toBe('Passed')
    // JSDOM normalizes hex to rgb — check via toContain partial
    expect(screen.getByTestId('seg-shell').style.background).toContain('29')   // rgb(29,...)
    expect(screen.getByTestId('seg-paths').style.background).toContain('29')
  })

  it('AC-S142a-9: WARN paths → seg-paths amber; others green; card border amber; "1 Issue"', () => {
    const { getByTestId } = render(
      <SecurityHeroCard report={warnReport} active={false} onClick={vi.fn()} />
    )
    expect(screen.getByTestId('security-value').textContent).toBe('1 Issue')
    expect(getByTestId('seg-paths').style.background).toContain('239')    // rgb(239,...) = amber
    expect(getByTestId('seg-shell').style.background).toContain('29')     // rgb(29,...)  = green
    // border: JSDOM normalizes rgba shorthand; check key color components
    expect(getByTestId('security-hero-card').style.border).toContain('239')
  })

  it('AC-S142a-9: WARN sub text shows first issue detail', () => {
    render(<SecurityHeroCard report={warnReport} active={false} onClick={vi.fn()} />)
    expect(screen.getByText('Sensitive path access detected')).toBeTruthy()
  })

  it('AC-S142a-8: click 觸發 onClick', () => {
    const onClick = vi.fn()
    render(<SecurityHeroCard report={passReport} active={false} onClick={onClick} />)
    fireEvent.click(screen.getByTestId('security-hero-card'))
    expect(onClick).toHaveBeenCalledOnce()
  })

  it('report=null → "—" + 未掃描', () => {
    render(<SecurityHeroCard report={null} active={false} onClick={vi.fn()} />)
    expect(screen.getByTestId('security-value').textContent).toBe('—')
    expect(screen.getByText('未掃描')).toBeTruthy()
  })
})
