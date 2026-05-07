const SECTIONS = [
  { id: '01', label: 'Validation' },
  { id: '02', label: 'Implementation' },
  { id: '03', label: 'Discovery' },
]

export function QualityTOCCard() {
  return (
    <div data-testid="quality-toc-card" style={{ padding: '14px 16px', background: 'rgba(0,0,0,0.2)', borderRadius: 10 }}>
      <div style={{ fontSize: 11, color: 'var(--ink-3, rgba(238,236,234,0.4))', marginBottom: 10 }}>
        目錄
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {SECTIONS.map(s => (
          <a
            key={s.id}
            href={`#quality-section-${s.id}`}
            style={{ fontSize: 12, color: 'var(--ink-2, rgba(238,236,234,0.7))', textDecoration: 'none' }}
          >
            <span style={{ fontFamily: 'monospace', marginRight: 6, fontSize: 10, color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>{s.id}</span>
            {s.label}
          </a>
        ))}
      </div>
    </div>
  )
}
