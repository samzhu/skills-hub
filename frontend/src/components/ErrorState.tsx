import type { ReactNode } from 'react'
import { AlertCircle } from 'lucide-react'

/**
 * S100d — 統一 error state UI primitive。
 *
 * 取代各 page 重複的「rgba(226,75,74,0.14) + #F2A6A6 + AlertCircle」inline
 * 紅色 callout pattern (5+ 重複 callsites observed during S100 audit)。
 *
 * 兩個 variant：
 * - **inline** (default)：水平 small callout 用於 mutation error / form-level error
 * - **centered**：full-width centered box 用於 page-level data-load failure
 *
 * Style locked to danger-soft palette (rgba(226,75,74,0.14) bg + #F2A6A6 fg)
 * per DESIGN.md card-callout-danger token。Caller 提供 title + message；
 * icon 預設 AlertCircle，可 override。
 *
 * @example
 * ```tsx
 * // mutation onError inline
 * {mutation.isError && <ErrorState title="發佈失敗" message={localizeApiError(mutation.error)} />}
 *
 * // page-level data load failure
 * <ErrorState variant="centered" title="載入數據失敗" message="請重新整理頁面" />
 * ```
 */
export interface ErrorStateProps {
  /** 主訊息 / 標題（粗體第一行） */
  title: string
  /** 補充說明（小字第二行）；optional — 短訊息時可省略 */
  message?: ReactNode
  /** layout variant — 預設 inline */
  variant?: 'inline' | 'centered'
  /** 自訂 icon，預設 AlertCircle */
  icon?: ReactNode
  /** 額外 className 套在最外層（如調 mt 距離） */
  className?: string
}

const PALETTE = {
  bg: 'rgba(226,75,74,0.14)',
  fg: '#F2A6A6',
}

export function ErrorState({
  title,
  message,
  variant = 'inline',
  icon,
  className = '',
}: ErrorStateProps) {
  const iconNode = icon ?? <AlertCircle className="h-4 w-4 shrink-0" />

  if (variant === 'centered') {
    return (
      <div
        className={`flex items-center justify-center rounded-md p-4 text-[13px] ${className}`}
        style={{ backgroundColor: PALETTE.bg, color: PALETTE.fg }}
      >
        <span className="mr-2 inline-flex">{iconNode}</span>
        <div>
          <span className="font-medium">{title}</span>
          {message && <span className="ml-2 opacity-90">{message}</span>}
        </div>
      </div>
    )
  }

  // inline (default)
  return (
    <div
      className={`flex items-start gap-3 rounded-md p-3 text-[13px] ${className}`}
      style={{ backgroundColor: PALETTE.bg, color: PALETTE.fg }}
    >
      <span className="mt-0.5 shrink-0">{iconNode}</span>
      <div className="flex-1">
        <p className="m-0 font-medium">{title}</p>
        {message && <p className="m-0 mt-0.5 text-[12px] opacity-90">{message}</p>}
      </div>
    </div>
  )
}
