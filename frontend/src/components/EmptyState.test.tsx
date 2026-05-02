import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { EmptyState } from './EmptyState'

describe('EmptyState — S094c 4 tones', () => {
  it('AC-1: seed tone renders eyebrow + headline + sub + ghost preview', () => {
    render(
      <EmptyState
        tone="seed"
        eyebrow="0 skills · 0 publishers"
        headline="Your registry is waiting to be seeded."
        sub="Skills Hub gets more valuable as your team shares."
        primaryAction={{ label: 'Publish the first skill', href: '/publish' }}
      />,
    )
    expect(screen.getByText('0 skills · 0 publishers')).toBeInTheDocument()
    expect(screen.getByText('Your registry is waiting to be seeded.')).toBeInTheDocument()
    expect(screen.getByText(/Skills Hub gets more valuable/)).toBeInTheDocument()
    expect(screen.getByText('Publish the first skill')).toBeInTheDocument()
  })

  it("AC-2: invite tone renders icon + headline + 4-step flow", () => {
    render(
      <EmptyState
        tone="invite"
        headline="You haven't published anything yet."
        sub="Ship something you've built to the team."
      />,
    )
    expect(screen.getByText("You haven't published anything yet.")).toBeInTheDocument()
    // 4 steps: Zip / Auto-scan / Publish / Track
    expect(screen.getByText('Zip')).toBeInTheDocument()
    expect(screen.getByText('Auto-scan')).toBeInTheDocument()
    expect(screen.getByText('Publish')).toBeInTheDocument()
    expect(screen.getByText('Track')).toBeInTheDocument()
  })

  it('AC-3: redirect tone renders query echo + headline + suggestions list', () => {
    render(
      <EmptyState
        tone="redirect"
        query="crypto wallet integration for embedded devices"
        headline="We don't have a skill for that yet."
        sub="Nothing reaches above 0.32 similarity."
        suggestions={[
          { text: 'Try "wallet signature verification"', hint: '5 related · 0.68 avg' },
          { text: 'Browse all 247 skills', hint: 'Jump to the full registry' },
        ]}
      />,
    )
    expect(screen.getByText('Query ·')).toBeInTheDocument()
    expect(screen.getByText('"crypto wallet integration for embedded devices"')).toBeInTheDocument()
    expect(screen.getByText("We don't have a skill for that yet.")).toBeInTheDocument()
    expect(screen.getByText('Try "wallet signature verification"')).toBeInTheDocument()
    expect(screen.getByText('Browse all 247 skills')).toBeInTheDocument()
  })

  it('AC-4: clear tone renders check icon + headline + stats + audit link', () => {
    render(
      <EmptyState
        tone="clear"
        headline="All clear — nothing pending."
        sub="No skills are waiting on security review right now."
        stats={[
          { value: '12', label: 'Approved · 7d', delta: '↑ 3' },
          { value: '2', label: 'Rejected · 7d' },
          { value: '1h 36m', label: 'Avg turnaround' },
        ]}
        auditLink={{ label: 'View full audit log', href: '/admin/audit' }}
      />,
    )
    expect(screen.getByText('All clear — nothing pending.')).toBeInTheDocument()
    expect(screen.getByText('12')).toBeInTheDocument()
    expect(screen.getByText('Approved · 7d')).toBeInTheDocument()
    expect(screen.getByText('1h 36m')).toBeInTheDocument()
    expect(screen.getByText('View full audit log')).toBeInTheDocument()
  })

  it('AC-5: optional fields omitted gracefully (sub/eyebrow/actions absent ok)', () => {
    render(<EmptyState tone="seed" headline="Bare seed state" />)
    expect(screen.getByText('Bare seed state')).toBeInTheDocument()
    // No crash / no prop errors when optional pieces missing
  })
})
