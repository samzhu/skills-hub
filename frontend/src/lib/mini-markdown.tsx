import type { ReactNode } from 'react'

/**
 * S099b3 — Minimal Markdown renderer for SKILL.md preview pane。
 *
 * 不依賴 react-markdown / remark-gfm（避免 50KB+ bundle dep）— hand-rolled
 * line-by-line parser cover SKILL.md 主要 markdown subset：
 *
 * - `#` / `##` / `###` → h1 / h2 / h3
 * - ``` ``` ``` → fenced code block (`<pre>`)
 * - 開頭 `- ` / `* ` / `數字. ` → unordered / ordered list
 * - 行內 `code` → `<code>` (簡單 backtick split)
 * - 其他非空行 → `<p>`
 *
 * **不支援**：tables / blockquotes / images / links（KISS — defer if user needs）。
 * **YAML frontmatter `---...---` block** 被略過 — preview 只渲染 markdown 正文。
 *
 * @param content raw markdown string
 * @returns React node（fragment of p / h1-h3 / pre / ul / ol）
 */
export function MiniMarkdown({ content }: { content: string }) {
  const blocks = parseBlocks(content)
  return (
    <>
      {blocks.map((b, i) => renderBlock(b, i))}
    </>
  )
}

type Block =
  | { type: 'h1' | 'h2' | 'h3' | 'p'; text: string }
  | { type: 'code'; text: string; lang?: string }
  | { type: 'list'; items: string[]; ordered: boolean }

function parseBlocks(content: string): Block[] {
  const blocks: Block[] = []
  const lines = content.split('\n')
  let i = 0
  // Skip frontmatter ---...---
  if (lines[0]?.trim() === '---') {
    let j = 1
    while (j < lines.length && lines[j].trim() !== '---') j++
    i = j + 1 // skip closing ---
  }
  while (i < lines.length) {
    const line = lines[i]
    const trimmed = line.trim()

    // empty line
    if (!trimmed) { i++; continue }

    // fenced code block
    if (trimmed.startsWith('```')) {
      const lang = trimmed.slice(3).trim()
      const codeLines: string[] = []
      i++
      while (i < lines.length && !lines[i].trim().startsWith('```')) {
        codeLines.push(lines[i])
        i++
      }
      i++ // consume closing ```
      blocks.push({ type: 'code', text: codeLines.join('\n'), lang })
      continue
    }

    // headings
    if (trimmed.startsWith('### ')) {
      blocks.push({ type: 'h3', text: trimmed.slice(4) })
      i++
      continue
    }
    if (trimmed.startsWith('## ')) {
      blocks.push({ type: 'h2', text: trimmed.slice(3) })
      i++
      continue
    }
    if (trimmed.startsWith('# ')) {
      blocks.push({ type: 'h1', text: trimmed.slice(2) })
      i++
      continue
    }

    // unordered list
    if (/^[-*]\s+/.test(trimmed)) {
      const items: string[] = []
      while (i < lines.length && /^[-*]\s+/.test(lines[i].trim())) {
        items.push(lines[i].trim().replace(/^[-*]\s+/, ''))
        i++
      }
      blocks.push({ type: 'list', items, ordered: false })
      continue
    }

    // ordered list
    if (/^\d+\.\s+/.test(trimmed)) {
      const items: string[] = []
      while (i < lines.length && /^\d+\.\s+/.test(lines[i].trim())) {
        items.push(lines[i].trim().replace(/^\d+\.\s+/, ''))
        i++
      }
      blocks.push({ type: 'list', items, ordered: true })
      continue
    }

    // paragraph fallback — collect current line + adjacent non-special lines into <p>
    // 必須包當前 line（保證 i 推進避免無窮迴圈，例如 "##NoSpace" 不符 heading regex 但首字 #）
    const paraLines: string[] = [lines[i].trim()]
    i++ // 強制推進避免 infinite loop
    while (
      i < lines.length
      && lines[i].trim()
      && !lines[i].trim().startsWith('#')
      && !lines[i].trim().startsWith('```')
      && !/^[-*]\s+/.test(lines[i].trim())
      && !/^\d+\.\s+/.test(lines[i].trim())
    ) {
      paraLines.push(lines[i].trim())
      i++
    }
    blocks.push({ type: 'p', text: paraLines.join(' ') })
  }
  return blocks
}

/** Render inline `backtick` code → array of nodes mixing text + <code>. */
function renderInline(text: string): ReactNode[] {
  const parts: ReactNode[] = []
  const segs = text.split(/(`[^`]+`)/)
  for (let k = 0; k < segs.length; k++) {
    const seg = segs[k]
    if (seg.startsWith('`') && seg.endsWith('`') && seg.length >= 2) {
      parts.push(
        <code key={k} className="rounded bg-[#171719] px-1 py-0.5 font-mono text-[12px] text-[#EEECEA]">
          {seg.slice(1, -1)}
        </code>,
      )
    } else if (seg) {
      parts.push(seg)
    }
  }
  return parts
}

function renderBlock(block: Block, idx: number): ReactNode {
  switch (block.type) {
    case 'h1':
      return <h1 key={idx} className="mt-4 text-[20px] font-semibold text-[#EEECEA]">{renderInline(block.text)}</h1>
    case 'h2':
      return <h2 key={idx} className="mt-4 text-[16px] font-semibold text-[#EEECEA]">{renderInline(block.text)}</h2>
    case 'h3':
      return <h3 key={idx} className="mt-3 text-[14px] font-medium text-[#EEECEA]">{renderInline(block.text)}</h3>
    case 'p':
      return <p key={idx} className="mt-2 text-[13px] leading-relaxed text-[#A8A49C]">{renderInline(block.text)}</p>
    case 'code':
      return (
        <pre key={idx} className="mt-2 overflow-x-auto rounded-md border border-[rgba(255,255,255,0.06)] bg-[#0F0F12] p-3 font-mono text-[12px] leading-relaxed text-[#EEECEA]">
          {block.text}
        </pre>
      )
    case 'list': {
      const Tag = block.ordered ? 'ol' : 'ul'
      const cls = block.ordered ? 'list-decimal' : 'list-disc'
      return (
        <Tag key={idx} className={`mt-2 ${cls} pl-5 text-[13px] leading-relaxed text-[#A8A49C]`}>
          {block.items.map((it, j) => (
            <li key={j}>{renderInline(it)}</li>
          ))}
        </Tag>
      )
    }
  }
}
