import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Download } from 'lucide-react'
import { installCollection } from '@/api/skills'

/**
 * S096f2-T04 — Collection install 按鈕（per spec §1 Approach C）。
 *
 * 點擊 → POST `/collections/{id}/install` → 接收 `{downloadUrls}` → loop trigger
 * N 個 `<a download>` click（50ms 間隔避 browser 對 rapid downloads 的 throttle）。
 * 每個 skill 的 download 走既有 `GET /skills/{id}/download` endpoint，自然累計
 * 個別 skill 的 `download_count`（spec §1 Approach C：reuse 既有路徑）。
 *
 * Mutation onSuccess invalidate `['collections']` — install_count +1 自動觸發 list
 * refetch（樂觀更新走 invalidate 而非 setQueryData，避免估錯 server 真實 count）。
 */
export function InstallButton({
  collectionId,
  skillCount,
}: {
  collectionId: string
  skillCount: number
}) {
  const queryClient = useQueryClient()
  const mutation = useMutation({
    mutationFn: () => installCollection(collectionId),
    onSuccess: (data) => {
      // 50ms 間隔依序觸發 N 個 <a download> click — Spec §2.5 Hypothesis：browser
      // 對 rapid-fire downloads 有 throttle；50ms 是粗估值，可調整為 100ms 或 await queue。
      data.downloadUrls.forEach((url, i) => {
        setTimeout(() => {
          const a = document.createElement('a')
          a.href = url
          a.download = ''
          document.body.appendChild(a)
          a.click()
          a.remove()
        }, i * 50)
      })
      queryClient.invalidateQueries({ queryKey: ['collections'] })
    },
  })

  return (
    <button
      type="button"
      onClick={() => mutation.mutate()}
      disabled={mutation.isPending}
      className="inline-flex items-center gap-1.5 rounded-md bg-primary px-3 py-1.5 text-[12px] font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
    >
      <Download className="h-3 w-3" />
      {mutation.isPending ? '安裝中...' : `安裝 ${skillCount} 個技能`}
    </button>
  )
}
