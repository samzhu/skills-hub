import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useMutation } from '@tanstack/react-query'
import { AlertCircle } from 'lucide-react'
import { AppShell } from '@/components/AppShell'
import { FileDropZone } from '@/components/FileDropZone'
import { Input } from '@/components/ui/input'
import { uploadSkill } from '@/api/skills'
import { localizeApiError } from '@/lib/api-error-messages'

/**
 * 技能發佈頁：提供表單讓使用者上傳新的 Skill zip 套件。
 *
 * 表單欄位包含：zip 檔案、版本號、分類、作者。
 * 上傳成功後顯示後端分配的 Skill ID，並提供跳轉至詳情頁的連結。
 *
 * 版本號預設為 `1.0.0`（首次發佈最常見的起始版本）。
 * 上傳成功後不重置表單，方便使用者微調後再次發佈。
 */
export function PublishPage() {
  const [file, setFile] = useState<File | null>(null)
  // 預填 1.0.0 作為首次發佈的慣例起始版本
  const [version, setVersion] = useState('1.0.0')
  const [author, setAuthor] = useState('')
  const [category, setCategory] = useState('')
  const navigate = useNavigate()

  const mutation = useMutation({
    mutationFn: () => {
      if (!file) throw new Error('請選取檔案')
      return uploadSkill(file, version, author, category)
    },
    onSuccess: (data) => {
      // S098a: 上傳成功後 navigate 到 /publish/validate?id=X 中介頁
      // — 顯 4-step stepper + scan polling；scan 完成 (riskLevel 設值) 自動跳 /publish/review
      // 取代 S096d4a 直接跳 review（user 在 review 看到 spinner 心智模型不清；validate 頁明示 stepper 狀態）
      navigate(`/publish/validate?id=${data.id}`)
    },
    onError: (err) => {
      console.error('[PublishPage] 發佈技能失敗', err)
      // S098b: redirect to dedicated /publish/failed?state=A page (frontmatter / upload error).
      // msg query 帶錯誤訊息（已 localize）— bookmarkable + sharable for support。
      const msg = encodeURIComponent(localizeApiError(err))
      navigate(`/publish/failed?state=A&msg=${msg}`)
    },
  })

  /** 表單送出：阻止預設行為後觸發 mutation */
  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    mutation.mutate()
  }

  return (
    <AppShell>
      {/* S086: 對齊 prototype `skill_publish_upload_flow.html` — 居中收斂 hero + card */}
      <div className="mx-auto max-w-2xl">
        <div className="mb-[14px]">
          <h1 className="m-0 text-[22px] font-medium leading-[1.2]">發佈新技能</h1>
          <p className="mt-1 text-[13px] text-muted-foreground">
            上傳 SKILL.md zip 套件 — 系統會自動驗證、掃描風險並產生分類索引
          </p>
        </div>

        <div className="rounded-lg border border-border bg-card p-5">
          <h2 className="mb-4 text-sm font-medium text-foreground">上傳 Skill 套件</h2>
          <form onSubmit={handleSubmit} className="space-y-4">
            <FileDropZone onFileSelect={setFile} selectedFile={file} />

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
              disabled={!file || mutation.isPending}
              className="w-full rounded-md bg-primary px-4 py-2 text-[13px] font-medium text-primary-foreground hover:bg-foreground disabled:opacity-50"
            >
              {mutation.isPending ? '上傳中...' : '發佈技能'}
            </button>
          </form>

          {/* S096d4a: success state 改 navigate 到 /publish/review?id=X — 不再顯 inline card */}

          {/* S086: error callout per DESIGN.md card-callout-danger with danger-soft + danger-deep */}
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
