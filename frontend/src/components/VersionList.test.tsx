import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { VersionList } from './VersionList'
import type { SkillVersion } from '@/types/skill'

/**
 * VersionList tests — 對齊 docs/grimo/test-cases.md Round 5.5。
 * 「比較版本變化」link 出現條件：versions ≥ 2；href 指向 /skills/:skillId/diff。
 */

const v = (overrides: Partial<SkillVersion> = {}): SkillVersion => ({
  id: 'v-id',
  skillId: 'skill-1',
  version: '1.0.0',
  fileSize: 8000,
  publishedAt: '2026-04-01T00:00:00Z',
  ...overrides,
})

const renderWith = (versions: SkillVersion[]) =>
  render(
    <MemoryRouter>
      <VersionList versions={versions} />
    </MemoryRouter>,
  )

describe('VersionList — ledger Round 5.5', () => {
  it('AC-1: empty versions shows fallback「尚無版本記錄」', () => {
    renderWith([])
    expect(screen.getByText('尚無版本記錄')).toBeInTheDocument()
    expect(screen.queryByText('比較版本變化')).not.toBeInTheDocument()
  })

  it('AC-2: single version does NOT render diff link (need 2+)', () => {
    renderWith([v({ version: '1.0.0' })])
    expect(screen.getByText('v1.0.0')).toBeInTheDocument()
    expect(screen.queryByText('比較版本變化')).not.toBeInTheDocument()
  })

  it('AC-3: multiple versions renders diff link with correct href', () => {
    renderWith([
      v({ id: 'v2', version: '1.1.0', publishedAt: '2026-04-08T00:00:00Z' }),
      v({ id: 'v1', version: '1.0.0' }),
    ])
    const link = screen.getByText('比較版本變化').closest('a')
    expect(link).toHaveAttribute('href', '/skills/skill-1/diff')
  })

  it('AC-4: latest version (index 0) gets「最新」badge', () => {
    renderWith([
      v({ id: 'v2', version: '1.1.0', publishedAt: '2026-04-08T00:00:00Z' }),
      v({ id: 'v1', version: '1.0.0' }),
    ])
    expect(screen.getByText('最新')).toBeInTheDocument()
  })

  it('AC-5: download link points to versioned download endpoint', () => {
    renderWith([v({ version: '2.0.0' })])
    const downloadLink = screen.getByText('下載').closest('a')
    expect(downloadLink).toHaveAttribute('href', '/api/v1/skills/skill-1/versions/2.0.0/download')
  })
})
