import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { FileExplorerPanel } from './FileExplorerPanel'
import type { SkillFile } from '@/api/skills'
import * as useSkillFilesModule from '@/hooks/useSkillFiles'
import * as skillsApi from '@/api/skills'

vi.mock('@/hooks/useSkillFiles')
vi.mock('@/api/skills', async (importOriginal) => {
  const actual = await importOriginal<typeof skillsApi>()
  return { ...actual, fetchSkillFile: vi.fn() }
})

const mockFiles: SkillFile[] = [
  { path: 'SKILL.md', size: 1024, type: 'text/markdown' },
  { path: 'examples/example.py', size: 512, type: 'text/x-python' },
  { path: 'scripts/install.sh', size: 256, type: 'text/x-sh' },
  { path: 'assets/logo.png', size: 4096, type: 'image/png' },
]

const mockUseSkillFiles = vi.mocked(useSkillFilesModule.useSkillFiles)
const mockFetchSkillFile = vi.mocked(skillsApi.fetchSkillFile)

function makeTextBlob(text = '# Hello') {
  return { blob: new Blob([text], { type: 'text/plain' }), contentType: 'text/plain' }
}

function makeBinaryBlob() {
  return { blob: new Blob([new Uint8Array([0x89, 0x50])], { type: 'image/png' }), contentType: 'image/png' }
}

function setupFiles(files = mockFiles) {
  mockUseSkillFiles.mockReturnValue({ data: files, isLoading: false, error: null, refetch: vi.fn() } as any)
  mockFetchSkillFile.mockResolvedValue(makeTextBlob())
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('FileExplorerPanel', () => {
  it('AC-S142a-14: loading state shows loading text', () => {
    mockUseSkillFiles.mockReturnValue({ data: undefined, isLoading: true, error: null, refetch: vi.fn() } as any)
    render(<FileExplorerPanel skillId="s1" />)
    expect(screen.getByText('載入檔案清單中...')).toBeTruthy()
  })

  it('AC-S142a-14: error state shows 檔案列表載入失敗 + retry', () => {
    mockUseSkillFiles.mockReturnValue({ data: undefined, isLoading: false, error: new Error('fail'), refetch: vi.fn() } as any)
    render(<FileExplorerPanel skillId="s1" />)
    expect(screen.getByTestId('files-error')).toBeTruthy()
    expect(screen.getByText('檔案列表載入失敗')).toBeTruthy()
    expect(screen.getByText('重試')).toBeTruthy()
  })

  it('AC-S142a-14: retry button calls refetch', () => {
    const refetch = vi.fn()
    mockUseSkillFiles.mockReturnValue({ data: undefined, isLoading: false, error: new Error('fail'), refetch } as any)
    render(<FileExplorerPanel skillId="s1" />)
    fireEvent.click(screen.getByText('重試'))
    expect(refetch).toHaveBeenCalledOnce()
  })

  it('AC-S142a-14: layout grid renders file-explorer-panel', () => {
    setupFiles()
    const { container } = render(<FileExplorerPanel skillId="s1" />)
    const panel = container.querySelector('[data-testid="file-explorer-panel"]') as HTMLElement
    expect(panel).toBeTruthy()
    expect(panel.style.gridTemplateColumns).toBe('220px 1fr')
  })

  it('AC-S142a-14: scripts/ dir has ft-scripts-dir class', () => {
    setupFiles()
    const { container } = render(<FileExplorerPanel skillId="s1" />)
    const scriptsBtn = container.querySelector('[data-testid="tree-item-scripts"]')
    expect(scriptsBtn).toBeTruthy()
    expect(scriptsBtn!.classList.contains('ft-scripts-dir')).toBe(true)
  })

  it('AC-S142a-14: scripts/ dir shows "security scan" badge', () => {
    setupFiles()
    render(<FileExplorerPanel skillId="s1" />)
    expect(screen.getByText('security scan')).toBeTruthy()
  })

  it('AC-S142a-14: file in scripts/ has ft-in-scripts class', () => {
    setupFiles()
    const { container } = render(<FileExplorerPanel skillId="s1" />)
    const installBtn = container.querySelector('[data-testid="tree-item-scripts-install.sh"]')
    expect(installBtn).toBeTruthy()
    expect(installBtn!.classList.contains('ft-in-scripts')).toBe(true)
  })

  it('AC-S142a-14: clicking scripts/ file shows security-banner in preview', async () => {
    setupFiles()
    render(<FileExplorerPanel skillId="s1" />)
    fireEvent.click(screen.getByTestId('tree-item-scripts-install.sh'))
    await waitFor(() => expect(screen.getByTestId('security-banner')).toBeTruthy())
  })

  it('AC-S142a-14: binary file shows ft-binary fallback', async () => {
    setupFiles()
    mockFetchSkillFile.mockResolvedValue(makeBinaryBlob())
    const { container } = render(<FileExplorerPanel skillId="s1" />)
    fireEvent.click(screen.getByTestId('tree-item-assets-logo.png'))
    await waitFor(() => expect(container.querySelector('[data-testid="binary-fallback"]')).toBeTruthy())
    expect(screen.getByText('Binary file — preview unavailable')).toBeTruthy()
  })

  it('AC-S142a-14: lang badge visible for selected file', async () => {
    setupFiles()
    render(<FileExplorerPanel skillId="s1" />)
    await waitFor(() => expect(screen.getByTestId('lang-badge')).toBeTruthy())
  })

  it('AC-S142a-14: SKILL.md selected by default — lang badge shows Markdown', async () => {
    setupFiles()
    render(<FileExplorerPanel skillId="s1" />)
    await waitFor(() => {
      const badge = screen.getByTestId('lang-badge')
      expect(badge.textContent).toBe('Markdown')
    })
  })

  it('AC-S142a-14: clicking different file updates selected path in header', async () => {
    setupFiles()
    render(<FileExplorerPanel skillId="s1" />)
    // first collapse examples/ folder isn't needed — just click a top-level file via SKILL.md
    // click examples folder to expand it (already expanded), then click example.py
    fireEvent.click(screen.getByTestId('tree-item-examples-example.py'))
    await waitFor(() => expect(screen.getByText('examples/example.py')).toBeTruthy())
  })
})
