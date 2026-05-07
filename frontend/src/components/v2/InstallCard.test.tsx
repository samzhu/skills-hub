import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { InstallCard } from './InstallCard'
import type { Skill } from '@/types/skill'

const baseSkill: Skill = {
  id: 's1', name: 'my-skill', description: 'A skill', author: 'alice',
  category: 'AI', latestVersion: '1.0.0', riskLevel: 'LOW',
  status: 'PUBLISHED', downloadCount: 0, averageRating: 0, reviewCount: 0,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
  verified: false, latestVersionPublishedAt: null,
  license: null, compatibility: [], versionCount: 1, openFlagCount: 0,
}

describe('InstallCard', () => {
  it('AC-S142a-16: renders install command with author/name', () => {
    render(<InstallCard skill={baseSkill} />)
    expect(screen.getByText('skills-hub install alice/my-skill')).toBeTruthy()
  })

  it('AC-S142a-16: copy button calls clipboard.writeText', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })
    render(<InstallCard skill={baseSkill} />)
    fireEvent.click(screen.getByTestId('install-copy-btn'))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('skills-hub install alice/my-skill'))
  })

  it('AC-S142a-16: after copy, button shows checkmark', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })
    render(<InstallCard skill={baseSkill} />)
    fireEvent.click(screen.getByTestId('install-copy-btn'))
    await waitFor(() => expect(screen.getByTestId('install-copy-btn').textContent).toBe('✓'))
  })

  it('AC-S142a-16: "What are skills?" link present', () => {
    render(<InstallCard skill={baseSkill} />)
    const link = screen.getByText('What are skills?') as HTMLAnchorElement
    expect(link).toBeTruthy()
    expect(link.href).toContain('/docs/your-first-skill')
  })

  it('AC-S142a-16: CLI label visible', () => {
    render(<InstallCard skill={baseSkill} />)
    expect(screen.getByText('CLI ▼')).toBeTruthy()
  })
})
