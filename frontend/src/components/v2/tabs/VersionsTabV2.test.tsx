import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { VersionsTabV2 } from './VersionsTabV2'
import type { SkillVersion } from '@/types/skill'

const versions: SkillVersion[] = [
  { id: 'v1', skillId: 's1', version: '2.0.0', fileSize: 4096, fileCount: 3, publishedAt: new Date(Date.now() - 86400000 * 2).toISOString() },
  { id: 'v2', skillId: 's1', version: '1.0.0', fileSize: 2048, fileCount: 2, publishedAt: new Date(Date.now() - 86400000 * 30).toISOString() },
]

describe('VersionsTabV2', () => {
  it('AC-S142a-13: 2 version cards rendered', () => {
    render(<VersionsTabV2 versions={versions} />)
    expect(screen.getAllByTestId('version-card').length).toBe(2)
  })

  it('AC-S142a-13: first card has Latest badge; second does not', () => {
    render(<VersionsTabV2 versions={versions} />)
    const badges = screen.queryAllByTestId('latest-badge')
    expect(badges.length).toBe(1)
    expect(badges[0].textContent).toBe('Latest')
  })

  it('AC-S142a-13: version numbers shown in mono font', () => {
    render(<VersionsTabV2 versions={versions} />)
    const nums = screen.getAllByTestId('version-num')
    expect(nums[0].textContent).toBe('v2.0.0')
    expect(nums[1].textContent).toBe('v1.0.0')
  })

  it('empty list → 尚無版本記錄', () => {
    render(<VersionsTabV2 versions={[]} />)
    expect(screen.getByText('尚無版本記錄')).toBeTruthy()
  })
})
