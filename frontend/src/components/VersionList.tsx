import { Download, GitCompare } from 'lucide-react'
import { Link } from 'react-router'
import { Badge } from '@/components/ui/badge'
import type { SkillVersion } from '@/types/skill'

/**
 * 技能版本列表：依發佈時間降冪排列各版本資訊，並標示最新版。
 *
 * 此元件假設 API 回傳的版本陣列已按 publishedAt 降冪排序（後端保證），
 * 因此以 `index === 0` 作為「最新版」的判斷依據。
 *
 * 下載連結使用版本號字串（`v.version`）而非版本記錄 ID（`v.id`），
 * 符合後端路由設計 `/api/v1/skills/:skillId/versions/:version/download`。
 *
 * @param versions 版本清單（由 useVersions hook 取得，已降冪排序）
 */
export function VersionList({ versions }: { versions: SkillVersion[] }) {
  if (versions.length === 0) {
    return <p className="text-muted-foreground">尚無版本記錄</p>
  }

  // S098c: 多版本時顯「比較版本」捷徑（連結到 VersionDiffPage 預設 from=次新 / to=最新）
  const hasMultiple = versions.length >= 2
  const skillId = versions[0]?.skillId

  return (
    <div className="space-y-2">
      {hasMultiple && skillId && (
        <Link
          to={`/skills/${skillId}/diff`}
          className="mb-2 inline-flex items-center gap-1.5 text-[12px] text-muted-foreground hover:text-foreground"
        >
          <GitCompare className="h-3 w-3" />
          比較版本變化
        </Link>
      )}
      {versions.map((v, i) => (
        <div
          key={v.id}
          className="flex items-center justify-between rounded-md border px-4 py-3"
        >
          <div className="flex items-center gap-3">
            <span className="font-mono font-medium">v{v.version}</span>
            {/* i === 0 為最新版（API 已按 publishedAt DESC 排序，第一筆即為最新） */}
            {i === 0 && <Badge variant="secondary">最新</Badge>}
          </div>
          <div className="flex items-center gap-4 text-sm text-muted-foreground">
            {/* fileSize 單位為 bytes，轉換為 KB（1 KB = 1024 bytes）顯示 */}
            <span>{v.fileSize >= 0 ? `${(v.fileSize / 1024).toFixed(1)} KB` : '—'}</span>
            {/*
              S117 — 顯示套件檔案數；fileCount=0 為 pre-S098a3-2 歷史 row fallback signal，
              此情境下隱藏該欄避免「0 個檔案」誤導（per spec §2 graceful policy）。
            */}
            {v.fileCount > 0 && <span>{v.fileCount} 個檔案</span>}
            <span>{new Date(v.publishedAt).toLocaleDateString('zh-TW')}</span>
            {/*
              使用原生 <a> 而非 React Router <Link>：
              下載端點回傳 Content-Disposition: attachment，
              需要由瀏覽器直接處理，SPA 路由無法攔截此類回應。
            */}
            <a
              href={`/api/v1/skills/${v.skillId}/versions/${v.version}/download`}
              className="inline-flex items-center gap-1 text-primary hover:underline"
            >
              <Download className="h-3 w-3" />
              下載
            </a>
          </div>
        </div>
      ))}
    </div>
  )
}
