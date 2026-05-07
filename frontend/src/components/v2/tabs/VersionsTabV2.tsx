import { useState } from 'react'
import type { SkillVersion } from '@/types/skill'

interface Props {
  versions: SkillVersion[]
}

function relativeTime(iso: string): string {
  const days = Math.floor((Date.now() - new Date(iso).getTime()) / 86400000)
  if (days === 0) return '今天'
  if (days === 1) return '1 天前'
  return `${days} 天前`
}

function VersionCard({ v, isLatest }: { v: SkillVersion; isLatest: boolean }) {
  const [expanded, setExpanded] = useState(false)
  // Backend doesn't currently send changelog text — show version as the card title
  const hasLongDesc = false

  return (
    <div
      data-testid="version-card"
      style={{
        padding: '14px 16px',
        border: '0.5px solid var(--line, rgba(255,255,255,0.08))',
        borderRadius: 10,
        marginBottom: 10,
        background: 'rgba(255,255,255,0.02)',
      }}
    >
      {/* Top row */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6 }}>
        <span data-testid="version-num" style={{ fontFamily: 'monospace', fontSize: 15, fontWeight: 600 }}>
          v{v.version}
        </span>
        {isLatest && (
          <span
            data-testid="latest-badge"
            style={{ fontSize: 10, padding: '1px 7px', borderRadius: 999, background: 'rgba(127,119,221,0.2)', color: '#C9C5F2', fontWeight: 500 }}
          >
            最新
          </span>
        )}
        <span style={{ fontSize: 12, color: 'var(--ink-3, rgba(238,236,234,0.4))', marginLeft: 'auto' }}>
          {relativeTime(v.publishedAt)}
        </span>
      </div>
      {/* Changelog placeholder */}
      <p
        data-testid="version-desc"
        style={{
          fontSize: 13,
          color: 'var(--ink-2, rgba(238,236,234,0.7))',
          lineHeight: 1.55,
          display: expanded ? 'block' : '-webkit-box',
          WebkitLineClamp: 3,
          WebkitBoxOrient: 'vertical',
          overflow: expanded ? 'visible' : 'hidden',
          margin: 0,
        }}
      >
        版本 {v.version} · {(v.fileSize / 1024).toFixed(1)} KB · {v.fileCount} 個檔案
      </p>
      {hasLongDesc && (
        <button
          onClick={() => setExpanded(e => !e)}
          style={{ fontSize: 11, color: '#7F77DD', background: 'none', border: 'none', padding: 0, cursor: 'pointer', marginTop: 4 }}
        >
          {expanded ? '顯示較少' : '顯示更多'}
        </button>
      )}
    </div>
  )
}

/**
 * S142a — Versions tab v2: changelog-style cards.
 * First card has Latest badge; each shows version, relative time, file info.
 */
export function VersionsTabV2({ versions }: Props) {
  if (versions.length === 0) {
    return (
      <div style={{ padding: 24, color: 'var(--ink-3, rgba(238,236,234,0.4))', textAlign: 'center' }}>
        尚無版本記錄
      </div>
    )
  }

  return (
    <div data-testid="versions-tab-v2" style={{ padding: '16px 0' }}>
      {versions.map((v, i) => (
        <VersionCard key={v.id} v={v} isLatest={i === 0} />
      ))}
    </div>
  )
}
