import { MiniMarkdown } from '@/lib/mini-markdown'
import { FrontmatterSyntax } from '../shared/FrontmatterSyntax'

interface Props {
  /** Raw SKILL.md content (fetched externally); undefined = loading, null = 404 */
  content: string | null | undefined
}

function parseFrontmatter(raw: string): { frontmatter: string; body: string } {
  const lines = raw.split('\n')
  if (lines[0]?.trim() !== '---') {
    return { frontmatter: '', body: raw }
  }
  const closeIdx = lines.findIndex((l, i) => i > 0 && l.trim() === '---')
  if (closeIdx < 0) {
    return { frontmatter: '', body: raw }
  }
  return {
    frontmatter: lines.slice(0, closeIdx + 1).join('\n'),
    body: lines.slice(closeIdx + 1).join('\n').trimStart(),
  }
}

/**
 * S142a — SKILL.md tab panel.
 * Renders YAML frontmatter with syntax highlighting + markdown body.
 * Content is passed from parent (SkillDetailPage fetches via useSkillFile).
 */
export function SkillMdTab({ content }: Props) {
  if (content === undefined) {
    return (
      <div style={{ padding: 24 }}>
        <div className="animate-pulse" style={{ height: 120, background: 'rgba(255,255,255,0.05)', borderRadius: 8 }} />
      </div>
    )
  }

  if (content === null) {
    return (
      <div style={{ padding: 24, color: 'var(--ink-3, rgba(238,236,234,0.4))', fontSize: 14 }}>
        SKILL.md 暫不可用
      </div>
    )
  }

  const { frontmatter, body } = parseFrontmatter(content)

  return (
    <div data-testid="skill-md-tab" style={{ padding: '16px 0' }}>
      {frontmatter && <FrontmatterSyntax yaml={frontmatter} />}
      {body && (
        <div style={{ marginTop: 20, fontSize: 14, lineHeight: 1.7 }}>
          <MiniMarkdown content={body} />
        </div>
      )}
    </div>
  )
}
