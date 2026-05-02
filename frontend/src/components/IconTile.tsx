/**
 * S085: 6-category-tinted icon tile per DESIGN.md §Components Icon Tiles。
 *
 * Square tiles holding 2-letter initials sized at sm (24) / md (30) / lg (40) / xl (52)。
 * Tile color from category palette (NEVER from risk palette — DESIGN.md strictness).
 * Per `skills_hub_homepage_mockup.html` `.sh-icon.{purple|blue|teal|coral|amber|pink}` 配色。
 */

type Size = 'sm' | 'md' | 'lg' | 'xl'
type Category = 'devops' | 'infra' | 'testing' | 'docs' | 'data' | 'security' | 'default'

const SIZE_CLASS: Record<Size, string> = {
  sm: 'w-6 h-6 text-[10px] rounded-[5px]',
  md: 'w-[30px] h-[30px] text-[13px] rounded-[7px]',
  lg: 'w-10 h-10 text-base rounded-[8px]',
  xl: 'w-[52px] h-[52px] text-lg rounded-[10px]',
}

// Per DESIGN.md category palette + prototype `.sh-icon.*`
const CATEGORY_COLORS: Record<Category, { bg: string; fg: string }> = {
  devops:   { bg: '#EEEDFE', fg: '#3C3489' },
  infra:    { bg: '#E6F1FB', fg: '#0C447C' },
  testing:  { bg: '#E1F5EE', fg: '#085041' },
  docs:     { bg: '#FAECE7', fg: '#712B13' },
  data:     { bg: '#FAEEDA', fg: '#633806' },
  security: { bg: '#FBEAF0', fg: '#72243E' },
  default:  { bg: '#F5F4ED', fg: '#5C5C5C' },
}

/**
 * Map skill.category string → category palette key.
 * Falls back to 'default' for unknown categories（warm-neutral tone）.
 */
function categoryKey(category: string | undefined): Category {
  const c = (category ?? '').toLowerCase()
  if (c === 'devops') return 'devops'
  if (c === 'testing' || c === 'uitest') return 'testing'
  if (c === 'documents' || c === 'docs' || c === 'documentation') return 'docs'
  if (c === 'data' || c === 'data & etl' || c === 'analytics' || c === 'ai') return 'data'
  if (c === 'security') return 'security'
  if (c === 'infra' || c === 'infrastructure' || c === 'frontend' || c === 'design') return 'infra'
  return 'default'
}

/** 從 skill name 取 1-2 letter initial（capitalized）。 */
function initial(name: string): string {
  const trimmed = name.trim()
  if (!trimmed) return '?'
  // 取第一個字母，若有 hyphen 取兩段首字母
  const parts = trimmed.split(/[-_\s]+/).filter(Boolean)
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase()
  return trimmed[0].toUpperCase()
}

export function IconTile({
  name,
  category,
  size = 'md',
}: {
  name: string
  category?: string
  size?: Size
}) {
  const key = categoryKey(category)
  const { bg, fg } = CATEGORY_COLORS[key]
  return (
    <span
      className={`inline-flex items-center justify-center font-medium shrink-0 ${SIZE_CLASS[size]}`}
      style={{ backgroundColor: bg, color: fg }}
      aria-hidden="true"
    >
      {initial(name)}
    </span>
  )
}
