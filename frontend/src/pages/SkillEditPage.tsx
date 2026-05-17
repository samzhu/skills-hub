import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { AlertCircle, ArrowLeft, Check, FileText, Upload as UploadIcon } from 'lucide-react'
import { AppShell } from '@/components/AppShell'
import { ErrorState } from '@/components/ErrorState'
import { useSkill } from '@/hooks/useSkill'
import { useSkillFile } from '@/hooks/useSkillFiles'
import { validateFrontmatter } from './PublishPage.utils'

type EditMode = 'text' | 'upload'

/**
 * S187-T02：SKILL.md 編輯頁 shell。
 * 目前完成 latest SKILL.md text mode；submit / upload mode 留給後續 S187 tasks。
 */
export function SkillEditPage() {
  const { id = '' } = useParams<{ id: string }>()
  const skillQuery = useSkill(id)
  const skillMdQuery = useSkillFile(id, 'SKILL.md')
  const [mode, setMode] = useState<EditMode>('text')
  const [skillMdText, setSkillMdText] = useState('')
  const [hydratedForSkill, setHydratedForSkill] = useState<string | null>(null)
  const [fileReadError, setFileReadError] = useState<string | null>(null)

  useEffect(() => {
    if (!skillMdQuery.data || hydratedForSkill === id) return

    let cancelled = false
    skillMdQuery.data.blob.text()
      .then((text) => {
        if (cancelled) return
        setSkillMdText(text)
        setHydratedForSkill(id)
        setFileReadError(null)
      })
      .catch((err) => {
        if (cancelled) return
        console.error('[SkillEditPage] 讀取 SKILL.md 失敗', err)
        setFileReadError('SKILL.md 內容讀取失敗，請切換上傳檔案或稍後重試。')
      })

    return () => {
      cancelled = true
    }
  }, [hydratedForSkill, id, skillMdQuery.data])

  const fmValidation = useMemo(
    () => validateFrontmatter(skillMdText),
    [skillMdText],
  )
  const canSaveText = mode === 'text'
    && skillMdText.trim().length > 0
    && fmValidation.errors.length === 0
    && !skillMdQuery.isLoading
    && !fileReadError
  const saveDisabled = mode !== 'text' || !canSaveText

  const loading = skillQuery.isLoading || skillMdQuery.isLoading
  const skillName = skillQuery.data?.name ?? id

  return (
    <AppShell>
      <div className="mx-auto max-w-4xl">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div className="min-w-0">
            <Link
              to={`/skills/${id}`}
              className="mb-2 inline-flex items-center gap-1.5 text-[12px] text-muted-foreground hover:text-foreground"
            >
              <ArrowLeft className="h-3.5 w-3.5" />
              返回技能
            </Link>
            <h1 className="m-0 text-[22px] font-medium leading-[1.2]">編輯 SKILL.md</h1>
            <p className="mt-1 text-[13px] text-muted-foreground">
              {skillName}
              {skillQuery.data?.latestVersion && (
                <span className="ml-2 font-mono text-[12px]">latest {skillQuery.data.latestVersion}</span>
              )}
            </p>
          </div>
          <div className="flex items-center gap-2">
            <Link
              to={`/skills/${id}`}
              className="inline-flex h-9 items-center justify-center rounded-md border border-border px-3 text-[13px] text-muted-foreground hover:bg-muted hover:text-foreground"
            >
              取消
            </Link>
            <button
              type="button"
              className="inline-flex h-9 items-center justify-center rounded-md border border-border px-3 text-[13px] text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-50"
              disabled
            >
              儲存分類
            </button>
            <button
              type="button"
              disabled={saveDisabled}
              className="inline-flex h-9 items-center justify-center rounded-md bg-primary px-4 text-[13px] font-medium text-primary-foreground hover:bg-foreground disabled:opacity-50"
            >
              儲存新版本
            </button>
          </div>
        </div>

        {skillQuery.isError && (
          <ErrorState
            className="mb-4"
            title="載入技能失敗"
            message="請重新整理頁面後再試一次。"
          />
        )}
        {skillMdQuery.isError && (
          <ErrorState
            className="mb-4"
            title="載入 SKILL.md 失敗"
            message="你仍可切換到上傳檔案模式，或稍後再試。"
          />
        )}
        {fileReadError && (
          <ErrorState className="mb-4" title="讀取 SKILL.md 失敗" message={fileReadError} />
        )}

        <div className="rounded-lg border border-border bg-card p-5">
          <div className="mb-4 inline-flex rounded-md border border-border bg-secondary p-0.5 text-[12px]">
            <ModeTab active={mode === 'text'} onClick={() => setMode('text')}>
              <FileText className="h-3 w-3" /> 貼上文本
            </ModeTab>
            <ModeTab active={mode === 'upload'} onClick={() => setMode('upload')}>
              <UploadIcon className="h-3 w-3" /> 上傳檔案
            </ModeTab>
          </div>

          {mode === 'text' ? (
            <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_220px]">
              <div>
                <label
                  htmlFor="skill-edit-skill-md"
                  className="mb-1.5 block text-[12px] font-medium uppercase tracking-wide text-muted-foreground"
                >
                  SKILL.md 內容
                </label>
                <textarea
                  id="skill-edit-skill-md"
                  data-testid="skill-edit-skill-md-textarea"
                  value={skillMdText}
                  onChange={(e) => setSkillMdText(e.target.value)}
                  disabled={loading}
                  rows={22}
                  className="min-h-[420px] w-full resize-y rounded-md border border-border bg-background px-3 py-2 font-mono text-[12.5px] leading-relaxed text-foreground placeholder:text-muted-foreground focus:border-[rgba(255,255,255,0.20)] focus:outline-none disabled:opacity-70"
                  placeholder="正在讀取 latest SKILL.md..."
                />
              </div>

              <aside className="space-y-3">
                <section className="rounded-md border border-[rgba(255,255,255,0.06)] bg-[#0F0F12] p-3">
                  <h2 className="mb-2 text-[12px] font-medium text-foreground">Frontmatter 檢查</h2>
                  <div className="space-y-1.5 text-[12px]">
                    <ValidationCheck
                      testId="frontmatter-block-check"
                      label="frontmatter"
                      passed={fmValidation.hasFrontmatter}
                    />
                    <ValidationCheck
                      testId="frontmatter-name-check"
                      label="name"
                      passed={fmValidation.hasName}
                    />
                    <ValidationCheck
                      testId="frontmatter-description-check"
                      label="description"
                      passed={fmValidation.hasDescription}
                    />
                  </div>
                  {fmValidation.errors.length > 0 && (
                    <ul className="mt-2 list-disc pl-5 text-[11.5px] leading-relaxed text-[#F2A6A6]">
                      {fmValidation.errors.map((err) => (
                        <li key={err}>{err}</li>
                      ))}
                    </ul>
                  )}
                </section>
              </aside>
            </div>
          ) : (
            <div className="rounded-md border border-dashed border-border bg-background p-6 text-[13px] text-muted-foreground">
              上傳檔案模式會在下一個步驟接上送出流程。
            </div>
          )}
        </div>
      </div>
    </AppShell>
  )
}

function ValidationCheck({
  testId,
  label,
  passed,
}: {
  testId: string
  label: string
  passed: boolean
}) {
  return (
    <div data-testid={testId} className="flex items-center gap-2 leading-relaxed">
      {passed ? (
        <Check className="h-3.5 w-3.5 shrink-0 text-[#6FD8B0]" />
      ) : (
        <AlertCircle className="h-3.5 w-3.5 shrink-0 text-[#A8A49C]" />
      )}
      <span className={passed ? 'text-[#6FD8B0]' : 'text-muted-foreground'}>
        {label} {passed ? '已通過' : '缺少'}
      </span>
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
  children: ReactNode
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
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
