/**
 * S085: 6-category-tinted icon tile per DESIGN.md §Components Icon Tiles。
 *
 * Square tiles holding 2-letter initials sized at sm (24) / md (30) / lg (40) / xl (52)。
 * Tile color from category palette (NEVER from risk palette — DESIGN.md strictness).
 * Per `docs/grimo/ui/prototype/Skills Hub Homepage.html` icon-tile palette.
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
  devops:   { bg: 'rgba(127,119,221,0.18)', fg: '#C9C5F2' },
  infra:    { bg: 'rgba(55,138,221,0.14)', fg: '#B0D5F2' },
  testing:  { bg: 'rgba(29,158,117,0.14)', fg: '#6FD8B0' },
  docs:     { bg: 'rgba(226,75,74,0.18)', fg: '#F2A6A6' },
  data:     { bg: 'rgba(239,159,39,0.14)', fg: '#FAC775' },
  security: { bg: 'rgba(217,56,138,0.18)', fg: '#F0A2C5' },
  default:  { bg: '#171719', fg: '#A8A49C' },
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
