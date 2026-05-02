import { useState } from 'react'
import { useParams, Link } from 'react-router'
import { ArrowLeft, Download, AlertCircle } from 'lucide-react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { AppShell } from '@/components/AppShell'
import { Input } from '@/components/ui/input'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Separator } from '@/components/ui/separator'
import { RiskBadge } from '@/components/RiskBadge'
import { IconTile } from '@/components/IconTile'
import { MetricCard } from '@/components/MetricCard'
import { VersionList } from '@/components/VersionList'
import { FileDropZone } from '@/components/FileDropZone'
import { FilesPanel } from '@/components/FilesPanel'
import { useSkill, useSkillByAuthorAndName } from '@/hooks/useSkill'
import { useVersions } from '@/hooks/useVersions'
import { addVersion } from '@/api/skills'
import { ApiError } from '@/api/client'
import { localizeApiError } from '@/lib/api-error-messages'
import type { RiskLevel, SkillStatus } from '@/types/skill'

/**
 * S028 — 技能狀態中譯。S087: status pill 改用 DESIGN.md semantic-soft palette。
 */
const STATUS_LABEL: Record<SkillStatus, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已發佈',
  SUSPENDED: '已停用',
}

const STATUS_PILL_STYLE: Record<SkillStatus, { backgroundColor: string; color: string }> = {
  DRAFT:     { backgroundColor: '#FAEEDA', color: '#633806' },  // warning-soft / warning-deep
  PUBLISHED: { backgroundColor: '#EAF3DE', color: '#085041' },  // success-soft / success-text
  SUSPENDED: { backgroundColor: '#FCEBEB', color: '#791F1F' },  // danger-soft / danger-deep
}

/**
 * S036 — Risk tab 段落說明（mirror STATUS_LABEL pattern；exhaustive Record 防漏）。
 * RiskBadge 是 inline 短訊；本表是 detail page 段落級別的詳述。
 */
const RISK_DESCRIPTION: Record<RiskLevel, string> = {
  LOW: '此技能僅含 SKILL.md，不含可執行腳本，風險等級為低。',
  MEDIUM: '此技能含可執行腳本，但未偵測到高風險模式。建議審視 scripts/ 內容後再使用。',
  HIGH: '此技能的 scripts/ 中偵測到高風險模式，請謹慎使用。',
}

const RISK_TEXT_CLASS: Record<RiskLevel, string> = {
  LOW: 'text-muted-foreground',
  MEDIUM: 'text-amber-700',
  HIGH: 'text-red-600',
}

/**
 * 技能詳情頁：顯示單一技能的完整資訊、版本歷史及風險評估結果。
 *
 * 從 URL 參數 `:id` 取得技能 ID；id 不存在時 hooks 的 `enabled: false` 防止請求發送。
 */
