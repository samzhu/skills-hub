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
  // S117: fileCount=3 為合理預設（多檔 zip）；驗 fileCount=0 graceful hide 場景由獨立 AC cover
  fileCount: 3,
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

  it('AC-S117-1: fileCount > 0 顯示「N 個檔案」', () => {
    renderWith([v({ fileCount: 5 })])
    expect(screen.getByText('5 個檔案')).toBeInTheDocument()
  })

  it('AC-S117-2: fileCount=0 (pre-S098a3-2 fallback) 隱藏該欄避免「0 個檔案」誤導', () => {
    renderWith([v({ fileCount: 0 })])
    expect(screen.queryByText(/個檔案/)).not.toBeInTheDocument()
  })
})
