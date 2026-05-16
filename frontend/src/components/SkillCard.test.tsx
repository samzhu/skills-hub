import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { SkillCard } from './SkillCard'
import type { Skill } from '@/types/skill'

const mockSkill: Skill = {
  id: 'skill-001',
  name: 'k8s-helper',
  description: 'Kubernetes 排錯助理 — 自動分析 Pod 失敗、Service 未連通與 Ingress 設定錯誤的常見根因。',
  author: 'samzhu',
  category: '雲端維運',
  latestVersion: '0.3.1',
  riskLevel: 'LOW',
  status: 'PUBLISHED',
  visibility: 'PUBLIC',
  downloadCount: 42,
  averageRating: 0,
  reviewCount: 0,
  createdAt: '2026-04-01T00:00:00Z',
  updatedAt: '2026-04-20T00:00:00Z',
  verified: true,
  latestVersionPublishedAt: '2026-04-20T00:00:00Z',
  license: null,
  compatibility: [],
  versionCount: 1,
  openFlagCount: 0,
}

describe('AC-2: SkillCard 渲染', () => {
  // SkillCard 包 <Link> — 必 MemoryRouter wrap 解 router context
  const renderCard = (skill: Skill, score?: number) =>
    render(
      <MemoryRouter>
        <SkillCard skill={skill} score={score} />
      </MemoryRouter>,
    )

  it('顯示 skill 基本欄位（name / author / description / category）', () => {
    renderCard(mockSkill)
    expect(screen.getByText('k8s-helper')).toBeInTheDocument()
    expect(screen.getByText('samzhu')).toBeInTheDocument()
    expect(screen.getByText(/Kubernetes 排錯助理/)).toBeInTheDocument()
    expect(screen.getByText('雲端維運')).toBeInTheDocument()
  })

  it('latestVersion 顯示為 `v{semver}` 格式', () => {
    renderCard(mockSkill)
    expect(screen.getByText('v0.3.1')).toBeInTheDocument()
  })

  it('latestVersion 為 null 不渲染版本 span', () => {
    renderCard({ ...mockSkill, latestVersion: null })
    expect(screen.queryByText(/^v[\d.]+$/)).not.toBeInTheDocument()
  })

  it('Link 包覆整張卡片 → /skills/{id}', () => {
    renderCard(mockSkill)
    const link = screen.getByRole('link')
    expect(link).toHaveAttribute('href', '/skills/skill-001')
  })

  it('S159b AC-5: lowercase category 顯示時首字母大寫（"testing" → "Testing"）', () => {
    // DB V20 後 skills.category 一律 lowercase；UI 透過 capitalize helper 還原 display
    renderCard({ ...mockSkill, category: 'testing' })
    expect(screen.getByText('Testing')).toBeInTheDocument()
    expect(screen.queryByText('testing')).not.toBeInTheDocument()
  })

  it('S159b Round 2 AC-R2-5: 優先用 categoryDisplay 保留原 CamelCase（"DevOps"）', () => {
    // V21 dual-column 後 backend 回 categoryDisplay 原 case；frontend 直接顯示，不再 lossy capitalize
    renderCard({ ...mockSkill, category: 'devops', categoryDisplay: 'DevOps' })
    expect(screen.getByText('DevOps')).toBeInTheDocument()
    expect(screen.queryByText('Devops')).not.toBeInTheDocument()  // 確認沒走 fallback
  })

  it('條件 score badge — 傳入 score 顯示「XX% 相符」', () => {
    renderCard(mockSkill, 0.873)
    expect(screen.getByText('87% 相符')).toBeInTheDocument()
  })

  it('AC-1: 顯示 authorDisplayName 而非 raw author user_id (S154b)', () => {
    // 模擬 S154 backend 回 enriched author 欄位
    renderCard({
      ...mockSkill,
      author: 'u_a3f9c1',
      authorDisplayName: 'Alice Chen',
      authorHandle: 'alice',
    })
    // S154b §2.3：getDisplayName 五層 fallback priority 1 → authorDisplayName
    expect(screen.getByText('Alice Chen')).toBeInTheDocument()
    // 不顯示 raw user_id（user 看不懂的 platform 識別）
    expect(screen.queryByText('u_a3f9c1')).not.toBeInTheDocument()
  })

  it('條件 score badge — 不傳 score 不顯示相符 badge', () => {
    renderCard(mockSkill)
    expect(screen.queryByText(/% 相符/)).not.toBeInTheDocument()
  })
})
