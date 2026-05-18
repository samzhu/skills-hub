import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { AlertCircle, ArrowLeft, Check, FileText, Upload as UploadIcon } from 'lucide-react'
import { ApiError } from '@/api/client'
import { addVersion, updateSkill } from '@/api/skills'
import { skillKeys } from '@/api/queryKeys'
import { AppShell } from '@/components/AppShell'
import { ErrorState } from '@/components/ErrorState'
import { FileDropZone } from '@/components/FileDropZone'
import { useSkill } from '@/hooks/useSkill'
import { useSkillFile } from '@/hooks/useSkillFiles'
import { localizeApiError } from '@/lib/api-error-messages'
import type { ValidationFinding } from '@/types/skill'
import { validateFrontmatter } from './PublishPage.utils'

type EditMode = 'text' | 'upload'

/**
 * S187-T02/T03：SKILL.md 編輯頁 shell + 新版本送出流程。
 */
export function SkillEditPage() {
  const { id = '' } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const skillQuery = useSkill(id)
  const skillMdQuery = useSkillFile(id, 'SKILL.md')
  const [mode, setMode] = useState<EditMode>('text')
  const [skillMdText, setSkillMdText] = useState('')
  const [version, setVersion] = useState('')
  const [categoryDraft, setCategoryDraft] = useState('')
  const [categoryTouched, setCategoryTouched] = useState(false)
  const [selectedFile, setSelectedFile] = useState<File | null>(null)
  const [hydratedForSkill, setHydratedForSkill] = useState<string | null>(null)
  const [fileReadError, setFileReadError] = useState<string | null>(null)
  const currentCategory = skillQuery.data?.categoryDisplay ?? skillQuery.data?.category ?? ''
  const category = categoryTouched ? categoryDraft : currentCategory
  const trimmedCategory = category.trim()

  const addVersionMutation = useMutation({
    mutationFn: () => {
      const file = mode === 'text'
        ? new File([skillMdText], 'SKILL.md', { type: 'text/markdown' })
        : selectedFile
      if (!file) throw new Error('請選取檔案或貼上 SKILL.md 內容')
      return addVersion(id, file, version)
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: skillKeys.detail(id) }),
        queryClient.invalidateQueries({ queryKey: ['skills', id, 'versions'] }),
        queryClient.invalidateQueries({ queryKey: ['skills', id, 'files'] }),
      ])
      navigate(`/publish/validate?id=${id}&mode=version`)
    },
  })

  const updateCategoryMutation = useMutation({
    mutationFn: () => updateSkill(id, { category: trimmedCategory }),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: skillKeys.detail(id) }),
        queryClient.invalidateQueries({ queryKey: ['skills'] }),
      ])
    },
  })

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
  const canSaveUpload = mode === 'upload' && selectedFile != null
  const saveDisabled = addVersionMutation.isPending || (mode === 'text' ? !canSaveText : !canSaveUpload)
  const categorySaveDisabled = updateCategoryMutation.isPending
    || trimmedCategory.length === 0
    || trimmedCategory === currentCategory.trim()

  const loading = skillQuery.isLoading || skillMdQuery.isLoading
  const skillName = skillQuery.data?.name ?? id
  const handleSaveVersion = () => {
    addVersionMutation.mutate()
  }
  const handleSaveCategory = () => {
    updateCategoryMutation.mutate()
  }

  return (
    <AppShell>
      <div className="mx-auto max-w-4xl">
        <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between">
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
          <div data-testid="skill-edit-actions" className="grid w-full grid-cols-1 gap-2 min-[420px]:grid-cols-3 sm:w-auto sm:flex sm:items-center">
            <Link
              to={`/skills/${id}`}
              className="inline-flex h-9 items-center justify-center rounded-md border border-border px-3 text-[13px] text-muted-foreground hover:bg-muted hover:text-foreground"
            >
              取消
            </Link>
            <button
              type="button"
              className="inline-flex h-9 items-center justify-center rounded-md border border-border px-3 text-[13px] text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-50"
              disabled={categorySaveDisabled}
              onClick={handleSaveCategory}
            >
              {updateCategoryMutation.isPending ? '儲存中...' : '儲存分類'}
            </button>
            <button
              type="button"
              disabled={saveDisabled}
              onClick={handleSaveVersion}
              className="inline-flex h-9 items-center justify-center rounded-md bg-primary px-4 text-[13px] font-medium text-primary-foreground hover:bg-foreground disabled:opacity-50"
            >
              {addVersionMutation.isPending ? '儲存中...' : '儲存新版本'}
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
        {addVersionMutation.isError && (
          <ErrorState
            className="mb-4"
            title={primaryValidationMessage(addVersionMutation.error) ?? '儲存新版本失敗'}
            message={(
              <>
                <span>{versionErrorMessage(addVersionMutation.error)}</span>
                <ValidationFindingsList findings={validationFindings(addVersionMutation.error)} />
              </>
            )}
          />
        )}
        {updateCategoryMutation.isError && (
          <ErrorState
            className="mb-4"
            title="儲存分類失敗"
            message={localizeApiError(updateCategoryMutation.error)}
          />
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

          <div className="mb-4">
            <label
              htmlFor="skill-edit-category"
              className="mb-1.5 block text-[12px] font-medium uppercase tracking-wide text-muted-foreground"
            >
              分類
            </label>
            <input
              id="skill-edit-category"
              value={category}
              onChange={(e) => {
                setCategoryTouched(true)
                setCategoryDraft(e.target.value)
              }}
              placeholder="DevOps"
              className="h-9 w-full max-w-xs rounded-md border border-border bg-background px-3 text-[13px] text-foreground placeholder:text-muted-foreground focus:border-[rgba(255,255,255,0.20)] focus:outline-none"
            />
          </div>

          <div className="mb-4">
            <label
              htmlFor="skill-edit-version"
              className="mb-1.5 block text-[12px] font-medium uppercase tracking-wide text-muted-foreground"
            >
              版本號
            </label>
            <input
              id="skill-edit-version"
              value={version}
              onChange={(e) => setVersion(e.target.value)}
              placeholder="留白自動產生"
              className="h-9 w-full max-w-xs rounded-md border border-border bg-background px-3 font-mono text-[13px] text-foreground placeholder:text-muted-foreground focus:border-[rgba(255,255,255,0.20)] focus:outline-none"
            />
            <p className="mt-1 text-[11px] text-muted-foreground">
              留白時系統沿用後端自動流水號；填值時會建立該版本標籤。
            </p>
          </div>

          {mode === 'text' ? (
            <TextModeEditor
              skillMdText={skillMdText}
              setSkillMdText={setSkillMdText}
              loading={loading}
              fmValidation={fmValidation}
            />
          ) : (
            <UploadMode selectedFile={selectedFile} setSelectedFile={setSelectedFile} />
          )}
        </div>
      </div>
    </AppShell>
  )
}

function validationFindings(error: unknown): ValidationFinding[] {
  if (!ApiError.is(error)) return []
  return error.findings ?? []
}

function primaryValidationMessage(error: unknown): string | null {
  const findings = validationFindings(error)
  return findings.find((finding) => finding.severity === 'error')?.title
    ?? findings[0]?.title
    ?? null
}

function versionErrorMessage(error: unknown): string {
  const findings = validationFindings(error)
  if (findings.length > 0 && ApiError.is(error) && error.message) {
    return `儲存新版本失敗：${error.message}`
  }
  return localizeApiError(error)
}

function ValidationFindingsList({ findings }: { findings: ValidationFinding[] }) {
  if (findings.length === 0) return null

  return (
    <span className="mt-2 block space-y-1 text-[12px] leading-relaxed">
      {findings.map((finding, index) => (
        <span key={`${finding.section}-${finding.title}-${index}`} className="block">
          <span className="block break-words">{`${finding.severity} · ${finding.section} · ${finding.title}`}</span>
          {finding.hint && <span className="mt-0.5 block break-words opacity-80">{finding.hint}</span>}
        </span>
      ))}
    </span>
  )
}

function TextModeEditor({
  skillMdText,
  setSkillMdText,
  loading,
  fmValidation,
}: {
  skillMdText: string
  setSkillMdText: (value: string) => void
  loading: boolean
  fmValidation: ReturnType<typeof validateFrontmatter>
}) {
  return (
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
  )
}

function UploadMode({
  selectedFile,
  setSelectedFile,
}: {
  selectedFile: File | null
  setSelectedFile: (file: File | null) => void
}) {
  return (
    <div className="rounded-md border border-border bg-background p-4">
      <label
        htmlFor="skill-edit-file"
        className="mb-1.5 block text-[12px] font-medium uppercase tracking-wide text-muted-foreground"
      >
        Skill 套件
      </label>
      <FileDropZone
        inputId="skill-edit-file"
        selectedFile={selectedFile}
        onFileSelect={setSelectedFile}
      />
      <p className="mt-2 text-[12px] text-muted-foreground">
        可上傳 zip 套件或單一 SKILL.md。
      </p>
    </div>
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
