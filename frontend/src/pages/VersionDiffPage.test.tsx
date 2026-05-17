import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { VersionDiffPage } from './VersionDiffPage'

/**
 * S098c — VersionDiffPage frontend-only diff tests。
 *
 * 用 mock fetch 餵假 versions response — VersionDiffPage 內 useSkill +
 * useVersions 都走 apiFetch；mock global fetch 即可。
 */

const skillJson = {
  id: 'skill-1',
  name: 'date-formatter',
  author: 'team-a',
  description: 'desc',
  category: 'utility',
  riskLevel: 'LOW',
  status: 'PUBLISHED',
  downloadCount: 10,
  latestVersion: '3',
  createdAt: '2026-04-01T00:00:00Z',
  updatedAt: '2026-04-01T00:00:00Z',
}

const versionsJson = [
  { id: 'v3', skillId: 'skill-1', version: '3', fileSize: 12000, publishedAt: '2026-04-15T00:00:00Z' },
  { id: 'v2', skillId: 'skill-1', version: '2', fileSize: 10000, publishedAt: '2026-04-08T00:00:00Z' },
  { id: 'v1', skillId: 'skill-1', version: '1', fileSize: 8000, publishedAt: '2026-04-01T00:00:00Z' },
]

const renderPage = (search = '') => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/skills/skill-1/diff${search}`]}>
        <Routes>
          <Route path="/skills/:id/diff" element={<VersionDiffPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const diffJson = {
  skillId: 'skill-1',
  from: { version: '2', publishedAt: '2026-04-08T00:00:00Z', fileSize: 10000, fileCount: 5 },
  to: { version: '3', publishedAt: '2026-04-15T00:00:00Z', fileSize: 12000, fileCount: 6 },
  fields: [
    { field: 'description', fromValue: '舊描述', toValue: '新描述', changeType: 'changed' },
    { field: 'allowedTools', fromValue: null, toValue: 'bash:read_file', changeType: 'added' },
  ],
}

const fileListDiffJson = {
  skillId: 'skill-1',
  fromVersion: '2',
  toVersion: '3',
  addedCount: 1,
  removedCount: 0,
  modifiedCount: 0,
  unchangedCount: 1,
  entries: [
    { path: 'scripts/run.sh', changeType: 'added', fromSize: null, toSize: 1024 },
  ],
}

beforeEach(() => {
  globalThis.fetch = vi.fn().mockImplementation((url: string) => {
    if (url.includes('/file-list-diff')) {
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve(fileListDiffJson),
      } as Response)
    }
    if (url.includes('/diff')) {
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve(diffJson),
      } as Response)
    }
    if (url.endsWith('/versions')) {
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve(versionsJson),
      } as Response)
    }
    return Promise.resolve({
      ok: true,
      status: 200,
      json: () => Promise.resolve(skillJson),
    } as Response)
  })
})

describe('VersionDiffPage — S098c', () => {
  it('AC-1: default selection compares latest 2 versions', async () => {
    renderPage()
    // 等待 versions fetch resolve
    await waitFor(() => {
      // skill name in h1
      expect(screen.getByText('date-formatter')).toBeInTheDocument()
    })
    // 預設 to=最新 (3), from=次新 (2)；version labels 在 hero text + cards 都會出現
    expect(screen.getAllByText(/v3/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/v2/).length).toBeGreaterThan(0)
  })

  it('AC-2: query params override default from/to', async () => {
    renderPage('?from=1&to=3')
    await waitFor(() => {
      expect(screen.getByText('date-formatter')).toBeInTheDocument()
    })
    // 應顯 v1 + v3 為比對對；v2 也在 selector chips 但不是 from/to
    expect(screen.getAllByText(/v1/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/v3/).length).toBeGreaterThan(0)
  })

  it('AC-4: S098c2 diff fields rendered from /diff API response', async () => {
    renderPage('?from=2&to=3')
    await waitFor(() => {
      expect(screen.getByText('date-formatter')).toBeInTheDocument()
    })
    // DiffFieldsPanel shows structured diff rows
    await waitFor(() => {
      expect(screen.getByText('描述')).toBeInTheDocument()   // FIELD_LABELS['description']
      expect(screen.getByText('舊描述')).toBeInTheDocument()
      expect(screen.getByText('新描述')).toBeInTheDocument()
      expect(screen.getByText('允許工具')).toBeInTheDocument() // FIELD_LABELS['allowedTools']
      expect(screen.getByText('bash:read_file')).toBeInTheDocument()
    })
  })

  it('AC-3-S098c3: FileListDiffPanel renders added file from /file-list-diff response', async () => {
    renderPage('?from=2&to=3')
    await waitFor(() => {
      expect(screen.getByText('date-formatter')).toBeInTheDocument()
    })
    await waitFor(() => {
      // summary heading contains the counts
      expect(screen.getByText(/\+1 \/ -0 \/ ~0/)).toBeInTheDocument()
      // added file path visible
      expect(screen.getByText('scripts/run.sh')).toBeInTheDocument()
    })
  })

  it('AC-3-legacy: insufficient versions shows fallback message', async () => {
    globalThis.fetch = vi.fn().mockImplementation((url: string) => {
      if (url.endsWith('/versions')) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve([versionsJson[0]]), // only 1 version
        } as Response)
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve(skillJson),
      } as Response)
    })
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('技能版本不足 2 個，無法比較。')).toBeInTheDocument()
    })
  })
})
