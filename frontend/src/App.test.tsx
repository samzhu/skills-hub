import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { NotFoundPage } from './pages/NotFoundPage'

/**
 * 此測試只驗 NotFoundPage 本身渲染合約，不 mount 整個 <App />，
 * 因為 HomePage / SkillDetailPage 會觸發 React Query + window.matchMedia（border-beam）
 * — 那些 mocking 屬其他測試的範圍。
 */
describe('AC-1: NotFoundPage', () => {
  it('render 「404」與回首頁連結', () => {
    render(
      <MemoryRouter initialEntries={['/bogus']}>
        <Routes>
          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </MemoryRouter>,
    )
    expect(screen.getByText('404')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '回到首頁' })).toHaveAttribute('href', '/')
  })
})
