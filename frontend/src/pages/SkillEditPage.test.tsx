import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { SkillEditPage } from './SkillEditPage'

const latestSkillMd = `---
name: docker-helper
description: Generate and validate Docker Compose files
---

# Docker Helper

Use when editing compose.yml.
`

const skillFixture = {
  id: 'skill-docker',
  name: 'Docker Helper',
  description: 'Generate and validate Docker Compose files',
  author: 'alice',
  category: 'DevOps',
  status: 'PUBLISHED',
  visibility: 'PUBLIC',
  latestVersion: '2.1.0',
  downloadCount: 12,
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-02T00:00:00Z',
  riskLevel: 'LOW',
  verified: true,
  latestVersionPublishedAt: '2024-01-02T00:00:00Z',
  license: 'MIT',
  compatibility: [],
  versionCount: 3,
  openFlagCount: 0,
  averageRating: 0,
  reviewCount: 0,
  ownerId: 'alice',
  viewerPermissions: {
    isOwner: true,
    canView: true,
    canDownload: true,
    canEdit: true,
    canDelete: true,
    canShare: true,
    canManageGrants: true,
  },
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/skills/skill-docker/edit']}>
        <Routes>
          <Route path="/skills/:id/edit" element={<SkillEditPage />} />
          <Route path="/publish/validate" element={<div>REDIRECTED_TO_VERSION_VALIDATE</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  globalThis.fetch = vi.fn().mockImplementation((input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input.toString()
    if (url === '/api/v1/skills/skill-docker') {
      return Promise.resolve(new Response(JSON.stringify(skillFixture), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }))
    }
    if (url === '/api/v1/skills/skill-docker/files/SKILL.md') {
      return Promise.resolve(new Response(latestSkillMd, {
        status: 200,
        headers: { 'Content-Type': 'text/markdown' },
      }))
    }
    return Promise.resolve(new Response(JSON.stringify({}), {
      status: 404,
      headers: { 'Content-Type': 'application/json' },
    }))
  })
})

describe('SkillEditPage — S187 edit page text mode', () => {
  it('AC-S187-3: edit page 貼上文本 mode 預填 latest SKILL.md', async () => {
    renderPage()

    await waitFor(() => {
      expect(screen.getByLabelText('SKILL.md 內容')).toHaveValue(latestSkillMd)
    })

    expect(screen.getByRole('button', { name: /貼上文本/ })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTestId('frontmatter-name-check')).toHaveTextContent('name 已通過')
    expect(screen.getByTestId('frontmatter-description-check')).toHaveTextContent('description 已通過')
    expect(screen.getByRole('button', { name: '儲存新版本' })).toBeEnabled()
  })

  it('AC-S187-3: 缺 description 時儲存新版本 disabled', async () => {
    renderPage()

    const textarea = await screen.findByLabelText('SKILL.md 內容')
    fireEvent.change(textarea, {
      target: {
        value: `---
name: docker-helper
---

# Docker Helper
`,
      },
    })

    expect(screen.getByTestId('frontmatter-description-check')).toHaveTextContent('description 缺少')
    expect(screen.getByRole('button', { name: '儲存新版本' })).toBeDisabled()
  })
})

