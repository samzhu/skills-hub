import { useState } from 'react'
import { Link } from 'react-router'
import { getAuthorRouteSegment } from '@/lib/displayName'
import type { Skill } from '@/types/skill'

interface Props {
  skill: Skill
}

export function InstallCard({ skill }: Props) {
  const [copied, setCopied] = useState(false)
  // S192: install command 是 technical segment；handle 缺時可 fallback platform user_id。
  const cmd = `skills-hub install ${getAuthorRouteSegment(skill)}/${skill.name}`

  function handleCopy() {
    navigator.clipboard.writeText(cmd).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    })
  }

  return (
    <div data-testid="install-card" style={{ padding: '14px 16px', background: 'rgba(0,0,0,0.2)', borderRadius: 10 }}>
      <div style={{ fontSize: 11, color: 'var(--ink-3, rgba(238,236,234,0.4))', marginBottom: 8 }}>
        安裝
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
        <div style={{
          flex: 1,
          minWidth: 0,
          fontFamily: 'monospace',
          fontSize: 11,
          padding: '6px 10px',
          background: 'rgba(0,0,0,0.35)',
          borderRadius: 6,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }}>
          {cmd}
        </div>
        <button
          data-testid="install-copy-btn"
          onClick={handleCopy}
          aria-label={copied ? '已複製' : '複製安裝指令'}
          style={{
            flexShrink: 0,
            background: copied ? 'rgba(29,158,117,0.15)' : 'rgba(127,119,221,0.15)',
            border: 'none',
            borderRadius: 6,
            padding: '6px 10px',
            cursor: 'pointer',
            fontSize: 12,
            color: copied ? '#6FD8B0' : '#C9C5F2',
          }}
        >
          {copied ? '✓' : '⧉'}
        </button>
      </div>
      <div style={{ marginTop: 6, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        {/* S155 #7: 移除「▼」假 dropdown 暗示（無 onclick / role/handler）— 純文字 label */}
        <span style={{ fontSize: 10, color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>Skills Hub CLI</span>
        <Link
          to="/docs/your-first-skill"
          style={{ fontSize: 10, color: 'var(--ink-3, rgba(238,236,234,0.4))', textDecoration: 'underline' }}
        >
          什麼是技能？
        </Link>
      </div>
    </div>
  )
}
