import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { PublishPage } from './PublishPage'
import { validateFrontmatter } from './PublishPage.utils'
import * as useMeModule from '@/hooks/useMe'
import * as useAuthModule from '@/hooks/useAuth'

/**
 * S099b2 — validateFrontmatter pure-function tests。
 * Cover positive / negative (3+ types) / edge cases per S099 testing methodology
 * (3-5 反例 / round)。
 */

vi.mock('@/hooks/useMe')
vi.mock('@/hooks/useAuth')

describe('validateFrontmatter — S099b2', () => {
  // Positive
  it('AC-1: valid frontmatter with name + description passes', () => {
    const r = validateFrontmatter('---\nname: my-skill\ndescription: A useful skill\n---\n# body')
    expect(r.hasFrontmatter).toBe(true)
    expect(r.hasName).toBe(true)
    expect(r.hasDescription).toBe(true)
    expect(r.errors).toEqual([])
  })

  // Negative — empty
  it('AC-2 (negative empty): empty content yields all-false + no errors', () => {
    const r = validateFrontmatter('')
    expect(r.hasFrontmatter).toBe(false)
    expect(r.hasName).toBe(false)
    expect(r.hasDescription).toBe(false)
    expect(r.errors).toEqual([])
  })

  // Negative — no frontmatter delimiter
  it('AC-3 (negative format): content without leading --- shows frontmatter error', () => {
    const r = validateFrontmatter('# Just markdown\nno frontmatter')
    expect(r.hasFrontmatter).toBe(false)
    expect(r.errors).toContain('SKILL.md 必須以 YAML frontmatter 開頭（首行 ---）')
  })

  // Negative — missing closing delimiter
  it('AC-4 (negative format): missing closing --- shows error', () => {
    const r = validateFrontmatter('---\nname: x\ndescription: y\n# body without closing')
    expect(r.hasFrontmatter).toBe(false)
    expect(r.errors).toContain('Frontmatter 缺少結束 ---（需在第 N 行單獨一個 ---）')
  })

  // Negative — missing required field name
  it('AC-5 (negative missing field): missing name yields field error', () => {
    const r = validateFrontmatter('---\ndescription: only desc\n---\n')
    expect(r.hasFrontmatter).toBe(true)
    expect(r.hasName).toBe(false)
    expect(r.hasDescription).toBe(true)
    expect(r.errors).toContain('缺必填欄位：name')
  })

  // Negative — missing required field description
  it('AC-6 (negative missing field): missing description yields field error', () => {
    const r = validateFrontmatter('---\nname: only-name\n---\n')
    expect(r.hasFrontmatter).toBe(true)
    expect(r.hasName).toBe(true)
    expect(r.hasDescription).toBe(false)
    expect(r.errors).toContain('缺必填欄位：description')
  })

  // Edge — name with empty value
  it('AC-7 (edge): name with empty value treated as missing', () => {
    const r = validateFrontmatter('---\nname:\ndescription: x\n---\n')
    expect(r.hasName).toBe(false)
    expect(r.errors).toContain('缺必填欄位：name')
  })

  // Edge — extra whitespace before delimiter
  it('AC-8 (edge): leading whitespace before --- accepted (after trim)', () => {
    const r = validateFrontmatter('   \n---\nname: x\ndescription: y\n---\n')
    expect(r.hasFrontmatter).toBe(true)
    expect(r.hasName).toBe(true)
  })
})

/**
 * S154b — PublishPage 作者欄位 read-only display test。
 *
 * LAB user 反映原本 input 顯示 raw OAuth sub `116549129985546340268`，
 * 且可改但 server silent ignore（per S154 backend §2.5 forge fix）。T05 把
 * 作者欄位改為 read-only display + uploadSkill signature drop author param，
 * 確保 UI 不再誤導使用者。
 */
const mockUseMe = vi.mocked(useMeModule.useMe)
const mockUseAuth = vi.mocked(useAuthModule.useAuth)

