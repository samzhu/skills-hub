import { useState, useEffect } from 'react'
import { useSkillFiles } from '@/hooks/useSkillFiles'
import { fetchSkillFile } from '@/api/skills'
import type { SkillFile } from '@/api/skills'
import { ApiError } from '@/api/client'
import { LangBadge } from '../shared/LangBadge'

/* ── Tree building ── */

interface TreeNode {
  name: string
  path: string
  isDir: boolean
  size?: number
  type?: string
  children: TreeNode[]
}

function buildTree(files: SkillFile[]): TreeNode[] {
  const root: TreeNode = { name: '', path: '', isDir: true, children: [] }

  for (const f of files) {
    const parts = f.path.split('/')
    let node = root
    for (let i = 0; i < parts.length; i++) {
      const part = parts[i]
      const isLast = i === parts.length - 1
      let child = node.children.find(c => c.name === part)
      if (!child) {
        child = {
          name: part,
          path: parts.slice(0, i + 1).join('/'),
          isDir: !isLast,
          size: isLast ? f.size : undefined,
          type: isLast ? f.type : undefined,
          children: [],
        }
        node.children.push(child)
      }
      node = child
    }
  }

  return root.children
}

function isScriptsDir(path: string): boolean {
  return path === 'scripts' || path.toLowerCase() === 'scripts'
}

function isInScripts(path: string): boolean {
  return path.startsWith('scripts/')
}

function isBinary(type: string): boolean {
  return !type.startsWith('text/') && !type.includes('json') && !type.includes('xml') &&
    !type.includes('javascript') && !type.includes('yaml')
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  return `${(bytes / 1024).toFixed(1)} KB`
}

/* ── Tree Node component ── */
function TreeItem({
  node,
  selected,
  onSelect,
  depth,
}: {
  node: TreeNode
  selected: string | null
  onSelect: (path: string) => void
  depth: number
}) {
  const [open, setOpen] = useState(true)
  const isActive = !node.isDir && selected === node.path
  const isScripts = node.isDir && isScriptsDir(node.name)
  const inScripts = !node.isDir && isInScripts(node.path)

  return (
    <div>
      <button
        data-testid={`tree-item-${node.path.replace(/\//g, '-')}`}
        onClick={() => node.isDir ? setOpen(o => !o) : onSelect(node.path)}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          width: '100%',
          padding: `5px 10px 5px ${10 + depth * 16}px`,
          background: isActive ? 'rgba(127,119,221,0.1)' : isScripts ? 'rgba(239,159,39,0.06)' : 'transparent',
          borderLeft: isActive ? '2px solid #7F77DD' : isScripts ? '2px solid #EF9F27' : inScripts ? '2px solid rgba(239,159,39,0.3)' : 'none',
          border: 'none',
          cursor: 'pointer',
          textAlign: 'left',
          fontSize: 12,
          color: isScripts ? 'var(--amber-text, #FAC775)' : isActive ? '#C9C5F2' : 'var(--ink-2, rgba(238,236,234,0.7))',
        }}
        className={isScripts ? 'ft-scripts-dir' : inScripts ? 'ft-in-scripts' : ''}
      >
        <span style={{ flexShrink: 0 }}>{node.isDir ? (open ? '▾' : '▸') : '·'}</span>
        <span style={{ fontFamily: node.isDir ? undefined : 'monospace', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {node.name}
        </span>
        {isScripts && (
          <span className="ft-badge" style={{ fontSize: 9, padding: '1px 5px', borderRadius: 4, background: 'rgba(239,159,39,0.2)', color: '#FAC775', marginLeft: 4 }}>
            security scan
          </span>
        )}
        {!node.isDir && node.size !== undefined && (
          <span style={{ marginLeft: 'auto', fontSize: 10, color: 'var(--ink-3, rgba(238,236,234,0.4))', flexShrink: 0 }}>
            {formatSize(node.size)}
          </span>
        )}
      </button>
      {node.isDir && open && (
        <div>
          {node.children.map(child => (
            <TreeItem key={child.path} node={child} selected={selected} onSelect={onSelect} depth={depth + 1} />
          ))}
        </div>
      )}
    </div>
  )
}

/* ── File preview ── */

