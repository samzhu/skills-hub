import { useEffect, useState } from 'react'
import { FileText, FileCode, Image as ImageIcon, File as FileIcon } from 'lucide-react'
import { useSkillFile, useSkillFiles } from '@/hooks/useSkillFiles'
import { ApiError } from '@/api/client'
import type { SkillFile } from '@/api/skills'

/**
 * S082: SkillDetailPage「檔案」tab 內容元件。
 *
 * 左側 file list（依 path 排序）+ 右側 content viewer。Text 類 MIME 直接 plain-text；
 * binary / 過大檔顯 fallback。SUSPENDED → API 回 403；DRAFT 無 PUBLISHED 版本 → 404；
 * 兩者於本元件統一 friendly 訊息。
 */
export function FilesPanel({ skillId }: { skillId: string }) {
  const [selectedPath, setSelectedPath] = useState<string>('SKILL.md')
  const { data: files, isLoading, error } = useSkillFiles(skillId)

  if (isLoading) {
    return <p className="text-sm text-muted-foreground">載入檔案清單中...</p>
  }

  if (error) {
    // 區分常見 status：403 SKILL_SUSPENDED / 404 (DRAFT 無版本) / 其他
    const status = ApiError.is(error) ? error.status : null
    let msg = '載入檔案清單時發生錯誤'
    if (status === 403) msg = '此技能已被停用，無法瀏覽檔案'
    else if (status === 404) msg = '此技能尚未發布版本，無檔案可瀏覽'
    return <p className="text-sm text-muted-foreground">{msg}</p>
  }

  if (!files || files.length === 0) {
    return <p className="text-sm text-muted-foreground">此技能套件不含任何檔案</p>
  }

  return (
    <div className="grid gap-4 md:grid-cols-[260px_1fr]">
      <FileList files={files} selected={selectedPath} onSelect={setSelectedPath} />
      <FileViewer skillId={skillId} path={selectedPath} files={files} />
    </div>
  )
}

/** 左欄：檔案清單，按 path 字典序排序，附 icon + size。 */
function FileList({
  files,
  selected,
  onSelect,
}: {
  files: SkillFile[]
  selected: string
  onSelect: (path: string) => void
}) {
  const sorted = [...files].sort((a, b) => a.path.localeCompare(b.path))
  return (
    <ul className="rounded-md border bg-card text-sm">
      {sorted.map((f) => {
        const isSel = f.path === selected
        return (
          <li key={f.path}>
            <button
              type="button"
              onClick={() => onSelect(f.path)}
              className={`flex w-full items-center justify-between gap-2 px-3 py-2 text-left hover:bg-muted ${
                isSel ? 'bg-muted font-medium' : ''
              }`}
            >
              <span className="flex items-center gap-2 overflow-hidden">
                <MimeIcon type={f.type} />
                <span className="truncate" title={f.path}>{f.path}</span>
              </span>
              <span className="shrink-0 text-xs text-muted-foreground">{formatSize(f.size)}</span>
            </button>
          </li>
        )
      })}
    </ul>
  )
}

/** 右欄：選中檔案的內容預覽。Text plain-text 渲染；binary / 過大顯 fallback。 */
function FileViewer({
  skillId,
  path,
  files,
}: {
  skillId: string
  path: string
  files: SkillFile[]
}) {
  const meta = files.find((f) => f.path === path)
  const { data, isLoading, error } = useSkillFile(skillId, path)

  if (!meta) {
    return <p className="text-sm text-muted-foreground">請從左側選取檔案</p>
  }

  if (isLoading) {
    return <p className="text-sm text-muted-foreground">載入 {path} 中...</p>
  }

  if (error) {
    const status = ApiError.is(error) ? error.status : null
    if (status === 413) {
      return (
        <div className="rounded-md border bg-card p-4 text-sm">
          <p className="font-medium">檔案過大，無法預覽</p>
          <p className="mt-1 text-muted-foreground">
            單檔預覽上限為 1 MB（此檔 {formatSize(meta.size)}）。請下載整包 zip 查看完整內容。
          </p>
        </div>
      )
    }
    return <p className="text-sm text-destructive">載入 {path} 時發生錯誤</p>
  }

  if (!data) return null

  if (isTextMime(data.contentType)) {
    return (
      <div className="overflow-hidden rounded-md border bg-card">
        <header className="flex items-center justify-between border-b px-3 py-2 text-xs text-muted-foreground">
          <span className="font-mono">{path}</span>
          <span>{data.contentType} · {formatSize(meta.size)}</span>
        </header>
        <pre className="max-h-[60vh] overflow-auto bg-muted/30 p-3 text-xs leading-relaxed font-mono">
          <TextContent blob={data.blob} />
        </pre>
      </div>
    )
  }

  // Binary fallback — image 嘗試 inline 顯示，其他顯示 metadata + 下載提示
  if (data.contentType.startsWith('image/')) {
    const url = URL.createObjectURL(data.blob)
    return (
      <div className="rounded-md border bg-card p-4">
        <p className="mb-2 text-xs text-muted-foreground font-mono">{path} · {data.contentType} · {formatSize(meta.size)}</p>
        {/* Vite app, not Next.js — native <img> is correct here */}
        <img src={url} alt={path} className="max-h-[60vh] max-w-full" />
      </div>
    )
  }

  return (
    <div className="rounded-md border bg-card p-4 text-sm">
      <p className="font-medium">此為 binary 檔案，無法預覽</p>
      <p className="mt-1 text-muted-foreground">
        路徑：<span className="font-mono">{path}</span><br />
        類型：<span className="font-mono">{data.contentType}</span><br />
        大小：{formatSize(meta.size)}
      </p>
      <p className="mt-2 text-xs text-muted-foreground">請下載整包 zip 查看完整內容。</p>
    </div>
  )
}

/** Blob → text decoded async, 顯示 plain-text content。 */
function TextContent({ blob }: { blob: Blob }) {
  const [text, setText] = useState<string>('')
  useEffect(() => {
    let cancelled = false
    blob.text().then((t) => { if (!cancelled) setText(t) })
    return () => { cancelled = true }
  }, [blob])
  return <>{text || '(空檔)'}</>
}

const TEXT_MIMES = new Set([
  'text/markdown',
  'text/plain',
  'text/html',
  'text/css',
  'text/csv',
  'text/x-python',
  'application/json',
  'application/yaml',
  'application/javascript',
  'application/typescript',
  'application/x-sh',
  'application/toml',
  'application/xml',
])

function isTextMime(ct: string): boolean {
  // 去掉 ;charset=... 後比對
  const base = ct.split(';')[0].trim()
  if (TEXT_MIMES.has(base)) return true
  return base.startsWith('text/')
}

function formatSize(bytes: number): string {
  if (bytes < 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

function MimeIcon({ type }: { type: string }) {
  const cls = 'h-3.5 w-3.5 shrink-0 text-muted-foreground'
  if (type.startsWith('text/') || type === 'application/json' || type === 'application/yaml') {
    return <FileText className={cls} />
  }
  if (
    type === 'application/javascript' ||
    type === 'application/typescript' ||
    type === 'application/x-sh' ||
    type === 'text/x-python'
  ) {
    return <FileCode className={cls} />
  }
  if (type.startsWith('image/')) {
    return <ImageIcon className={cls} />
  }
  return <FileIcon className={cls} />
}
