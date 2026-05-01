import { useState } from 'react'
import { Link } from 'react-router'
import { useMutation } from '@tanstack/react-query'
import { AppShell } from '@/components/AppShell'
import { FileDropZone } from '@/components/FileDropZone'
import { Input } from '@/components/ui/input'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
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

  const mutation = useMutation({
    mutationFn: () => {
      if (!file) throw new Error('請選取檔案')
      return uploadSkill(file, version, author, category)
    },
    onError: (err) => {
      console.error('[PublishPage] 發佈技能失敗', err)
    },
  })

  /** 表單送出：阻止預設行為後觸發 mutation */
  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    mutation.mutate()
  }

  return (
    <AppShell>
      <div className="mx-auto max-w-2xl">
        <h1 className="mb-6 text-2xl font-bold">發佈新技能</h1>

        <Card>
          <CardHeader>
            <CardTitle>上傳 Skill 套件</CardTitle>
          </CardHeader>
          <CardContent>
            <form onSubmit={handleSubmit} className="space-y-4">
              <FileDropZone onFileSelect={setFile} selectedFile={file} />

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="mb-1 block text-sm font-medium">版本號</label>
                  <Input
                    value={version}
                    onChange={(e) => setVersion(e.target.value)}
                    placeholder="1.0.0"
                    required
                    // S067: HTML5 pattern 預驗 semver — 對齊 backend Skill.VERSION_REGEX (S056)
                    // 陷阱：(1) HTML5 自動 wrap ^(?:pattern)$，pattern 本身不要寫 ^...$；
                    //       (2) Chrome 對字元 class 內未 escape 的 `.` 與 `-` 會 silent 停用整個 pattern；
                    //          必須寫 `\.\-`，pattern 才實際生效
                    pattern="\d+\.\d+\.\d+(-[A-Za-z0-9\.\-]+)?"
                    title="格式：MAJOR.MINOR.PATCH（如 1.0.0 或 2.0.0-rc.1）"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium">分類</label>
                  <Input
                    value={category}
                    onChange={(e) => setCategory(e.target.value)}
                    placeholder="DevOps"
                    required
                    // S068: 對齊 backend skills.category varchar(50) 上限
                    maxLength={50}
                  />
                </div>
              </div>

              <div>
                <label className="mb-1 block text-sm font-medium">作者</label>
                <Input
                  value={author}
                  onChange={(e) => setAuthor(e.target.value)}
                  placeholder="your-name"
                  required
                  // S068: 對齊 backend skills.author varchar(255) 上限
                  maxLength={255}
                />
              </div>

              {/* file 未選取或上傳中時停用提交按鈕 */}
              <button
                type="submit"
                disabled={!file || mutation.isPending}
                className="w-full rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
              >
                {mutation.isPending ? '上傳中...' : '發佈技能'}
              </button>
            </form>

            {mutation.isSuccess && (
              <div className="mt-4 rounded-md bg-green-50 p-4 text-green-800">
                <p className="font-medium">發佈成功！</p>
                <p className="text-sm">Skill ID: {mutation.data.id}</p>
                <Link
                  to={`/skills/${mutation.data.id}`}
                  className="mt-1 inline-block text-sm font-medium text-green-700 underline"
                >
                  查看技能 →
                </Link>
              </div>
            )}

            {mutation.isError && (
              <div className="mt-4 rounded-md bg-red-50 p-4 text-red-800">
                <p className="font-medium">發佈失敗</p>
                {/* S040: 後端 error code 翻譯為繁中；未知 code fallback 至 error.message */}
                <p className="text-sm">{localizeApiError(mutation.error)}</p>
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </AppShell>
  )
}
