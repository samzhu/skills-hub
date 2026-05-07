import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { EmptyState } from './EmptyState'

const wrap = (ui: React.ReactElement) => render(<MemoryRouter>{ui}</MemoryRouter>)

describe('EmptyState — S094c 4 tones', () => {
  it('AC-1: seed tone renders eyebrow + headline + sub + ghost preview', () => {
    wrap(
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

  it("AC-2 (S105): invite tone WITHOUT steps prop → 4-step strip 不顯", () => {
    render(
      <EmptyState
        tone="invite"
        headline="You haven't published anything yet."
        sub="Ship something you've built to the team."
      />,
    )
    expect(screen.getByText("You haven't published anything yet.")).toBeInTheDocument()
    // S105: 預設不顯 4-step strip — context 不對齊時 (Reviews / Collections / Requests / Search) 應乾淨
    expect(screen.queryByText('打包')).not.toBeInTheDocument()
    expect(screen.queryByText('自動掃描')).not.toBeInTheDocument()
    expect(screen.queryByText('發佈')).not.toBeInTheDocument()
    expect(screen.queryByText('追蹤')).not.toBeInTheDocument()
  })

  it("AC-S105: invite tone WITH steps prop opt-in → 4-step strip 顯（既有 publish onboarding visual 不變）", () => {
    render(
      <EmptyState
        tone="invite"
        headline="你還沒有發布過技能。"
        sub="完整 round-trip 通常少於 1 分鐘。"
        steps={['打包', '自動掃描', '發佈', '追蹤']}
      />,
    )
    expect(screen.getByText('打包')).toBeInTheDocument()
    expect(screen.getByText('自動掃描')).toBeInTheDocument()
    expect(screen.getByText('發佈')).toBeInTheDocument()
    expect(screen.getByText('追蹤')).toBeInTheDocument()
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
    // S098h2: i18n - "Query ·" → "查詢 ·"
    expect(screen.getByText('查詢 ·')).toBeInTheDocument()
    expect(screen.getByText('"crypto wallet integration for embedded devices"')).toBeInTheDocument()
    expect(screen.getByText("We don't have a skill for that yet.")).toBeInTheDocument()
    expect(screen.getByText('Try "wallet signature verification"')).toBeInTheDocument()
    expect(screen.getByText('Browse all 247 skills')).toBeInTheDocument()
  })

  it('AC-4: clear tone renders check icon + headline + stats + audit link', () => {
    wrap(
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
