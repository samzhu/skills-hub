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

  it('AC-S187-8: 手機寬度下主要編輯控制仍可見', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 390 })
    renderPage()

    await waitFor(() => {
      expect(screen.getByLabelText('SKILL.md 內容')).toHaveValue(latestSkillMd)
    })

    expect(screen.getByTestId('skill-edit-actions')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '取消' })).toBeVisible()
    expect(screen.getByRole('button', { name: '儲存分類' })).toBeVisible()
    expect(screen.getByRole('button', { name: '儲存新版本' })).toBeVisible()
    expect(screen.getByLabelText('分類')).toBeVisible()
    expect(screen.getByLabelText('版本號')).toBeVisible()
    expect(screen.getByLabelText('SKILL.md 內容')).toBeVisible()
  })
})

describe('SkillEditPage — S187 version submit flow', () => {
  it('AC-S195-1: edit upload mode shows drag/drop dropzone', async () => {
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: /上傳檔案/ }))

    expect(screen.getByText('拖拽 zip 或 md 檔到此處')).toBeInTheDocument()
    expect(screen.getByText('或點擊選取檔案')).toBeInTheDocument()
    expect(screen.getByLabelText('Skill 套件')).toHaveAttribute('id', 'skill-edit-file')
  })

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

  it('AC-S195-2: edit upload mode sends selected zip to PUT versions', async () => {
    let capturedUrl: string | null = null
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
        capturedUrl = url
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
    const file = new File(['zip-bytes'], 'handover.zip', { type: 'application/zip' })
    fireEvent.change(screen.getByLabelText('Skill 套件'), { target: { files: [file] } })
    fireEvent.click(screen.getByRole('button', { name: '儲存新版本' }))

    await waitFor(() => {
      expect(capturedForm).not.toBeNull()
    })
    expect(capturedUrl).toBe('/api/v1/skills/skill-docker/versions')
    expect((capturedForm!.get('file') as File).name).toBe('handover.zip')
  })

  it('AC-S195-5: invalid extension shows inline error and does not PUT', async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
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
    globalThis.fetch = fetchMock

    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: /上傳檔案/ }))
    const file = new File(['txt'], 'handover.txt', { type: 'text/plain' })
    fireEvent.change(screen.getByLabelText('Skill 套件'), { target: { files: [file] } })

    expect(screen.getByText('只接受 .zip / .md 檔，目前是 handover.txt')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '儲存新版本' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '儲存新版本' }))

    expect(fetchMock).not.toHaveBeenCalledWith(
      '/api/v1/skills/skill-docker/versions',
      expect.objectContaining({ method: 'PUT' }),
    )
  })

  it('AC-S195-3: edit page promotes first error finding title as primary validation error', async () => {
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
          error: 'VALIDATION_ERROR',
          message: 'SKILL.md validation failed',
          findings: [
            {
              section: 'skill_md',
              severity: 'warning',
              title: 'frontmatter_official_format: metadata.tags array accepted in compatibility mode',
              hint: null,
            },
            {
              section: 'skill_md',
              severity: 'error',
              title: "metadata: key 'owner' nested object is not supported",
              hint: '請把 metadata.owner 改成純字串。',
            },
          ],
        }), {
          status: 400,
          headers: { 'Content-Type': 'application/json' },
        }))
      }
      return Promise.resolve(new Response(JSON.stringify({}), {
        status: 404,
        headers: { 'Content-Type': 'application/json' },
      }))
    })

    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: /上傳檔案/ }))
    fireEvent.change(screen.getByLabelText('Skill 套件'), {
      target: { files: [new File(['zip'], 'handover.zip', { type: 'application/zip' })] },
    })
    fireEvent.click(screen.getByRole('button', { name: '儲存新版本' }))

    await waitFor(() => {
      expect(screen.getByText("metadata: key 'owner' nested object is not supported")).toBeInTheDocument()
    })
    expect(screen.getByText('儲存新版本失敗：SKILL.md validation failed')).toBeInTheDocument()
    expect(screen.getByText("error · skill_md · metadata: key 'owner' nested object is not supported")).toBeInTheDocument()
    expect(screen.getByText('請把 metadata.owner 改成純字串。')).toBeInTheDocument()
    expect(screen.getByText(/warning · skill_md · frontmatter_official_format/)).toBeInTheDocument()
  })

  it('AC-S195-3: edit page falls back to localized message when findings are missing', async () => {
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

    fireEvent.click(await screen.findByRole('button', { name: /上傳檔案/ }))
    fireEvent.change(screen.getByLabelText('Skill 套件'), {
      target: { files: [new File(['zip'], 'handover.zip', { type: 'application/zip' })] },
    })
    fireEvent.click(screen.getByRole('button', { name: '儲存新版本' }))

    await waitFor(() => {
      expect(screen.getByText('儲存新版本失敗')).toBeInTheDocument()
    })
    expect(screen.getByText('此版本號已存在，請改用其他版本號。')).toBeInTheDocument()
    expect(screen.queryByText(/error · skill_md/)).not.toBeInTheDocument()
  })

  it('AC-S195-6: mobile upload mode keeps dropzone and primary actions visible', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 390 })
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: /上傳檔案/ }))

    expect(screen.getByText('拖拽 zip 或 md 檔到此處')).toBeVisible()
    expect(screen.getByText('或點擊選取檔案')).toBeVisible()
    expect(screen.getByRole('link', { name: '取消' })).toBeVisible()
    expect(screen.getByRole('button', { name: '儲存分類' })).toBeVisible()
    expect(screen.getByRole('button', { name: '儲存新版本' })).toBeVisible()
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