export function SkillDetailPage() {
  // S096c — dual-route support per ADR-003：兩個 React Routes 都 mount 此 component
  // - `/skills/:id` legacy alias → useParams returns { id }
  // - `/skills/:author/:name` canonical → useParams returns { author, name }
  const params = useParams<{ id?: string; author?: string; name?: string }>()
  const skillByIdQuery = useSkill(params.id ?? '')
  const skillByAuthorNameQuery = useSkillByAuthorAndName(params.author, params.name)
  // Pick whichever query has data; both have `enabled` gates so only one fires
  const activeQuery = params.id ? skillByIdQuery : skillByAuthorNameQuery
  const { data: skill, isLoading, error } = activeQuery
  // 後續 hook (useVersions / mutations) 仍需 skill UUID — 從 fetched skill aggregate 取
  const id = skill?.id ?? params.id ?? ''
  const { data: versions } = useVersions(id ?? '')

  if (isLoading) {
    return (
      <AppShell>
        <div className="flex items-center justify-center py-16 text-muted-foreground">
          載入中...
        </div>
      </AppShell>
    )
  }

  if (error || !skill) {
    // S039: 區分 404 not-found 與其他 server / network error；先前所有 error 都顯示「找不到」誤導 user
    // S065: ApiError.is name-based check — HMR 安全；instanceof 在 module 重載後不可靠
    const isNotFound = ApiError.is(error) && error.status === 404
    return (
      <AppShell>
        <div className="flex flex-col items-center justify-center py-16 text-muted-foreground">
          <p className="text-lg font-medium">
            {isNotFound ? '找不到此技能' : '載入技能時發生錯誤'}
          </p>
          {!isNotFound && (
            <p className="mt-1 text-sm">請稍後重試或重新整理頁面</p>
          )}
          <Link to="/" className="mt-2 text-sm text-primary hover:underline">
            返回首頁
          </Link>
        </div>
      </AppShell>
    )
  }

  return (
    <AppShell>
      <Link to="/" className="mb-4 inline-flex items-center gap-1 text-[13px] text-muted-foreground hover:text-foreground">
        <ArrowLeft className="h-4 w-4" />
        返回列表
      </Link>

      {/* S087: hero row — IconTile xl + name 22px + author tertiary + version mono pill + risk pill + status pill + 下載 CTA */}
      <div className="mb-6 flex items-start gap-4">
        <IconTile name={skill.name} category={skill.category} size="xl" />
        <div className="min-w-0 flex-1">
          <h1 className="m-0 truncate text-[22px] font-medium leading-[1.2]">{skill.name}</h1>
          <p className="mt-1 text-[13px] text-muted-foreground">by {skill.author}</p>
          <div className="mt-2 flex flex-wrap items-center gap-2">
            {skill.latestVersion && (
              <span className="rounded font-mono text-[11px] bg-secondary text-foreground/80 px-1.5 py-0.5">
                v{skill.latestVersion}
              </span>
            )}
            <RiskBadge level={skill.riskLevel} />
            <span
              className="rounded-full px-2 py-0.5 text-[11px] font-medium"
              style={STATUS_PILL_STYLE[skill.status]}
            >
              {STATUS_LABEL[skill.status]}
            </span>
          </div>
        </div>
        {skill.latestVersion && skill.status === 'PUBLISHED' && (
          <a
            href={`/api/v1/skills/${skill.id}/download`}
            className="inline-flex shrink-0 items-center gap-1.5 rounded-md bg-primary px-3.5 py-2 text-[13px] font-medium text-primary-foreground hover:bg-foreground"
          >
            <Download className="h-4 w-4" />
            下載
          </a>
        )}
      </div>

      {/* S087: SUSPENDED callout per DESIGN.md card-callout-danger pattern */}
      {skill.status === 'SUSPENDED' && (
        <div
          className="mb-6 flex items-start gap-3 rounded-md p-3 text-[13px]"
          style={{ backgroundColor: '#FCEBEB', color: '#791F1F' }}
        >
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
          <div className="flex-1">
            <p className="m-0 font-medium">此技能已被停用，無法下載</p>
            <p className="m-0 mt-0.5 text-[12px] opacity-90">
              被停用的技能仍會保留紀錄，但下載端點已停用。如需恢復請聯絡管理員。
            </p>
          </div>
        </div>
      )}

      <div className="mb-6 grid grid-cols-2 gap-4 sm:grid-cols-4">
        <MetricCard
          label="下載次數"
          value={`${skill.downloadCount} 次下載`}
        />
        <MetricCard
          label="版本"
          value={skill.latestVersion ?? '—'}
        />
        <MetricCard
          label="分類"
          value={skill.category}
        />
        <MetricCard
          label="建立時間"
          value={new Date(skill.createdAt).toLocaleDateString('zh-TW')}
        />
      </div>

      <Separator className="mb-6" />

      <Tabs defaultValue="overview">
        <TabsList>
          <TabsTrigger value="overview">概要</TabsTrigger>
          <TabsTrigger value="files">檔案</TabsTrigger>
          <TabsTrigger value="versions">版本歷史</TabsTrigger>
          <TabsTrigger value="risk">風險評估</TabsTrigger>
        </TabsList>
        <TabsContent value="overview" className="mt-4">
          <div className="prose max-w-none">
            <h3 className="text-lg font-semibold">描述</h3>
            <p className="text-muted-foreground">{skill.description}</p>
          </div>
          {/* S047: 安裝指引只對 PUBLISHED 顯示 — DRAFT 沒可下載版本 / SUSPENDED 已 block download。
              避免 user 看到「下載 zip」指引但實際找不到下載按鈕的 UX 矛盾。 */}
          {skill.status === 'PUBLISHED' && (
            <div className="mt-6 rounded-md border bg-muted/50 p-4">
              <h4 className="mb-2 text-sm font-semibold">安裝指引</h4>
              <p className="text-sm text-muted-foreground">
                下載 zip 後解壓，將資料夾放到：
              </p>
              <code className="mt-1 block text-sm">~/.claude/skills/（系統級）</code>
              <code className="block text-sm">或 &lt;project&gt;/.claude/skills/（專案級）</code>
            </div>
          )}
        </TabsContent>
        {/* S082: 檔案瀏覽 — 接 S074 backend API (`/skills/{id}/files` + `/files/{*path}`) */}
        <TabsContent value="files" className="mt-4">
          <FilesPanel skillId={id ?? ''} />
        </TabsContent>
        <TabsContent value="versions" className="mt-4">
          <VersionList versions={versions ?? []} />
          {/* S035: SUSPENDED 隱藏新增版本表單 — backend recordVersionPublished 對 SUSPENDED 會拋
              IllegalStateException → 409；預先 hide affordance，admin 必須先 reactivate 才能加版本 */}
          {skill.status !== 'SUSPENDED' && <AddVersionForm skillId={id ?? ''} />}
        </TabsContent>
        <TabsContent value="risk" className="mt-4">
          <div className="space-y-3">
            <div className="flex items-center gap-2">
              <span className="text-sm font-medium">風險等級：</span>
              <RiskBadge level={skill.riskLevel} />
            </div>
            {!skill.riskLevel && (
              <p className="text-sm text-muted-foreground">此技能尚未完成風險評估。</p>
            )}
            {/* S036: 三段風險說明（LOW/MEDIUM/HIGH）統一從 Record map 取；補齊 MEDIUM */}
            {skill.riskLevel && (
              <p className={`text-sm ${RISK_TEXT_CLASS[skill.riskLevel]}`}>
                {RISK_DESCRIPTION[skill.riskLevel]}
              </p>
            )}
          </div>
        </TabsContent>
      </Tabs>
    </AppShell>
  )
}

