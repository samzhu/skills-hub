import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { YourFirstSkillPage } from './YourFirstSkillPage'

describe('YourFirstSkillPage — S094d', () => {
  // S096h1: AppShell bell badge 用 useQuery，需 QueryClientProvider context.
  const renderPage = () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    return render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/docs/your-first-skill']}>
          <YourFirstSkillPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )
  }

  it('AC-1: renders title + breadcrumbs + meta row', () => {
    renderPage()
    expect(screen.getByText('Write your first skill')).toBeInTheDocument()
    expect(screen.getByText('Getting started')).toBeInTheDocument() // breadcrumb middle
    expect(screen.getByText('5 min read')).toBeInTheDocument()
    expect(screen.getByText('Based on agentskills.io v1.2')).toBeInTheDocument()
  })

  it('AC-2: renders all 6 main sections with anchor headings', () => {
    renderPage()
    expect(screen.getByText('The minimum viable skill')).toBeInTheDocument()
    expect(screen.getByText('The bundle')).toBeInTheDocument()
    expect(screen.getByText('Required fields')).toBeInTheDocument()
    expect(screen.getByText('Writing a description that works')).toBeInTheDocument()
    expect(screen.getByText('What triggers each risk tier')).toBeInTheDocument()
    expect(screen.getByText('Ready to publish?')).toBeInTheDocument()
  })

  it('AC-3: renders 3 risk tiers (LOW/MEDIUM/HIGH) with semantic styling', () => {
    renderPage()
    expect(screen.getByText('LOW')).toBeInTheDocument()
    expect(screen.getByText('MEDIUM')).toBeInTheDocument()
    expect(screen.getByText('HIGH')).toBeInTheDocument()
  })

  it('AC-4: renders required fields (name + description) with tags', () => {
    renderPage()
    // FieldCard 顯示 name 與 description 兩個 required field — 文字會出現多次（code block + FieldCard），
    // 用 length>0 確認存在；tags 用具體值斷言
    expect(screen.getAllByText('name').length).toBeGreaterThan(0)
    expect(screen.getAllByText('description').length).toBeGreaterThan(0)
    expect(screen.getAllByText('required').length).toBe(2) // FieldCard ×2
    expect(screen.getAllByText('≤ 64 chars').length).toBe(1)
    expect(screen.getAllByText('≤ 1024 chars').length).toBe(1)
  })

  it('AC-5: renders Upload your bundle CTA linking to /publish', () => {
    renderPage()
    const cta = screen.getByText('Upload your bundle')
    expect(cta).toBeInTheDocument()
    // closest <a> goes to /publish
    const link = cta.closest('a')
    expect(link).toHaveAttribute('href', '/publish')
  })
})
