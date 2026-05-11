/**
 * S142a — File extension → language display badge.
 * Used in FileExplorerPanel preview header.
 */
const EXT_LANG: Record<string, string> = {
  md: 'Markdown', markdown: 'Markdown',
  yml: 'YAML', yaml: 'YAML',
  ts: 'TypeScript', tsx: 'TSX',
  js: 'JavaScript', jsx: 'JSX',
  py: 'Python',
  sh: 'Bash', bash: 'Bash', zsh: 'Bash',
  json: 'JSON',
  toml: 'TOML',
  java: 'Java',
  go: 'Go',
  rb: 'Ruby',
  css: 'CSS',
  html: 'HTML',
  xml: 'XML',
  sql: 'SQL',
  dockerfile: 'Dockerfile',
}

function getLangLabel(filename: string): string {
  const ext = filename.split('.').pop()?.toLowerCase() ?? ''
  return EXT_LANG[ext] ?? (ext.toUpperCase() || 'Text')
}

export function LangBadge({ filename }: { filename: string }) {
  const label = getLangLabel(filename)
  return (
    <span
      data-testid="lang-badge"
      style={{
        fontSize: 10,
        padding: '1px 6px',
        borderRadius: 4,
        background: 'rgba(127,119,221,0.15)',
        color: '#C9C5F2',
        fontFamily: 'monospace',
        fontWeight: 500,
      }}
    >
      {label}
    </span>
  )
}
