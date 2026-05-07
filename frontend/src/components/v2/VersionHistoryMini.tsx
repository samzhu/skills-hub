import type { SkillVersion } from '@/types/skill'

interface Props {
  versions: SkillVersion[]
  onTabChange: (tab: string) => void
}

function relativeTime(iso: string): string {
  const days = Math.floor((Date.now() - new Date(iso).getTime()) / 86400000)
  if (days === 0) return '今天'
  if (days === 1) return '1 天前'
  return `${days} 天前`
}

export function VersionHistoryMini({ versions, onTabChange }: Props) {
  const latest4 = versions.slice(0, 4)

  return (
    <div data-testid="version-history-mini" style={{ padding: '14px 16px', background: 'rgba(0,0,0,0.2)', borderRadius: 10 }}>
      <div style={{ fontSize: 11, color: 'var(--ink-3, rgba(238,236,234,0.4))', marginBottom: 10 }}>
        版本記錄
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {latest4.map((v, i) => (
          <div key={v.id} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontFamily: 'monospace', fontSize: 11, flex: 1 }}>{v.version}</span>
            {i === 0 && (
              <span style={{ fontSize: 9, padding: '1px 5px', borderRadius: 3, background: 'rgba(127,119,221,0.15)', color: '#C9C5F2' }}>
                latest
              </span>
            )}
            <span style={{ fontSize: 10, color: 'var(--ink-3, rgba(238,236,234,0.4))', flexShrink: 0 }}>
              {relativeTime(v.publishedAt)}
            </span>
          </div>
        ))}
      </div>
      <button
        data-testid="versions-all-link"
        onClick={() => onTabChange('versions')}
        style={{
          marginTop: 10,
          width: '100%',
          background: 'none',
          border: 'none',
          cursor: 'pointer',
          fontSize: 11,
          color: 'var(--ink-3, rgba(238,236,234,0.4))',
          textAlign: 'right',
          padding: 0,
        }}
      >
        查看全部 →
      </button>
    </div>
  )
}
