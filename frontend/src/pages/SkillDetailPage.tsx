import { useState, useEffect } from 'react'
import { useParams, Link } from 'react-router'
import { AlertCircle } from 'lucide-react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AppShell } from '@/components/AppShell'
import { Input } from '@/components/ui/input'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { FileDropZone } from '@/components/FileDropZone'
import { FlagsList } from '@/components/FlagsList'
import { ReviewsPanel } from '@/components/ReviewsPanel'
import { MarkdownActionMenu } from '@/components/MarkdownActionMenu'
import { ShareModal } from '@/components/ShareModal'
import { EditSkillModal } from '@/components/EditSkillModal'
import { useSkill, useSkillByAuthorAndName } from '@/hooks/useSkill'
import { useVersions } from '@/hooks/useVersions'
import { useMe } from '@/hooks/useMe'
import { useSkillScores } from '@/hooks/useSkillScores'
import { useSkillFile } from '@/hooks/useSkillFiles'
import { useSecurityReport } from '@/hooks/useSecurityReport'
import { addVersion, fetchSkillStats } from '@/api/skills'
import { ApiError } from '@/api/client'
import { localizeApiError } from '@/lib/api-error-messages'
import { PageHeader } from '@/components/v2/PageHeader'
import { Sidebar } from '@/components/v2/Sidebar'
import { SkillMdTab } from '@/components/v2/tabs/SkillMdTab'
import { QualityTabV2 } from '@/components/v2/tabs/QualityTabV2'
import { VersionsTabV2 } from '@/components/v2/tabs/VersionsTabV2'
import { SecurityTab } from '@/components/v2/tabs/SecurityTab'
import { FileExplorerPanel } from '@/components/v2/tabs/FileExplorerPanel'

