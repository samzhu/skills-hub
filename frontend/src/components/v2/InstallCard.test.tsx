import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
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
    render(<MemoryRouter><InstallCard skill={baseSkill} /></MemoryRouter>)
    expect(screen.getByText('skills-hub install alice/my-skill')).toBeTruthy()
  })

  it('AC-2: 使用 authorHandle 為 install command segment（不用 raw user_id）', () => {
    // S154 backend 提供 authorHandle；install command 應走 user-facing slug
    const skillWithHandle = { ...baseSkill, author: 'u_a3f9c1', authorHandle: 'alice' }
    render(<MemoryRouter><InstallCard skill={skillWithHandle} /></MemoryRouter>)
    expect(screen.getByText('skills-hub install alice/my-skill')).toBeTruthy()
    // 不該出現 raw user_id segment
    expect(screen.queryByText(/skills-hub install u_a3f9c1/)).toBeNull()
  })

  it('AC-2: handle 缺時 fallback 用 user_id（不顯 raw sub）', () => {
    // Edge case：user row 無 handle（極罕見）→ fallback user_id
    const skillNoHandle = { ...baseSkill, author: 'u_a3f9c1', authorHandle: null }
    render(<MemoryRouter><InstallCard skill={skillNoHandle} /></MemoryRouter>)
    expect(screen.getByText('skills-hub install u_a3f9c1/my-skill')).toBeTruthy()
  })

  it('AC-S142a-16: copy button calls clipboard.writeText', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })
    render(<MemoryRouter><InstallCard skill={baseSkill} /></MemoryRouter>)
    fireEvent.click(screen.getByTestId('install-copy-btn'))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('skills-hub install alice/my-skill'))
  })

  it('AC-S142a-16: after copy, button shows checkmark', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })
    render(<MemoryRouter><InstallCard skill={baseSkill} /></MemoryRouter>)
    fireEvent.click(screen.getByTestId('install-copy-btn'))
    await waitFor(() => expect(screen.getByTestId('install-copy-btn').textContent).toBe('✓'))
  })

  it('AC-S142a-16: "什麼是技能？" link present', () => {
    render(<MemoryRouter><InstallCard skill={baseSkill} /></MemoryRouter>)
    const link = screen.getByText('什麼是技能？') as HTMLAnchorElement
    expect(link).toBeTruthy()
    expect(link.href).toContain('/docs/your-first-skill')
  })

  it('AC-S142a-16 / S155 #7: CLI label 純文字 — 不帶 ▼ 假 dropdown 暗示', () => {
    render(<MemoryRouter><InstallCard skill={baseSkill} /></MemoryRouter>)
    expect(screen.getByText('Skills Hub CLI')).toBeTruthy()
    expect(screen.queryByText(/▼/)).toBeNull()
  })
})
