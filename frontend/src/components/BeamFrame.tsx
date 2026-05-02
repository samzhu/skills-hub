import type { ReactNode } from 'react'

/**
 * S089: Hand-rolled conic-gradient beam frame.
 *
 * 取代 border-beam npm package（light theme 在白背景物理上做不出 glow，per S084 §2.2 研究）。
 * 1:1 對齊 prototype `skills_hub_homepage_mockup.html` `.sh-search-wrap` 結構：
 * - 1px padding wrapper，背景 = `--color-border-tertiary`（#E0DDD3）— 露出 ring
 * - `::before` 偽元素 conic-gradient，從 0° 到 360° 一圈：transparent 0-300° / accent 紫 #7F77DD 330° / info 藍 #378ADD 345° / transparent 360°
 * - inset: -50% 讓 gradient 蓋滿父容器並 spin 出邊外
 * - 4s linear infinite spin，per DESIGN.md §Elevation §3 「4-5s per rotation」
 *
 * Inner content 套 `--color-background-primary`（#FFFFFF），shape 與 wrapper 對齊但 -1px radius。
 *
 * @example
 * ```tsx
 * <BeamFrame>
 *   <div className="search-input-content">...</div>
 * </BeamFrame>
 * ```
 */
export function BeamFrame({ children }: { children: ReactNode }) {
  return (
    <>
      <style>{`
        @keyframes beam-frame-spin { to { transform: rotate(360deg); } }
        .beam-frame {
          position: relative;
          border-radius: var(--border-radius-lg, 12px);
          padding: 1px;
          background: var(--color-border-tertiary, #E0DDD3);
          overflow: hidden;
        }
        .beam-frame::before {
          content: '';
          position: absolute;
          inset: -50%;
          background: conic-gradient(
            from 0deg,
            transparent 0deg,
            transparent 300deg,
            #7F77DD 330deg,
            #378ADD 345deg,
            transparent 360deg
          );
          animation: beam-frame-spin 4s linear infinite;
        }
        .beam-frame > .beam-frame-inner {
          position: relative;
          background: var(--color-background-primary, #FFFFFF);
          border-radius: calc(var(--border-radius-lg, 12px) - 1px);
        }
      `}</style>
      <div className="beam-frame">
        <div className="beam-frame-inner">{children}</div>
      </div>
    </>
  )
}