describe('SkillEditPage — S187 version submit flow', () => {
  it('AC-S187-4: upload mode 建立新版本後進驗證中', async () => {
    let capturedForm: FormData | null = null
    globalThis.fetch = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString()
      if (url === '/api/v1/skills/skill-docker') {
        return Promise.resolve(new Response(JSON.stringify(skillFixture), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }))
      }
      if (url === '/api/v1/skills/skill-docker/files/SKILL.md') {
        return Promise.resolve(new Response(latestSkillMd, {
          status: 200,
          headers: { 'Content-Type': 'text/markdown' },
        }))
      }
      if (url === '/api/v1/skills/skill-docker/versions' && init?.method === 'PUT') {
        capturedForm = init.body as FormData
        return Promise.resolve(new Response(null, { status: 204 }))
      }
      return Promise.resolve(new Response(JSON.stringify({}), {
        status: 404,
        headers: { 'Content-Type': 'application/json' },
      }))
    })

    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: /上傳檔案/ }))
    const file = new File(['zip-bytes'], 'skill.zip', { type: 'application/zip' })
    fireEvent.change(screen.getByLabelText('Skill 套件'), { target: { files: [file] } })
    fireEvent.change(screen.getByLabelText('版本號'), { target: { value: '1.1.0' } })
    fireEvent.click(screen.getByRole('button', { name: '儲存新版本' }))

    await waitFor(() => {
      expect(capturedForm).not.toBeNull()
    })
    expect(capturedForm!.get('file')).toBe(file)
    expect(capturedForm!.get('version')).toBe('1.1.0')
    await waitFor(() => {
      expect(screen.getByText('REDIRECTED_TO_VERSION_VALIDATE')).toBeInTheDocument()
    })
  })

  it('AC-S187-7: duplicate version 不覆寫舊版本且留在 edit page', async () => {
    globalThis.fetch = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString()
      if (url === '/api/v1/skills/skill-docker') {
        return Promise.resolve(new Response(JSON.stringify(skillFixture), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }))
      }
      if (url === '/api/v1/skills/skill-docker/files/SKILL.md') {
        return Promise.resolve(new Response(latestSkillMd, {
          status: 200,
          headers: { 'Content-Type': 'text/markdown' },
        }))
      }
      if (url === '/api/v1/skills/skill-docker/versions' && init?.method === 'PUT') {
        return Promise.resolve(new Response(JSON.stringify({
          error: 'VERSION_EXISTS',
          message: 'Version already exists',
        }), {
          status: 409,
          headers: { 'Content-Type': 'application/json' },
        }))
      }
      return Promise.resolve(new Response(JSON.stringify({}), {
        status: 404,
        headers: { 'Content-Type': 'application/json' },
      }))
    })

    renderPage()

    fireEvent.change(await screen.findByLabelText('SKILL.md 內容'), {
      target: { value: latestSkillMd },
    })
    fireEvent.change(screen.getByLabelText('版本號'), { target: { value: '2.1.0' } })
    fireEvent.click(screen.getByRole('button', { name: '儲存新版本' }))

    await waitFor(() => {
      expect(screen.getByText('此版本號已存在，請改用其他版本號。')).toBeInTheDocument()
    })
    expect(screen.getByRole('heading', { name: '編輯 SKILL.md' })).toBeInTheDocument()
    expect(screen.queryByText('REDIRECTED_TO_VERSION_VALIDATE')).not.toBeInTheDocument()
  })
})

describe('SkillEditPage — S187 category update', () => {
  it('AC-S187-9: edit page 可更新 category 且 request body 不含 description', async () => {
    let capturedBody: string | null = null
    globalThis.fetch = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString()
      if (url === '/api/v1/skills/skill-docker' && init?.method === 'PUT') {
        capturedBody = init.body as string
        return Promise.resolve(new Response(null, { status: 204 }))
      }
      if (url === '/api/v1/skills/skill-docker') {
        return Promise.resolve(new Response(JSON.stringify(skillFixture), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }))
      }
      if (url === '/api/v1/skills/skill-docker/files/SKILL.md') {
        return Promise.resolve(new Response(latestSkillMd, {
          status: 200,
          headers: { 'Content-Type': 'text/markdown' },
        }))
      }
      return Promise.resolve(new Response(JSON.stringify({}), {
        status: 404,
        headers: { 'Content-Type': 'application/json' },
      }))
    })

    renderPage()

    const categoryInput = await screen.findByLabelText('分類')
    fireEvent.change(categoryInput, { target: { value: 'Platform Tools' } })
    fireEvent.click(screen.getByRole('button', { name: '儲存分類' }))

    await waitFor(() => {
      expect(capturedBody).not.toBeNull()
    })
    expect(JSON.parse(capturedBody!)).toEqual({ category: 'Platform Tools' })
  })
})