function FilePreview({ skillId, path, meta }: { skillId: string; path: string; meta: SkillFile | undefined }) {
  const [content, setContent] = useState<string | null | 'loading' | 'binary'>('loading')
  const [error, setError] = useState<string | null>(null)
  const inScripts = isInScripts(path)

  useEffect(() => {
    if (!path) return
    setContent('loading')
    setError(null)
    fetchSkillFile(skillId, path)
      .then(({ blob, contentType }) => {
        if (isBinary(contentType)) {
          setContent('binary')
          return
        }
        blob.text().then(t => setContent(t)).catch(() => setContent('binary'))
      })
      .catch(e => setError(ApiError.is(e) ? e.message : '載入失敗'))
  }, [skillId, path])

  return (
    <div style={{ flex: 1, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
      {/* Header */}
      <div style={{ padding: '8px 14px', borderBottom: '0.5px solid var(--line, rgba(255,255,255,0.08))', display: 'flex', alignItems: 'center', gap: 10, flexShrink: 0 }}>
        <span style={{ fontFamily: 'monospace', fontSize: 12, flex: 1 }}>{path}</span>
        {meta && <span style={{ fontSize: 11, color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>{formatSize(meta.size)}</span>}
        <LangBadge filename={path.split('/').pop() ?? path} />
      </div>
      {/* Security banner for scripts/ */}
      {inScripts && (
        <div data-testid="security-banner" style={{ padding: '6px 14px', background: 'rgba(239,159,39,0.1)', borderBottom: '0.5px solid rgba(239,159,39,0.3)', fontSize: 11, color: '#FAC775' }}>
          ⚠ 此檔案位於 scripts/ — 已進行安全掃描
        </div>
      )}
      {/* Content */}
      <div style={{ flex: 1, overflow: 'auto' }}>
        {error && <p style={{ padding: 16, color: 'var(--red-text, #F08080)', fontSize: 13 }}>{error}</p>}
        {content === 'loading' && <p style={{ padding: 16, color: 'var(--ink-3, rgba(238,236,234,0.4))', fontSize: 13 }}>載入中...</p>}
        {content === 'binary' && (
          <div data-testid="binary-fallback" className="ft-binary" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: 200, color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>
            <span style={{ fontSize: 32, marginBottom: 8 }}>📄</span>
            <span style={{ fontSize: 13 }}>二進制檔案 — 無法預覽</span>
          </div>
        )}
        {typeof content === 'string' && content !== 'loading' && content !== 'binary' && (
          <pre style={{ margin: 0, padding: '12px 14px', fontFamily: 'monospace', fontSize: 12, lineHeight: 1.75, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
            {content}
          </pre>
        )}
      </div>
    </div>
  )
}

/* ── Main component ── */

interface Props {
  skillId: string
}

/**
 * S142a — v2 File Explorer: 220px tree + 1fr preview split-pane.
 * scripts/ directory gets amber security styling.
 * Replaces S082 FilesPanel for v2 SkillDetailPage.
 */
export function FileExplorerPanel({ skillId }: Props) {
  const [selected, setSelected] = useState<string | null>('SKILL.md')
  const { data: files, isLoading, error, refetch } = useSkillFiles(skillId)

  if (isLoading) {
    return (
      <div style={{ height: 400, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--ink-3, rgba(238,236,234,0.4))', fontSize: 13 }}>
        載入檔案清單中...
      </div>
    )
  }

  if (error || !files) {
    return (
      <div data-testid="files-error" style={{ padding: 24, textAlign: 'center' }}>
        <p style={{ color: 'var(--red-text, #F08080)', fontSize: 13, marginBottom: 12 }}>
          檔案列表載入失敗
        </p>
        <button onClick={() => refetch()} style={{ fontSize: 12, padding: '6px 14px', background: 'rgba(255,255,255,0.06)', border: '0.5px solid var(--line-2, rgba(255,255,255,0.12))', borderRadius: 6, cursor: 'pointer' }}>
          重試
        </button>
      </div>
    )
  }

  const tree = buildTree(files)
  const selectedMeta = selected ? files.find(f => f.path === selected) : undefined

  return (
    <div
      data-testid="file-explorer-panel"
      style={{
        display: 'grid',
        gridTemplateColumns: '220px 1fr',
        height: 'calc(100vh - 300px)',
        minHeight: 400,
        border: '0.5px solid var(--line, rgba(255,255,255,0.08))',
        borderRadius: 12,
        overflow: 'hidden',
      }}
    >
      {/* File tree */}
      <div style={{ borderRight: '0.5px solid var(--line, rgba(255,255,255,0.08))', overflowY: 'auto', background: 'rgba(0,0,0,0.15)' }}>
        {tree.map(node => (
          <TreeItem key={node.path} node={node} selected={selected} onSelect={setSelected} depth={0} />
        ))}
      </div>

      {/* Preview */}
      {selected ? (
        <FilePreview skillId={skillId} path={selected} meta={selectedMeta} />
      ) : (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--ink-3, rgba(238,236,234,0.4))', fontSize: 13 }}>
          請從左側選取檔案
        </div>
      )}
    </div>
  )
}