const renderPublishPage = () => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/publish']}>
        <PublishPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('PublishPage — S154b 作者欄位 read-only', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseMe.mockReturnValue({
      data: {
        userId: 'u_a3f9c1',
        handle: 'alice',
        sub: '116549129985546340268',
        email: 'alice@example.com',
        name: 'Alice Chen',
        roles: [],
        groups: [],
        companyId: null,
        deptId: null,
        scope: '',
        picture: null,
      },
      isLoading: false,
      isError: false,
      error: null,
    } as any)
    mockUseAuth.mockReturnValue({
      status: 'authenticated',
      user: {
        userId: 'u_a3f9c1',
        handle: 'alice',
        sub: '116549129985546340268',
        email: 'alice@example.com',
        name: 'Alice Chen',
      },
      login: vi.fn(),
      logout: vi.fn(),
    } as ReturnType<typeof useAuthModule.useAuth>)
  })

  it('AC-10: 作者欄位非 input、顯示 displayName + @handle、無「代發名稱」誤導文字', () => {
    renderPublishPage()
    // 作者欄位不是 textbox / input — 原 <Input id="publish-author"> 已被拔
    expect(screen.queryByRole('textbox', { name: /作者/ })).toBeNull()
    // 顯示 priority chain 第 1 順位 name
    expect(screen.getByText('Alice Chen')).toBeInTheDocument()
    // 顯示 handle chip
    expect(screen.getByText('@alice')).toBeInTheDocument()
    // 移除誤導文字
    expect(screen.queryByText(/代發名稱/)).toBeNull()
  })

  it('AC-S179-4: 已登入作者顯示維持 S154b 行為', () => {
    renderPublishPage()

    const authorDisplay = screen.getByTestId('publish-author-display')
    expect(authorDisplay).toHaveTextContent('Alice Chen')
    expect(authorDisplay).toHaveTextContent('@alice')
    expect(screen.queryByRole('textbox', { name: /作者/ })).toBeNull()
  })

  it('AC-S179-1: 未登入作者欄位顯示登入提示', () => {
    mockUseAuth.mockReturnValue({
      status: 'anonymous',
      login: vi.fn(),
      logout: vi.fn(),
    } as ReturnType<typeof useAuthModule.useAuth>)
    mockUseMe.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: false,
      error: null,
    } as any)

    renderPublishPage()

    const authorDisplay = screen.getByTestId('publish-author-display')
    expect(authorDisplay).toHaveTextContent('請先登入後發布')
    expect(authorDisplay).not.toHaveTextContent('@undefined')
  })

  it('AC-S179-3: 登入中不顯空白作者', () => {
    mockUseAuth.mockReturnValue({
      status: 'loading',
      login: vi.fn(),
      logout: vi.fn(),
    } as ReturnType<typeof useAuthModule.useAuth>)
    mockUseMe.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      error: null,
    } as any)

    renderPublishPage()

    const authorDisplay = screen.getByTestId('publish-author-display')
    expect(authorDisplay).toHaveTextContent('正在確認登入狀態...')
    expect(authorDisplay).not.toHaveTextContent('@')
  })

  it('AC-S179-2: 未登入送出只啟動登入流程', async () => {
    const login = vi.fn()
    const fetchSpy = vi.fn()
    ;(globalThis as any).fetch = fetchSpy
    mockUseAuth.mockReturnValue({
      status: 'anonymous',
      login,
      logout: vi.fn(),
    } as ReturnType<typeof useAuthModule.useAuth>)
    mockUseMe.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: false,
      error: null,
    } as any)

    renderPublishPage()

    fireEvent.click(screen.getByRole('button', { name: /貼上文本/ }))
    fireEvent.change(screen.getByPlaceholderText(/name: my-skill/), {
      target: { value: '---\nname: internal-package-name\ndescription: a useful skill\n---\n# body' },
    })
    fireEvent.change(screen.getByLabelText('技能名稱'), { target: { value: 'Team Transcribe' } })
    fireEvent.change(screen.getByPlaceholderText('DevOps'), { target: { value: 'DevOps' } })

    const submitBtn = screen.getByRole('button', { name: /發佈技能/ })
    await waitFor(() => expect(submitBtn).not.toBeDisabled())
    fireEvent.click(submitBtn)

    expect(login).toHaveBeenCalledTimes(1)
    expect(fetchSpy).not.toHaveBeenCalledWith('/api/v1/skills/upload', expect.anything())
  })

  it('AC-S176-1: 表單提交時 FormData 含 skillName 且不含 author key', async () => {
    // 攔截 fetch 取出 FormData 檢查
    let capturedForm: FormData | null = null
    const fetchSpy = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url === '/api/v1/skills/upload') {
        capturedForm = init?.body as FormData
        return {
          ok: true,
          status: 200,
          json: async () => ({ id: 'sk_test123' }),
        } as Response
      }
      return { ok: true, status: 200, json: async () => ({}) } as Response
    })
    ;(globalThis as any).fetch = fetchSpy

    renderPublishPage()

    // 走 text mode — textarea 比 FileDropZone 隱藏 input 更可靠在 jsdom 觸發 state update
    fireEvent.click(screen.getByRole('button', { name: /貼上文本/ }))
    const textarea = screen.getByPlaceholderText(/name: my-skill/) as HTMLTextAreaElement
    fireEvent.change(textarea, {
      target: { value: '---\nname: internal-package-name\ndescription: a useful skill\n---\n# body' },
    })
    const skillNameInput = screen.getByLabelText('技能名稱') as HTMLInputElement
    fireEvent.change(skillNameInput, { target: { value: 'Team Transcribe' } })
    const categoryInput = screen.getByPlaceholderText('DevOps') as HTMLInputElement
    fireEvent.change(categoryInput, { target: { value: 'DevOps' } })

    // 送出
    const submitBtn = screen.getByRole('button', { name: /發佈技能/ })
    await waitFor(() => expect(submitBtn).not.toBeDisabled())
    fireEvent.click(submitBtn)

    await waitFor(() => {
      expect(capturedForm).not.toBeNull()
    })
    // FormData entries 確認不含 author key
    const keys = Array.from(capturedForm!.keys())
    expect(keys).not.toContain('author')
    expect(capturedForm!.get('skillName')).toBe('Team Transcribe')
    // sanity — 其他必要欄位仍在；S188 之後空白版本交給 backend 自動產生 v1 / next number
    expect(keys).toContain('file')
    expect(keys).not.toContain('version')
    expect(keys).toContain('category')
    expect(keys).toContain('visibility')
  })

  it('AC-S176-1: 缺少技能名稱時發佈按鈕 disabled', async () => {
    renderPublishPage()

    fireEvent.click(screen.getByRole('button', { name: /貼上文本/ }))
    const textarea = screen.getByPlaceholderText(/name: my-skill/) as HTMLTextAreaElement
    fireEvent.change(textarea, {
      target: { value: '---\nname: internal-package-name\ndescription: a useful skill\n---\n# body' },
    })
    const categoryInput = screen.getByPlaceholderText('DevOps') as HTMLInputElement
    fireEvent.change(categoryInput, { target: { value: 'DevOps' } })

    const submitBtn = screen.getByRole('button', { name: /發佈技能/ })
    expect(submitBtn).toBeDisabled()

    const skillNameInput = screen.getByLabelText('技能名稱') as HTMLInputElement
    fireEvent.change(skillNameInput, { target: { value: 'platform-skill' } })
    await waitFor(() => expect(submitBtn).not.toBeDisabled())
  })

  it('AC-S176-1: 技能名稱是平台顯示名稱，不套用 SKILL.md package-name pattern', () => {
    renderPublishPage()

    const skillNameInput = screen.getByLabelText('技能名稱') as HTMLInputElement
    expect(skillNameInput.getAttribute('pattern')).toBeNull()
    expect(screen.getByText(/平台列表顯示名稱，可和 SKILL\.md 裡的 name 不同/)).toBeInTheDocument()
  })

  it('AC-S188-5: 版本欄位可留白，沒有 required / 固定格式 pattern', () => {
    renderPublishPage()

    const versionInput = screen.getByLabelText('版本號') as HTMLInputElement
    expect(versionInput.value).toBe('')
    expect(versionInput.required).toBe(false)
    expect(versionInput.getAttribute('pattern')).toBeNull()
    expect(screen.getByText(/留白時系統會自動產生版本號/)).toBeInTheDocument()
  })
})