/**
 * 新增版本表單（內部元件，僅供 SkillDetailPage 使用）。
 *
 * 上傳成功後同時 invalidate 版本列表快取與技能詳情快取，
 * 確保頁面即時反映最新版本號（latestVersion 欄位由後端 projection 更新）。
 */
function AddVersionForm({ skillId }: { skillId: string }) {
  const [file, setFile] = useState<File | null>(null)
  const [version, setVersion] = useState('')
  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: () => {
      if (!file) throw new Error('請選取檔案')
      return addVersion(skillId, file, version)
    },
    onSuccess: () => {
      setFile(null)
      setVersion('')
      // 雙重 invalidate：版本列表 + 技能詳情（latestVersion 欄位需同步更新）
      queryClient.invalidateQueries({ queryKey: ['skills', skillId, 'versions'] })
      queryClient.invalidateQueries({ queryKey: ['skills', skillId] })
    },
    onError: (err) => {
      console.error('[AddVersionForm] 新增版本失敗', err)
    },
  })

  return (
    <div className="mt-6 rounded-md border p-4">
      <h4 className="mb-3 text-sm font-semibold">新增版本</h4>
      <form
        onSubmit={(e) => { e.preventDefault(); mutation.mutate() }}
        className="flex items-end gap-3"
      >
        <div className="flex-1">
          <FileDropZone onFileSelect={setFile} selectedFile={file} />
        </div>
        <div className="w-32">
          <label className="mb-1 block text-xs text-muted-foreground">版本號</label>
          <Input
            value={version}
            onChange={(e) => setVersion(e.target.value)}
            placeholder="2.0.0"
            required
            // S067: HTML5 pattern 預驗 semver — 對齊 backend Skill.VERSION_REGEX (S056)
            // 陷阱：char class 內 `.` 與 `-` 必須 escape (`\.\-`) 否則 Chrome silent 停用整個 pattern
            pattern="\d+\.\d+\.\d+(-[A-Za-z0-9\.\-]+)?"
            title="格式：MAJOR.MINOR.PATCH（如 1.0.0 或 2.0.0-rc.1）"
          />
        </div>
        <button
          type="submit"
          disabled={!file || !version || mutation.isPending}
          className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
        >
          {mutation.isPending ? '上傳中...' : '新增'}
        </button>
      </form>
      {mutation.isError && (
        // S040: 後端 error code 翻譯為繁中；未知 code fallback 至 error.message
        <p className="mt-2 text-sm text-red-600">{localizeApiError(mutation.error)}</p>
      )}
      {mutation.isSuccess && (
        <p className="mt-2 text-sm text-green-600">版本新增成功！</p>
      )}
    </div>
  )
}
