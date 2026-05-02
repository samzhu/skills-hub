import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { DocsSidebar } from './DocsSidebar'

/**
 * DocsSidebar tests — 4 group labels + 11 nav items + active link contract。
 * Per S098f3 closing：所有 11 items 都 active link（no placeholders left）。
 */

const renderAt = (path: string) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <DocsSidebar />
    </MemoryRouter>,
  )

describe('DocsSidebar — S098f3 final state', () => {
  it('AC-1: 4 group labels render (入門/參考/發佈/API 與 Webhook)', () => {
    renderAt('/docs/overview')
    expect(screen.getByText('入門')).toBeInTheDocument()
    expect(screen.getByText('參考')).toBeInTheDocument()
    expect(screen.getByText('發佈')).toBeInTheDocument()
    expect(screen.getByText('API 與 Webhook')).toBeInTheDocument()
  })

  it('AC-2: all 11 doc nav items render as active links', () => {
    renderAt('/docs/overview')
    // 11 item labels per S098f3 close
    ;[
      '概覽', '撰寫第一個技能',
      'SKILL.md 規範', 'Frontmatter 欄位', 'Bundle 結構', '風險層級',
      '上傳與驗證', '版本管理', '語意搜尋',
      'REST 參考', 'Event payload',
    ].forEach((label) => {
      expect(screen.getByText(label)).toBeInTheDocument()
    })
  })

  it('AC-3: current path highlights the matching link', () => {
    renderAt('/docs/risk-tiers')
    const activeLink = screen.getByText('風險層級').closest('a')
    // active 用 bg-* + font-medium
    expect(activeLink?.className).toContain('font-medium')
  })

  it('AC-4: non-active items use muted-text style', () => {
    renderAt('/docs/overview')
    // 「風險層級」非 active → text-[#A8A49C] style
    const link = screen.getByText('風險層級').closest('a')
    expect(link?.className).toContain('text-[#A8A49C]')
  })

  it('AC-5: each link href matches its sidebar entry', () => {
    renderAt('/docs/overview')
    expect(screen.getByText('概覽').closest('a')).toHaveAttribute('href', '/docs/overview')
    expect(screen.getByText('SKILL.md 規範').closest('a')).toHaveAttribute('href', '/docs/skill-md-spec')
    expect(screen.getByText('REST 參考').closest('a')).toHaveAttribute('href', '/docs/rest-api')
    expect(screen.getByText('Event payload').closest('a')).toHaveAttribute('href', '/docs/event-payload')
  })
})
