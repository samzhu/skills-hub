import { AppShell } from '@/components/AppShell'
import { MetricCard } from '@/components/MetricCard'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { useOverview } from '@/hooks/useAnalytics'

/**
 * 平台數據分析頁：顯示技能總數、下載總數、本週新增及熱門技能排行榜。
 *
 * 排行榜以相對長度的進度條呈現，最高下載數的技能為 100% 寬度，
 * 其餘依比例縮放（相對分佈圖，非絕對值比較）。
 */
export function AnalyticsPage() {
  const { data: stats, isLoading, error } = useOverview()

  return (
    <AppShell>
      <h1 className="mb-6 text-2xl font-bold">平台數據分析</h1>

      {isLoading ? (
        <div className="flex items-center justify-center py-16 text-muted-foreground">
          載入中...
        </div>
      ) : error ? (
        // 查詢失敗時顯示錯誤訊息；錯誤已由 main.tsx QueryCache 訂閱記錄至 console
        <div className="flex items-center justify-center py-16 text-red-500">
          載入數據失敗，請重新整理頁面
        </div>
      ) : stats ? (
        <>
          <div className="mb-8 grid grid-cols-2 gap-4 sm:grid-cols-4">
            <MetricCard label="總技能數" value={stats.totalSkills} />
            <MetricCard label="總下載次數" value={stats.totalDownloads} />
            <MetricCard label="本週新增" value={stats.newSkillsThisWeek} />
            <MetricCard label="熱門排行" value={`Top ${stats.topSkills.length}`} />
          </div>

          <Card>
            <CardHeader>
              <CardTitle>熱門技能 Top 10</CardTitle>
            </CardHeader>
            <CardContent>
              {stats.topSkills.length === 0 ? (
                <p className="text-muted-foreground">尚無下載記錄</p>
              ) : (
                <div className="space-y-3">
                  {stats.topSkills.map((skill, i) => (
                    // key 使用 skill.name：後端以 skillId group by，同名技能不會重複出現
                    <div key={skill.name} className="flex items-center gap-3">
                      <span className="w-6 text-right text-sm font-bold text-muted-foreground">
                        {i + 1}
                      </span>
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center justify-between">
                          <span className="truncate font-medium">{skill.name}</span>
                          <span className="text-sm text-muted-foreground">
                            {skill.downloads} 次下載
                          </span>
                        </div>
                        <div className="mt-1 h-2 overflow-hidden rounded-full bg-muted">
                          {/*
                            相對分佈圖：以排名第一的技能下載數為基準（100%），
                            其餘依比例計算寬度。guard `> 0` 防止除以零。
                          */}
                          <div
                            className="h-full rounded-full bg-primary"
                            style={{
                              width: `${stats.topSkills[0].downloads > 0 ? (skill.downloads / stats.topSkills[0].downloads) * 100 : 0}%`
                            }}
                          />
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
        </>
      ) : null}
    </AppShell>
  )
}
