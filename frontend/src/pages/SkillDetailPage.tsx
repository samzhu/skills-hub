import { useState } from 'react'
import { useParams, Link } from 'react-router'
import { ArrowLeft, Download } from 'lucide-react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { AppShell } from '@/components/AppShell'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Separator } from '@/components/ui/separator'
import { RiskBadge } from '@/components/RiskBadge'
import { MetricCard } from '@/components/MetricCard'
import { VersionList } from '@/components/VersionList'
import { FileDropZone } from '@/components/FileDropZone'
import { useSkill } from '@/hooks/useSkill'
import { useVersions } from '@/hooks/useVersions'
import { addVersion } from '@/api/skills'
import type { SkillStatus } from '@/types/skill'

/**
 * S028 — 技能狀態中譯 + Badge variant 對應。
 * 對齊 backend SkillStatus enum 三狀態（DRAFT / PUBLISHED / SUSPENDED）。
 */
const STATUS_LABEL: Record<SkillStatus, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已發佈',
  SUSPENDED: '已停用',
}

function statusBadgeVariant(s: SkillStatus): 'default' | 'secondary' | 'destructive' {
  switch (s) {
    case 'DRAFT': return 'secondary'
    case 'PUBLISHED': return 'default'
    case 'SUSPENDED': return 'destructive'
  }
}

/**
 * 技能詳情頁：顯示單一技能的完整資訊、版本歷史及風險評估結果。
 *
 * 從 URL 參數 `:id` 取得技能 ID；id 不存在時 hooks 的 `enabled: false` 防止請求發送。
 */
export function SkillDetailPage() {
  const { id } = useParams<{ id: string }>()
  // id 可能為 undefined（型別安全），fallback 空字串讓 useSkill/useVersions 的 enabled 守衛生效
  const { data: skill, isLoading, error } = useSkill(id ?? '')
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
    return (
      <AppShell>
        <div className="flex flex-col items-center justify-center py-16 text-muted-foreground">
          <p className="text-lg font-medium">找不到此技能</p>
          <Link to="/" className="mt-2 text-sm text-primary hover:underline">
            返回首頁
          </Link>
        </div>
      </AppShell>
    )
  }

  return (
    <AppShell>
      <Link to="/" className="mb-4 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="h-4 w-4" />
        返回列表
      </Link>

      <div className="mb-6">
        <div className="flex items-start gap-3">
          <div className="min-w-0 flex-1">
            <h1 className="text-2xl font-bold">{skill.name}</h1>
            <p className="mt-1 text-muted-foreground">
              by {skill.author}
            </p>
          </div>
          <div className="flex items-center gap-2">
            {skill.latestVersion && (
              <Badge variant="secondary">v{skill.latestVersion}</Badge>
            )}
            <RiskBadge level={skill.riskLevel} />
            {/* S028: DRAFT secondary / PUBLISHED default / SUSPENDED destructive；中譯 via STATUS_LABEL */}
            <Badge variant={statusBadgeVariant(skill.status)}>
              {STATUS_LABEL[skill.status]}
            </Badge>
            {skill.latestVersion && (
              // 使用原生 <a> 而非 React Router <Link>，觸發瀏覽器直接下載行為，
              // 不走 SPA 路由（SPA 路由無法觸發 Content-Disposition: attachment）
              <a
                href={`/api/v1/skills/${skill.id}/download`}
                className="ml-2 inline-flex items-center gap-1 rounded-md bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/90"
              >
                <Download className="h-4 w-4" />
                下載
              </a>
            )}
          </div>
        </div>
      </div>

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
          <TabsTrigger value="versions">版本歷史</TabsTrigger>
          <TabsTrigger value="risk">風險評估</TabsTrigger>
        </TabsList>
        <TabsContent value="overview" className="mt-4">
          <div className="prose max-w-none">
            <h3 className="text-lg font-semibold">描述</h3>
            <p className="text-muted-foreground">{skill.description}</p>
          </div>
          <div className="mt-6 rounded-md border bg-muted/50 p-4">
            <h4 className="mb-2 text-sm font-semibold">安裝指引</h4>
            <p className="text-sm text-muted-foreground">
              下載 zip 後解壓，將資料夾放到：
            </p>
            <code className="mt-1 block text-sm">~/.claude/skills/（系統級）</code>
            <code className="block text-sm">或 &lt;project&gt;/.claude/skills/（專案級）</code>
          </div>
        </TabsContent>
        <TabsContent value="versions" className="mt-4">
          <VersionList versions={versions ?? []} />
          <AddVersionForm skillId={id ?? ''} />
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
            {skill.riskLevel === 'LOW' && (
              <p className="text-sm text-muted-foreground">此技能僅含 SKILL.md，不含可執行腳本，風險等級為低。</p>
            )}
            {skill.riskLevel === 'HIGH' && (
              <p className="text-sm text-red-600">此技能的 scripts/ 中偵測到高風險模式，請謹慎使用。</p>
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
        <p className="mt-2 text-sm text-red-600">{mutation.error.message}</p>
      )}
      {mutation.isSuccess && (
        <p className="mt-2 text-sm text-green-600">版本新增成功！</p>
      )}
    </div>
  )
}
