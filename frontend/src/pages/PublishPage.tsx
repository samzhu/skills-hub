import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useMutation } from '@tanstack/react-query'
import { AlertCircle, FileText, Upload as UploadIcon } from 'lucide-react'
import { AppShell } from '@/components/AppShell'
import { FileDropZone } from '@/components/FileDropZone'
import { Input } from '@/components/ui/input'
import { uploadSkill } from '@/api/skills'
import { localizeApiError } from '@/lib/api-error-messages'

/**
 * 技能發佈頁：提供雙 mode 上傳新 Skill。
 *
 * - **檔案 mode**（default）：FileDropZone 接受 .zip / .md
 * - **S099b 文本 mode**：textarea 直接貼 SKILL.md 內容；submit 時 synthesize
 *   `new File([text], 'SKILL.md', { type: 'text/markdown' })` reuse 既有
 *   uploadSkill mutation。零 backend 改動 — backend S053 已支援 raw .md。
 *
 * 上傳成功後 navigate 到 /publish/validate?id=X stepper page。
 */
type Mode = 'file' | 'text'

export function PublishPage() {
  const [mode, setMode] = useState<Mode>('file')
  const [file, setFile] = useState<File | null>(null)
  const [skillMdText, setSkillMdText] = useState('')
  // 預填 1.0.0 作為首次發佈的慣例起始版本
  const [version, setVersion] = useState('1.0.0')
  const [author, setAuthor] = useState('')
  const [category, setCategory] = useState('')
  const navigate = useNavigate()

  const mutation = useMutation({
    mutationFn: () => {
      // S099b: text mode 把 textarea 內容包成 synthetic File 走 .md 路徑
      // — 沿用 backend uploadSkill 既有 byte[] 處理（S053 raw .md 支援）
      const submitFile = mode === 'text'
        ? new File([skillMdText], 'SKILL.md', { type: 'text/markdown' })
        : file
      if (!submitFile) throw new Error('請選取檔案或貼上 SKILL.md 內容')
      return uploadSkill(submitFile, version, author, category)
    },
    onSuccess: (data) => {
      navigate(`/publish/validate?id=${data.id}`)
    },
    onError: (err) => {
      console.error('[PublishPage] 發佈技能失敗', err)
      const msg = encodeURIComponent(localizeApiError(err))
      navigate(`/publish/failed?state=A&msg=${msg}`)
    },
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    mutation.mutate()
  }

  // submit disable rule per mode
  const submitDisabled = mutation.isPending
    || (mode === 'file' ? !file : skillMdText.trim().length === 0)

  return (
    <AppShell>
      <div className="mx-auto max-w-2xl">
        <div className="mb-[14px]">
          <h1 className="m-0 text-[22px] font-medium leading-[1.2]">發佈新技能</h1>
          <p className="mt-1 text-[13px] text-muted-foreground">
            上傳 zip 套件 或 直接貼上 SKILL.md — 系統會自動驗證、掃描風險並產生分類索引
          </p>
        </div>

        <div className="rounded-lg border border-border bg-card p-5">
          {/* S099b: mode tabs */}
          <div className="mb-4 inline-flex rounded-md border border-border bg-secondary p-0.5 text-[12px]">
            <ModeTab active={mode === 'file'} onClick={() => setMode('file')}>
              <UploadIcon className="h-3 w-3" /> 上傳檔案
            </ModeTab>
            <ModeTab active={mode === 'text'} onClick={() => setMode('text')}>
              <FileText className="h-3 w-3" /> 貼上文本
            </ModeTab>
          </div>

          <h2 className="mb-4 text-sm font-medium text-foreground">
            {mode === 'file' ? '上傳 Skill 套件' : '貼上 SKILL.md 內容'}
          </h2>
          <form onSubmit={handleSubmit} className="space-y-4">
            {mode === 'file' ? (
              <FileDropZone onFileSelect={setFile} selectedFile={file} />
            ) : (
              <div>
                <textarea
                  value={skillMdText}
                  onChange={(e) => setSkillMdText(e.target.value)}
                  placeholder={`---\nname: my-skill\ndescription: 一段精煉描述「skill 做什麼 + agent 何時該呼叫」\nversion: 1.0.0\nlicense: MIT\n---\n\n# My Skill\n\nInvoke this skill when ...`}
                  required
                  rows={14}
                  className="w-full rounded-md border border-border bg-background px-3 py-2 font-mono text-[12.5px] leading-relaxed text-foreground placeholder:text-muted-foreground focus:border-[rgba(255,255,255,0.20)] focus:outline-none"
                />
                <p className="mt-1 text-[11px] text-muted-foreground">
                  含 YAML frontmatter（必填 <code className="rounded bg-secondary px-1 py-0.5 font-mono text-[11px]">name</code> + <code className="rounded bg-secondary px-1 py-0.5 font-mono text-[11px]">description</code>）+ markdown 內文。詳見 <a href="/docs/skill-md-spec" className="text-[#C9C5F2] hover:underline">SKILL.md 規範</a>。
                </p>
              </div>
            )}

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="mb-1.5 block text-[12px] font-medium text-muted-foreground uppercase tracking-wide">版本號</label>
                <Input
                  value={version}
                  onChange={(e) => setVersion(e.target.value)}
                  placeholder="1.0.0"
                  required
                  pattern="\d+\.\d+\.\d+(-[A-Za-z0-9\.\-]+)?"
                  title="格式：MAJOR.MINOR.PATCH（如 1.0.0 或 2.0.0-rc.1）"
                  className="font-mono"
                />
              </div>
              <div>
                <label className="mb-1.5 block text-[12px] font-medium text-muted-foreground uppercase tracking-wide">分類</label>
                <Input
                  value={category}
                  onChange={(e) => setCategory(e.target.value)}
                  placeholder="DevOps"
                  required
                  maxLength={50}
                />
              </div>
            </div>

            <div>
              <label className="mb-1.5 block text-[12px] font-medium text-muted-foreground uppercase tracking-wide">作者</label>
              <Input
                value={author}
                onChange={(e) => setAuthor(e.target.value)}
                placeholder="your-name"
                required
                maxLength={255}
              />
            </div>

            <button
              type="submit"
              disabled={submitDisabled}
              className="w-full rounded-md bg-primary px-4 py-2 text-[13px] font-medium text-primary-foreground hover:bg-foreground disabled:opacity-50"
            >
              {mutation.isPending ? '上傳中...' : '發佈技能'}
            </button>
          </form>

          {mutation.isError && (
            <div
              className="mt-4 flex items-start gap-3 rounded-md p-3 text-[13px]"
              style={{ backgroundColor: 'rgba(226,75,74,0.14)', color: '#F2A6A6' }}
            >
              <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
              <div className="flex-1">
                <p className="m-0 font-medium">發佈失敗</p>
                <p className="m-0 mt-0.5 text-[12px] opacity-90">{localizeApiError(mutation.error)}</p>
              </div>
            </div>
          )}
        </div>
      </div>
    </AppShell>
  )
}

function ModeTab({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={
        'inline-flex items-center gap-1.5 rounded-[5px] px-3 py-1.5 transition-colors ' +
        (active
          ? 'bg-card font-medium text-foreground shadow-sm'
          : 'text-muted-foreground hover:text-foreground')
      }
    >
      {children}
    </button>
  )
}
