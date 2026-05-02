import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router'
import { useMutation } from '@tanstack/react-query'
import { FileText, Upload as UploadIcon, Check, AlertCircle } from 'lucide-react'
import { AppShell } from '@/components/AppShell'
import { FileDropZone } from '@/components/FileDropZone'
import { ErrorState } from '@/components/ErrorState'
import { Input } from '@/components/ui/input'
import { uploadSkill } from '@/api/skills'
import { localizeApiError } from '@/lib/api-error-messages'

/**
 * S099b2 — Frontmatter live validation。
 * 簡化 parser：檢 `---\n...\n---` block 存在 + 內含 `name:` 與 `description:` 欄位。
 * 不做完整 YAML parse（backend 負責）；只 fail-fast 給作者立刻 feedback。
 *
 * Returns { hasFrontmatter, hasName, hasDescription, errors }；errors 是中文人類訊息列表。
 */
export function validateFrontmatter(content: string): {
  hasFrontmatter: boolean
  hasName: boolean
  hasDescription: boolean
  errors: string[]
} {
  const errors: string[] = []
  const trimmed = content.trim()
  if (!trimmed) {
    return { hasFrontmatter: false, hasName: false, hasDescription: false, errors }
  }
  // 必須以 --- 開頭
  if (!trimmed.startsWith('---')) {
    errors.push('SKILL.md 必須以 YAML frontmatter 開頭（首行 ---）')
    return { hasFrontmatter: false, hasName: false, hasDescription: false, errors }
  }
  // 抽 frontmatter block — 從第 1 個 --- 到第 2 個 ---
  const lines = trimmed.split('\n')
  let endIdx = -1
  for (let i = 1; i < lines.length; i++) {
    if (lines[i].trim() === '---') {
      endIdx = i
      break
    }
  }
  if (endIdx === -1) {
    errors.push('Frontmatter 缺少結束 ---（需在第 N 行單獨一個 ---）')
    return { hasFrontmatter: false, hasName: false, hasDescription: false, errors }
  }
  const fmBlock = lines.slice(1, endIdx)
  // 必填欄位 — 簡單 startsWith 檢查（不處理 quote / multiline 情況）
  const hasName = fmBlock.some((l) => /^name:\s*\S/.test(l))
  const hasDescription = fmBlock.some((l) => /^description:\s*\S/.test(l))
  if (!hasName) errors.push('缺必填欄位：name')
  if (!hasDescription) errors.push('缺必填欄位：description')
  return { hasFrontmatter: true, hasName, hasDescription, errors }
}

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

  // S099b2: live frontmatter validation only when text mode active
  const fmValidation = useMemo(
    () => validateFrontmatter(skillMdText),
    [skillMdText],
  )

  // submit disable rule per mode；text mode 加 frontmatter validation gate
  const submitDisabled = mutation.isPending
    || (mode === 'file'
      ? !file
      : skillMdText.trim().length === 0 || fmValidation.errors.length > 0)

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
                {/* S099b2: live validation feedback — 顯給作者 quick check */}
                {skillMdText.trim().length > 0 && (
                  <div className="mt-2 rounded-md border border-[rgba(255,255,255,0.06)] bg-[#0F0F12] p-2 text-[12px]">
                    <ValidationCheck label="Frontmatter block (---)" passed={fmValidation.hasFrontmatter} />
                    <ValidationCheck label="必填欄位：name" passed={fmValidation.hasName} />
                    <ValidationCheck label="必填欄位：description" passed={fmValidation.hasDescription} />
                    {fmValidation.errors.length > 0 && (
                      <ul className="mt-1.5 list-disc pl-5 text-[11.5px] text-[#F2A6A6]">
                        {fmValidation.errors.map((err, i) => (
                          <li key={i}>{err}</li>
                        ))}
                      </ul>
                    )}
                  </div>
                )}
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
            <ErrorState
              className="mt-4"
              title="發佈失敗"
              message={localizeApiError(mutation.error)}
            />
          )}
        </div>
      </div>
    </AppShell>
  )
}

function ValidationCheck({ label, passed }: { label: string; passed: boolean }) {
  return (
    <div className="flex items-center gap-2 text-[11.5px] leading-relaxed">
      {passed ? (
        <Check className="h-3 w-3 shrink-0 text-[#6FD8B0]" />
      ) : (
        <AlertCircle className="h-3 w-3 shrink-0 text-[#A8A49C]" />
      )}
      <span className={passed ? 'text-[#6FD8B0]' : 'text-muted-foreground'}>{label}</span>
    </div>
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
