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

  it('AC-S191-3: state=B explains HIGH risk scan result without manual review claims', () => {
    renderWith('?state=B&id=abc-123')
    expect(screen.getByRole('heading', { level: 1, name: '高風險掃描完成' })).toBeInTheDocument()
    expect(screen.getByText(/掃描偵測到 HIGH 級風險/)).toBeInTheDocument()
    expect(screen.getByText('abc-123')).toBeInTheDocument()
  })

  it('AC-S191-3: state=B does not render removed publication-review terms', () => {
    renderWith('?state=B&id=abc-123')

    const pageText = document.body.textContent ?? ''
    const removedTerms = ['已' + '送審', '人工審核' + '佇列', '審核員' + '核准', '24 ' + '小時', 'review' + 'er', 'app' + 'rove', 'rej' + 'ect']
    for (const removed of removedTerms) {
      expect(pageText).not.toContain(removed)
    }
  })

  it('AC-3 / S155 #3: 直訪無 context → EmptyState 引導回 /publish，不顯自相矛盾「0 error · 0 warning」', () => {
    renderWith('')
    expect(screen.getByText('沒有失敗紀錄可顯示')).toBeInTheDocument()
    const cta = screen.getByText('前往上傳').closest('a')
    expect(cta).toHaveAttribute('href', '/publish')
    // 不顯原本的 fallback 標題
    expect(screen.queryByRole('heading', { level: 1, name: '發佈未通過驗證' })).not.toBeInTheDocument()
  })

  it('AC-4: footer CTAs link to /publish + /browse（state=A 帶 msg → 走原 render path）', () => {
    renderWith('?state=A&msg=Missing%20field')
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
