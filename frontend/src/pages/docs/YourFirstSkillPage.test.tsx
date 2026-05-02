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
    expect(screen.getByText('撰寫你的第一個技能')).toBeInTheDocument()
    // breadcrumb middle group label — DocsSidebar 與 breadcrumb 同字串會匹配多個
    expect(screen.getAllByText('入門').length).toBeGreaterThan(0)
    expect(screen.getByText('閱讀 5 分鐘')).toBeInTheDocument()
    expect(screen.getByText('依 agentskills.io v1.2')).toBeInTheDocument()
  })

  it('AC-2: renders all 6 main sections with anchor headings', () => {
    renderPage()
    // S098g 之後 DocsSidebar 與 section heading 部分字串重複 (Bundle 結構)；用 heading role + name regex (suffix #) scope 至 H2
    expect(screen.getByRole('heading', { level: 2, name: /最小可行技能/ })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: /Bundle 結構/ })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: /必填欄位/ })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: /撰寫有效的 description/ })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: /各風險層級的觸發條件/ })).toBeInTheDocument()
    // §6 CTA 標題用 <p> not <h2>，獨立 assertion
    expect(screen.getByText('準備發佈了嗎？')).toBeInTheDocument()
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
    const cta = screen.getByText('上傳你的 bundle')
    expect(cta).toBeInTheDocument()
    // closest <a> goes to /publish
    const link = cta.closest('a')
    expect(link).toHaveAttribute('href', '/publish')
  })
})
