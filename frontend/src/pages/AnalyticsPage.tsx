import { Link } from 'react-router'
import { AppShell } from '@/components/AppShell'
import { MetricCard } from '@/components/MetricCard'
import { ErrorState } from '@/components/ErrorState'
import { useOverview } from '@/hooks/useAnalytics'

/**
 * S088: 重寫對齊 prototype `platform_analytics_dashboard_admin_view.html`：
 * - hero row (H1 + sub-text)
 * - metric strip 4-up + label-caps 統一風格
 * - top-skills 排行卡 hairline border + accent-bar progress（per DESIGN.md
 *   accent purple #7F77DD）取代 generic primary
 * - rank 數字 mono 大字
 *
 * 排行榜以相對長度的進度條呈現，最高下載數的技能為 100% 寬度，
 * 其餘依比例縮放（相對分佈圖，非絕對值比較）。
 */
export function AnalyticsPage() {
  const { data: stats, isLoading, error } = useOverview()

  return (
    <AppShell>
      <div className="mb-[18px]">
        <h1 className="m-0 text-[22px] font-medium leading-[1.2]">平台數據分析</h1>
        <p className="mt-1 text-[13px] text-muted-foreground">
          技能總覽、下載趨勢與熱門排行
        </p>
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-16 text-[13px] text-muted-foreground">
          載入中...
        </div>
      ) : error ? (
        <ErrorState variant="centered" title="載入數據失敗" message="請重新整理頁面" />
      ) : stats ? (
        <>
          {/* metric strip 4-up */}
          <div className="mb-[18px] grid grid-cols-2 gap-3 sm:grid-cols-4">
            <MetricCard label="總技能數" value={stats.totalSkills} />
            <MetricCard label="總下載次數" value={stats.totalDownloads} />
            <MetricCard label="本週新增" value={stats.newSkillsThisWeek} subtitle="rolling 7-day" />
            <MetricCard label="熱門排行" value={`Top ${stats.topSkills.length}`} />
          </div>

          {/* top skills card */}
          <div className="rounded-lg border border-border bg-card p-5">
            <div className="mb-4 flex items-center justify-between">
              <h2 className="m-0 text-[15px] font-medium">熱門技能 Top 10</h2>
              <span className="text-[11px] font-medium uppercase tracking-[0.05em] text-muted-foreground">
                依下載次數
              </span>
            </div>
            {stats.topSkills.length === 0 ? (
              <p className="text-[13px] text-muted-foreground">尚無下載記錄</p>
            ) : (
              <div className="space-y-3">
                {stats.topSkills.map((skill, i) => {
                  const top = stats.topSkills[0].downloads
                  const pct = top > 0 ? (skill.downloads / top) * 100 : 0
                  // S100a: wrap Link 至 canonical /skills/:author/:name route
                  return (
                    <Link
                      key={`${skill.author}/${skill.name}`}
                      to={`/skills/${skill.author}/${skill.name}`}
                      className="flex items-center gap-3 rounded-md transition-colors hover:bg-[rgba(255,255,255,0.04)]"
                    >
                      <span className="w-6 text-right font-mono text-[13px] font-medium text-muted-foreground">
                        {i + 1}
                      </span>
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center justify-between gap-2">
                          <span className="truncate text-[13px] font-medium">{skill.name}</span>
                          <span className="shrink-0 font-mono text-[12px] text-muted-foreground tabular-nums">
                            {skill.downloads}
                          </span>
                        </div>
                        <div className="mt-1.5 h-1.5 overflow-hidden rounded-full" style={{ backgroundColor: '#171719' }}>
                          {/* DESIGN.md accent #7F77DD purple progress bar (vs generic primary) */}
                          <div
                            className="h-full rounded-full transition-all"
                            style={{ width: `${pct}%`, backgroundColor: '#7F77DD' }}
                          />
                        </div>
                      </div>
                    </Link>
                  )
                })}
              </div>
            )}
          </div>
        </>
      ) : null}
    </AppShell>
  )
}
