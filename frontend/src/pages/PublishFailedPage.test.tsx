import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { PublishFailedPage } from './PublishFailedPage'
import type { ValidationFinding } from '@/types/skill'

/**
 * S098b — PublishFailedPage tone-aware tests。
 *
 * 此頁從 query string 取 state (A|B) + msg + id；State A 紅色 (frontmatter
 * error)；State B 橘色 (HIGH-risk submitted for review)；missing/invalid
 * state 預設 fallback A。
 *
 * AppShell 內含 useQuery for unread badge → 需 QueryClientProvider。
 */

const renderWith = (search: string, routerState?: unknown) => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[{ pathname: '/publish/failed', search, state: routerState }]}>
        <Routes>
          <Route path="/publish/failed" element={<PublishFailedPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('PublishFailedPage — S098b', () => {
  it('AC-1: state=A renders frontmatter validation error tone', () => {
    renderWith('?state=A&msg=Missing%20required%20field%3A%20name')
    expect(screen.getByRole('heading', { level: 1, name: '發佈未通過驗證' })).toBeInTheDocument()
    expect(screen.getByText('驗證在第 2 步停止 — 沒有任何資料寫入。')).toBeInTheDocument()
    // msg pre-block decoded
    expect(screen.getByText('Missing required field: name')).toBeInTheDocument()
  })

  it('AC-2: state=B renders HIGH-risk review tone with id echo', () => {
    renderWith('?state=B&id=abc-123')
    expect(screen.getByRole('heading', { level: 1, name: '高風險技能 — 已送審' })).toBeInTheDocument()
    expect(screen.getByText('技能掃出 HIGH 級風險 — 已寫入審核佇列。')).toBeInTheDocument()
    expect(screen.getByText('abc-123')).toBeInTheDocument()
  })

  it('AC-3: missing state defaults to A (validation error fallback)', () => {
    renderWith('')
    // 預期 fallback 為 State A — h1 為「發佈未通過驗證」
    expect(screen.getByRole('heading', { level: 1, name: '發佈未通過驗證' })).toBeInTheDocument()
  })

  it('AC-4: footer CTAs link to /publish + /browse', () => {
    renderWith('?state=A')
    const retry = screen.getByText('重新上傳').closest('a')
    expect(retry).toHaveAttribute('href', '/publish')
    const back = screen.getByText('返回瀏覽').closest('a')
    expect(back).toHaveAttribute('href', '/browse')
  })
})

describe('PublishFailedPage — S098b3-2 structured findings', () => {
  it('AC-S098b3-2-1: structured findings from router state render as individual ErrRows', () => {
    const findings: ValidationFinding[] = [
      { section: 'skill_md', severity: 'error', title: 'Missing required field: name', hint: null },
      { section: 'skill_md', severity: 'warning', title: 'description 建議超過 20 字', hint: '目前 12 字' },
    ]
    renderWith('?state=A&msg=fallback', { findings, msg: 'fallback' })
    expect(screen.getByText('Missing required field: name')).toBeInTheDocument()
    expect(screen.getByText('description 建議超過 20 字')).toBeInTheDocument()
    expect(screen.getByText('目前 12 字')).toBeInTheDocument()
    // flat msg fallback NOT shown when structured findings present
    expect(screen.queryByText('fallback')).not.toBeInTheDocument()
  })

  it('AC-S098b3-2-2: no router state → fallback to ?msg= URL param as single error row', () => {
    renderWith('?state=A&msg=Missing%20required%20field%3A%20name')
    expect(screen.getByText('Missing required field: name')).toBeInTheDocument()
  })
})
