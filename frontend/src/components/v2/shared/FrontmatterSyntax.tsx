import type { ReactNode } from 'react'

/**
 * S142a — Lightweight YAML frontmatter tokenizer.
 * Produces spans with CSS classes:
 *   tok-key  — YAML key (before ':')
 *   tok-str  — quoted string value
 *   tok-num  — numeric value
 *   tok-kw   — keyword: true / false / null
 *   tok-sep  — frontmatter delimiters (---)
 */

type TokenKind = 'key' | 'str' | 'num' | 'kw' | 'sep' | 'text'

interface Token { kind: TokenKind; value: string }

function tokenizeLine(line: string): Token[] {
  if (/^-{3,}$/.test(line.trim())) {
    return [{ kind: 'sep', value: line }]
  }

  const keyMatch = line.match(/^(\s*)([\w-]+)(\s*:)(.*)$/)
  if (!keyMatch) {
    return [{ kind: 'text', value: line }]
  }

  const [, indent, key, colon, rest] = keyMatch
  const tokens: Token[] = [
    { kind: 'text', value: indent },
    { kind: 'key', value: key + colon },
  ]

  const val = rest.trimStart()
  if (!val) return tokens

  tokens.push({ kind: 'text', value: ' ' })
  if (/^["']/.test(val)) {
    tokens.push({ kind: 'str', value: val })
  } else if (/^-?\d/.test(val)) {
    tokens.push({ kind: 'num', value: val })
  } else if (/^(true|false|null|yes|no)(\s|$)/i.test(val)) {
    const kw = val.split(/\s/)[0]
    tokens.push({ kind: 'kw', value: kw })
    const remainder = val.slice(kw.length)
    if (remainder) tokens.push({ kind: 'text', value: remainder })
  } else {
    tokens.push({ kind: 'text', value: val })
  }

  return tokens
}

const COLOR: Record<TokenKind, React.CSSProperties> = {
  key:  { color: '#79C0FF' },        // blue-ish
  str:  { color: '#A5D6FF' },        // light blue
  num:  { color: '#79C0FF' },
  kw:   { color: '#FF7B72' },        // keyword red
  sep:  { color: 'rgba(238,236,234,0.3)' },
  text: {},
}

/** Renders YAML frontmatter with syntax token coloring. */
export function FrontmatterSyntax({ yaml }: { yaml: string }) {
  const lines = yaml.split('\n')
  return (
    <pre
      data-testid="frontmatter-syntax"
      style={{
        fontFamily: 'monospace',
        fontSize: 13,
        lineHeight: 1.6,
        padding: '14px 16px',
        background: 'rgba(0,0,0,0.3)',
        borderRadius: 8,
        overflowX: 'auto',
        margin: 0,
      }}
    >
      {lines.map((line, i) => {
        const tokens = tokenizeLine(line)
        return (
          <div key={i}>
            {tokens.map((t, j) => (
              <span key={j} className={`tok-${t.kind}`} style={COLOR[t.kind]}>
                {t.value}
              </span>
            ))}
          </div>
        )
      })}
    </pre>
  )
}
