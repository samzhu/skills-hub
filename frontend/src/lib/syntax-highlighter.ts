/**
 * S142a — shiki-based syntax highlighter for SKILL.md frontmatter display.
 * Lazy-loaded to avoid impacting initial bundle.
 * Token CSS classes: tok-key / tok-str / tok-num / tok-kw
 */

type TokenType = 'key' | 'str' | 'num' | 'kw' | 'text'

export interface HighlightToken {
  type: TokenType
  value: string
}

let shikiHighlighter: Awaited<ReturnType<typeof createHighlighter>> | null = null

async function createHighlighter() {
  const { createHighlighter: create } = await import('shiki')
  return create({
    themes: ['github-dark'],
    langs: ['yaml'],
  })
}

async function getHighlighter() {
  if (!shikiHighlighter) {
    shikiHighlighter = await createHighlighter()
  }
  return shikiHighlighter
}

/**
 * Highlight YAML-like frontmatter content using shiki.
 * Returns HTML string with inline styles. Caller wraps in <pre>.
 */
export async function highlightFrontmatter(yaml: string): Promise<string> {
  const hl = await getHighlighter()
  return hl.codeToHtml(yaml, {
    lang: 'yaml',
    theme: 'github-dark',
  })
}

/**
 * Detect language from file extension for code preview.
 * Returns shiki-supported lang id or 'text' as fallback.
 */
export function detectLang(filename: string): string {
  const ext = filename.split('.').pop()?.toLowerCase() ?? ''
  const langMap: Record<string, string> = {
    ts: 'typescript', tsx: 'tsx', js: 'javascript', jsx: 'jsx',
    py: 'python', rb: 'ruby', go: 'go', java: 'java',
    sh: 'bash', bash: 'bash', zsh: 'bash',
    json: 'json', yaml: 'yaml', yml: 'yaml',
    md: 'markdown', toml: 'toml',
    css: 'css', html: 'html', xml: 'xml',
    sql: 'sql', dockerfile: 'dockerfile',
  }
  return langMap[ext] ?? 'text'
}

/**
 * Highlight arbitrary code for file preview.
 * Returns HTML string.
 */
export async function highlightCode(code: string, filename: string): Promise<string> {
  const lang = detectLang(filename)
  if (lang === 'text') return escapeHtml(code)
  const hl = await getHighlighter()
  try {
    return hl.codeToHtml(code, { lang, theme: 'github-dark' })
  } catch {
    return escapeHtml(code)
  }
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}
