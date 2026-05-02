import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MiniMarkdown } from './mini-markdown'

/**
 * MiniMarkdown tests — hand-rolled SKILL.md preview renderer。
 *
 * **Important**：JSX attribute string `content="..."` 不解 \n escape（HTML attribute
 * 慣例 \n 為 literal）。要傳真 LF 必須用 JSX expression `content={"...\n..."}`
 * 或 template literal。本檔下方測試一律 JSX expression form when LF needed.
 *
 * Per 2026-05-02 methodology「3-5 反例 / round」cover positive + negatives + edges.
 */

describe('MiniMarkdown — S099b3', () => {
  // Positive
  it('AC-1: heading levels render as h1/h2/h3', () => {
    render(<MiniMarkdown content={'# Big\n## Mid\n### Small'} />)
    expect(screen.getByRole('heading', { level: 1, name: 'Big' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Mid' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 3, name: 'Small' })).toBeInTheDocument()
  })

  it('AC-2: paragraph text renders as <p>', () => {
    render(<MiniMarkdown content="Just some prose here." />)
    expect(screen.getByText('Just some prose here.')).toBeInTheDocument()
  })

  it('AC-3: fenced code block preserves content', () => {
    render(<MiniMarkdown content={'```\nconst x = 1\n```'} />)
    expect(screen.getByText('const x = 1')).toBeInTheDocument()
  })

  it('AC-4: unordered list renders as <ul><li>', () => {
    const { container } = render(<MiniMarkdown content={'- one\n- two\n- three'} />)
    const ul = container.querySelector('ul')
    expect(ul).toBeInTheDocument()
    expect(ul?.querySelectorAll('li').length).toBe(3)
    expect(screen.getByText('two')).toBeInTheDocument()
  })

  it('AC-5: ordered list renders as <ol>', () => {
    const { container } = render(<MiniMarkdown content={'1. first\n2. second'} />)
    expect(container.querySelector('ol')).toBeInTheDocument()
  })

  // Edge: frontmatter skipped
  it('AC-6 (edge): YAML frontmatter --- block is stripped from preview', () => {
    render(<MiniMarkdown content={'---\nname: x\ndescription: y\n---\n# Real heading'} />)
    expect(screen.getByRole('heading', { level: 1, name: 'Real heading' })).toBeInTheDocument()
    // frontmatter content not rendered as text
    expect(screen.queryByText('name: x')).not.toBeInTheDocument()
  })

  // Negative: empty content
  it('AC-7 (negative empty): empty string yields empty render (no crash)', () => {
    const { container } = render(<MiniMarkdown content="" />)
    // Empty content → no h1/h2/h3/p children
    expect(container.firstChild?.firstChild ?? null).toBeNull()
  })

  // Negative: unclosed code fence
  it('AC-8 (negative malformed): unclosed code fence still renders captured content', () => {
    const { container } = render(<MiniMarkdown content={'```\nstill code\n# heading inside'} />)
    // 內容 captured to end of input；不該 crash；用 textContent 比 getByText 對 multiline pre 更穩
    expect(container.querySelector('pre')?.textContent).toContain('still code')
  })

  // Negative: heading-like text without space (## not heading)
  it('AC-9 (negative format): "##NoSpace" 不算 heading（無 space)', () => {
    render(<MiniMarkdown content="##NoSpace text" />)
    // h2 不應出現
    expect(screen.queryByRole('heading', { level: 2 })).not.toBeInTheDocument()
  })

  // Edge: inline code in paragraph
  it('AC-10 (edge): inline `code` in paragraph wraps in <code>', () => {
    const { container } = render(<MiniMarkdown content="Use `npm install` to setup." />)
    const code = container.querySelector('code')
    expect(code?.textContent).toBe('npm install')
  })
})