export function SkillDetailPage() {
  // S096c — dual-route support per ADR-003
  const params = useParams<{ id?: string; author?: string; name?: string }>()
  const skillByIdQuery = useSkill(params.id ?? '')
  const skillByAuthorNameQuery = useSkillByAuthorAndName(params.author, params.name)
  const { data: me } = useMe()
  const activeQuery = params.id ? skillByIdQuery : skillByAuthorNameQuery
  const { data: skill, isLoading, error } = activeQuery
  const id = skill?.id ?? params.id ?? ''

  const { data: versions } = useVersions(id)
  const { data: scores } = useSkillScores(id || undefined)
  const { data: report } = useSecurityReport(id)
  const { data: statsData } = useQuery({
    queryKey: ['skill-stats', id, '30d'],
    queryFn: () => fetchSkillStats(id, '30d'),
    staleTime: 5 * 60 * 1000,
    enabled: !!id && skill?.status === 'PUBLISHED',
  })
  const stats = statsData ?? []

  // SKILL.md content for SkillMdTab
  // 純 async decode 進 local state；error 分支由 render-time derive，避免 useEffect 內 sync setState
  // （per React 19 docs「Adjusting some state when a prop changes」— derive-in-render）。
  const skillMdQuery = useSkillFile(id, 'SKILL.md')
  const [decodedSkillMd, setDecodedSkillMd] = useState<string | null>(null)
  useEffect(() => {
    if (!skillMdQuery.data) return
    let cancelled = false
    skillMdQuery.data.blob.text()
      .then(t => { if (!cancelled) setDecodedSkillMd(t) })
      .catch(() => { if (!cancelled) setDecodedSkillMd(null) })
    return () => { cancelled = true }
  }, [skillMdQuery.data])
  // Display value: error → null（顯示「載入失敗」），loading → undefined（顯示 spinner），ready → decoded text
  const skillMdContent: string | null | undefined = skillMdQuery.error
    ? null
    : skillMdQuery.data
      ? decodedSkillMd
      : undefined

  const [activeTab, setActiveTab] = useState('skill-md')
  const [shareOpen, setShareOpen] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const permissions = skill?.viewerPermissions
  const isOwner = permissions?.isOwner ?? (!!skill && !!me?.userId && skill.ownerId === me.userId)
  const canEdit = permissions?.canEdit ?? isOwner

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
    // S153: 400 (格式錯誤 ID) / 403 (ACL 拒讀) / 404 (不存在) 對 user 都是「找不到」
    // 只有真正的 5xx / network error 才提示 retry — 對「永遠不存在的東西」叫使用者
    // 重試是錯訊息，會誘發無效 refresh。
    const isUnviewable =
      ApiError.is(error) && [400, 403, 404].includes(error.status)
    return (
      <AppShell>
        <div className="flex flex-col items-center justify-center py-16 text-muted-foreground">
          <p className="text-lg font-medium">
            {isUnviewable ? '找不到此技能' : '載入技能時發生錯誤'}
          </p>
          {!isUnviewable && (
            <p className="mt-1 text-sm">請稍後重試或重新整理頁面</p>
          )}
          <Link to="/browse" className="mt-2 text-sm text-primary hover:underline">
            返回列表
          </Link>
        </div>
      </AppShell>
    )
  }

  const fileCount = versions?.[0]?.fileCount ?? 0

  return (
    <AppShell>
      <Link
        to="/browse"
        className="mb-4 inline-flex items-center gap-1 text-[13px] text-muted-foreground hover:text-foreground"
        data-testid="back-link"
      >
        返回列表
      </Link>

      <PageHeader
        skill={skill}
        isOwner={isOwner}
        activeTab={activeTab}
        onTabChange={setActiveTab}
        scores={scores}
        stats={stats}
        onDownload={() => {
          // S142a-T06 missing wire-up — `<button data-testid="download-cta">下載技能</button>` 的
          // onClick={onDownload} 在父層沒傳就 noop（prod bug：button 點不動）。
          // 觸發 native browser download：set window.location 走 backend
          // /api/v1/skills/{id}/download endpoint（Content-Disposition: attachment）。
          window.location.href = `/api/v1/skills/${skill.id}/download`
        }}
        onShareClick={() => setShareOpen(true)}
        onEditClick={() => setEditOpen(true)}
      />

      {shareOpen && <ShareModal skillId={skill.id} onClose={() => setShareOpen(false)} />}
      {editOpen && <EditSkillModal skill={skill} onClose={() => setEditOpen(false)} />}

      {/* S087 — SUSPENDED callout */}
      {skill.status === 'SUSPENDED' && (
        <div
          className="mb-6 flex items-start gap-3 rounded-md p-3 text-[13px]"
          style={{ backgroundColor: 'rgba(226,75,74,0.14)', color: '#F2A6A6' }}
          data-testid="suspended-callout"
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

      <div
        data-testid="skill-detail-body"
        className="mt-2 grid grid-cols-1 items-start gap-6 lg:grid-cols-[minmax(0,1fr)_232px]"
      >
        <div data-testid="skill-detail-main" className="min-w-0">
          <Tabs value={activeTab} onValueChange={setActiveTab}>
            <div className="-mx-1 overflow-x-auto px-1 pb-1">
              <TabsList className="min-w-max justify-start">
                <TabsTrigger value="skill-md">SKILL.md</TabsTrigger>
                <TabsTrigger value="quality">品質</TabsTrigger>
                <TabsTrigger value="versions">版本</TabsTrigger>
                <TabsTrigger value="reviews">評論</TabsTrigger>
                <TabsTrigger value="security">安全性</TabsTrigger>
                <TabsTrigger value="flags">旗標</TabsTrigger>
                <TabsTrigger value="files">檔案 {fileCount > 0 ? `(${fileCount})` : ''}</TabsTrigger>
              </TabsList>
            </div>

            <TabsContent value="skill-md" className="mt-4 min-w-0">
              <SkillMdTab content={skillMdContent} />
            </TabsContent>

            <TabsContent value="quality" className="mt-4">
              <QualityTabV2 scores={scores ?? null} />
            </TabsContent>

            <TabsContent value="versions" className="mt-4">
              <VersionsTabV2 versions={versions ?? []} />
              {canEdit && skill.status !== 'SUSPENDED' && <AddVersionForm skillId={id} />}
            </TabsContent>

            <TabsContent value="reviews" className="mt-4">
              <ReviewsPanel skill={skill} currentUserId={me?.sub} />
            </TabsContent>

            <TabsContent value="security" className="mt-4">
              <SecurityTab riskLevel={skill.riskLevel} report={report} />
            </TabsContent>

            <TabsContent value="flags" className="mt-4">
              <FlagsList skillId={skill.id} />
            </TabsContent>

            <TabsContent value="files" className="mt-4">
              <FileExplorerPanel skillId={id} />
            </TabsContent>
          </Tabs>
        </div>

        <Sidebar
          skill={skill}
          version={versions?.[0]}
          stats={stats}
          scores={scores}
          report={report}
          versions={versions ?? []}
          activeTab={activeTab}
          onTabChange={setActiveTab}
        />
      </div>

      {/* Markdown action menu (floating — PUBLISHED skill 所有訪客可見, per S133 AC-5) */}
      {skill.status === 'PUBLISHED' && skill.latestVersion && (
        <MarkdownActionMenu skillId={skill.id} />
      )}
    </AppShell>
  )
}

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
          <label htmlFor="version-upload-version" className="mb-1 block text-xs text-muted-foreground">版本號</label>
          <Input
            id="version-upload-version"
            value={version}
            onChange={(e) => setVersion(e.target.value)}
            placeholder="留白自動產生"
          />
          <p className="mt-1 text-[11px] text-muted-foreground">留白時系統會自動產生下一版號</p>
        </div>
        <button
          type="submit"
          disabled={!file || mutation.isPending}
          className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
        >
          {mutation.isPending ? '上傳中...' : '新增'}
        </button>
      </form>
      {mutation.isError && (
        <p className="mt-2 text-sm text-red-600">{localizeApiError(mutation.error)}</p>
      )}
      {mutation.isSuccess && (
        <p className="mt-2 text-sm text-green-600">版本新增成功！</p>
      )}
    </div>
  )
}
