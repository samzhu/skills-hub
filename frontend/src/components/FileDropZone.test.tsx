import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { FileDropZone } from './FileDropZone'

/**
 * FileDropZone tests — S037 size guard / S048 ext guard / S053 multi-ext support。
 * 驗 callback contract + inline error 顯示，不測 drag visual states。
 */

const makeFile = (name: string, sizeBytes: number, content = 'x') =>
  new File([content.repeat(sizeBytes)], name, { type: 'application/zip' })

describe('FileDropZone — S037/S048/S053', () => {
  it('AC-S195-1: inputId wires label-compatible hidden file input', () => {
    const { container } = render(
      <FileDropZone
        inputId="skill-edit-file"
        onFileSelect={vi.fn()}
        selectedFile={null}
      />,
    )

    const input = container.querySelector('#skill-edit-file')
    expect(input).toBeInstanceOf(HTMLInputElement)
    expect(input).toHaveAttribute('type', 'file')
  })

  it('AC-1: empty state shows 拖拽 zip 或 md 檔到此處 prompt', () => {
    render(<FileDropZone onFileSelect={vi.fn()} selectedFile={null} />)
    expect(screen.getByText('拖拽 zip 或 md 檔到此處')).toBeInTheDocument()
  })

  it('AC-2: selectedFile shows filename + size in KB', () => {
    const file = makeFile('skill.zip', 2048)
    render(<FileDropZone onFileSelect={vi.fn()} selectedFile={file} />)
    expect(screen.getByText('skill.zip')).toBeInTheDocument()
    expect(screen.getByText(/KB/)).toBeInTheDocument()
  })

  it('AC-3: invalid extension shows inline error and skips onFileSelect', () => {
    const onFileSelect = vi.fn()
    const { container } = render(
      <FileDropZone onFileSelect={onFileSelect} selectedFile={null} />,
    )
    const input = container.querySelector('input[type="file"]') as HTMLInputElement
    const badFile = makeFile('virus.exe', 100)
    fireEvent.change(input, { target: { files: [badFile] } })
    expect(screen.getByText(/只接受/)).toBeInTheDocument()
    expect(onFileSelect).not.toHaveBeenCalled()
  })

  it('AC-4: oversized file shows MB limit error and skips callback', () => {
    const onFileSelect = vi.fn()
    const { container } = render(
      <FileDropZone onFileSelect={onFileSelect} selectedFile={null} maxSizeBytes={1024} />,
    )
    const input = container.querySelector('input[type="file"]') as HTMLInputElement
    const big = makeFile('big.zip', 2048)
    fireEvent.change(input, { target: { files: [big] } })
    expect(screen.getByText(/超過/)).toBeInTheDocument()
    expect(onFileSelect).not.toHaveBeenCalled()
  })

  it('AC-5: valid zip file under limit triggers onFileSelect', () => {
    const onFileSelect = vi.fn()
    const { container } = render(
      <FileDropZone onFileSelect={onFileSelect} selectedFile={null} />,
    )
    const input = container.querySelector('input[type="file"]') as HTMLInputElement
    const good = makeFile('skill.zip', 100)
    fireEvent.change(input, { target: { files: [good] } })
    expect(onFileSelect).toHaveBeenCalledTimes(1)
    expect(onFileSelect.mock.calls[0][0].name).toBe('skill.zip')
  })

  it('AC-6: S053 .md extension also accepted (multi-ext)', () => {
    const onFileSelect = vi.fn()
    const { container } = render(
      <FileDropZone onFileSelect={onFileSelect} selectedFile={null} />,
    )
    const input = container.querySelector('input[type="file"]') as HTMLInputElement
    const mdFile = makeFile('skill.md', 100)
    fireEvent.change(input, { target: { files: [mdFile] } })
    expect(onFileSelect).toHaveBeenCalled()
  })

  it('AC-S197-3: FileDropZone shows required error from caller', () => {
    render(
      <FileDropZone
        onFileSelect={vi.fn()}
        selectedFile={null}
        error="請選擇 zip 或 SKILL.md"
        errorId="publish-file-error"
      />,
    )

    expect(screen.getByText('請選擇 zip 或 SKILL.md')).toBeInTheDocument()
    const dropzone = screen.getByTestId('file-dropzone')
    expect(dropzone).toHaveAttribute('aria-invalid', 'true')
    expect(dropzone.getAttribute('aria-describedby')).toContain('publish-file-error')
  })

  it('AC-S197-3: invalid extension still shows extension error instead of required error', () => {
    const onFileSelect = vi.fn()
    const { container } = render(
      <FileDropZone
        onFileSelect={onFileSelect}
        selectedFile={null}
        error="請選擇 zip 或 SKILL.md"
        errorId="publish-file-error"
      />,
    )
    const input = container.querySelector('input[type="file"]') as HTMLInputElement

    fireEvent.change(input, { target: { files: [makeFile('handover.txt', 100)] } })

    expect(screen.getByText('只接受 .zip / .md 檔，目前是 handover.txt')).toBeInTheDocument()
    expect(screen.queryByText('請選擇 zip 或 SKILL.md')).toBeNull()
    expect(onFileSelect).not.toHaveBeenCalled()
  })

  it('AC-S197-7: FileDropZone exposes aria-invalid and aria-describedby when error exists', () => {
    render(
      <FileDropZone
        onFileSelect={vi.fn()}
        selectedFile={null}
        error="請選擇 zip 或 SKILL.md"
        errorId="publish-file-error"
        describedBy="publish-file-help"
      />,
    )

    const dropzone = screen.getByTestId('file-dropzone')
    expect(dropzone).toHaveAttribute('aria-invalid', 'true')
    expect(dropzone.getAttribute('aria-describedby')).toContain('publish-file-help')
    expect(dropzone.getAttribute('aria-describedby')).toContain('publish-file-error')
  })
})
