import type { Skill } from '@/types/skill'
import type { SkillVersion } from '@/types/skill'

interface Props {
  skill: Skill
  version: SkillVersion | undefined
}

function formatSize(bytes: number): string {
  if (bytes < 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  return `${(bytes / 1024).toFixed(1)} KB`
}

function formatDate(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleDateString('zh-TW')
}

export function DetailsCard({ skill, version }: Props) {
  const rows: Array<{ label: string; value: string }> = [
    { label: '發布日期', value: formatDate(skill.latestVersionPublishedAt ?? null) },
    { label: '授權', value: skill.license ?? '—' },
    { label: '大小', value: version ? formatSize(version.fileSize) : '—' },
    { label: '檔案數', value: version ? String(version.fileCount) : '—' },
    { label: '腳本', value: '無' },
  ]

  return (
    <div data-testid="details-card" style={{ padding: '14px 16px', background: 'rgba(0,0,0,0.2)', borderRadius: 10 }}>
      <div style={{ fontSize: 11, color: 'var(--ink-3, rgba(238,236,234,0.4))', marginBottom: 10 }}>
        詳細資訊
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {rows.map(r => (
          <div key={r.label} style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span style={{ fontSize: 11, color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>{r.label}</span>
            <span style={{ fontSize: 11, fontFamily: r.label === '發布日期' ? undefined : 'monospace' }}>{r.value}</span>
          </div>
        ))}
      </div>
    </div>
  )
}
