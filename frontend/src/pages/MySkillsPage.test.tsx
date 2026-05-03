import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MySkillsPage } from './MySkillsPage'

// S110 — verify MySkillsPage user-facing labels 全 zh-TW（per CLAUDE.md「UI 語言: 繁體中文」）

const renderPage = () => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/my-skills']}>
        <Routes>
          <Route path="/my-skills" element={<MySkillsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('MySkillsPage — S110 zh-TW label compliance', () => {
  it('AC-1: 4 個 metric cards 用 zh-TW labels', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('技能總數')).toBeInTheDocument()
    })
    expect(screen.getByText('下載總數')).toBeInTheDocument()
    expect(screen.getByText('平均評分')).toBeInTheDocument()
    expect(screen.getByText('待處理回報')).toBeInTheDocument()
  })

  it('AC-2: status subtitle 不再含 published/draft/suspended 英文 token', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.queryByText(/published.*draft.*suspended/)).not.toBeInTheDocument()
    })
  })

  it('AC-3: 既有 English labels 已完全移除（regression guard）', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.queryByText('Total skills')).not.toBeInTheDocument()
    })
    expect(screen.queryByText('Total downloads')).not.toBeInTheDocument()
    expect(screen.queryByText('Avg rating')).not.toBeInTheDocument()
    expect(screen.queryByText('Open flags')).not.toBeInTheDocument()
  })
})
