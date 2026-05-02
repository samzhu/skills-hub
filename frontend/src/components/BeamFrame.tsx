import type { ReactNode } from 'react'

/**
 * S089 → S096b — 5-color conic-gradient beam frame for dark theme.
 *
 * 改寫對齊 Engineering Handoff §8 BorderBeam Usage Rules：
 * - padding 1.5px（vs S089 1px）— 在 dark bg `#08080A` 上 ring 更可見
 * - background `#1A1A1E`（per Handoff §8）取代 light theme 的 hairline border token
 * - 5-color stops 對齊 v2 prototype HTML：purple → magenta → amber → green → blue
 * - animation 1.96s（vs S089 4s）— Handoff §8 specifies 此 timing 為「scarce motion primitive」
 * - blur(10px) opacity 0.5 ::after layer — glow halo effect on dark bg
 *
 * Inner content 用 `--color-background`（dark `#08080A`）對齊頁面，shape 與 wrapper 對齊。
 *
 * **Usage rules** (per Handoff §8): beam 屬稀有 motion primitive，**ONE per page**。
 * 限用於：
 * - Hero search bar (HomePage)
 * - Primary CTA button (one per page)
 * - FileDropZone (PublishPage Step 1)
 * - Featured / top-match skill card
 *
 * **Never use on**: metric cards, navigation, sidebar items, secondary buttons, list rows.
 *
 * @example
 * ```tsx
 * <BeamFrame>
 *   <button className="primary-cta">...</button>
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
          padding: 1.5px;
          border-radius: 8px;
          background: #1A1A1E;
          overflow: hidden;
          isolation: isolate;
        }
        .beam-frame::before,
        .beam-frame::after {
          content: '';
          position: absolute;
          inset: -120%;
          background: conic-gradient(from 0deg,
            transparent 0deg, transparent 197deg,
            rgba(127,119,221,.95) 230deg,
            rgba(217,56,138,.95) 268deg,
            rgba(239,159,39,.95) 300deg,
            rgba(29,158,117,.95) 332deg,
            rgba(55,138,221,.95) 360deg,
            transparent 360deg);
          animation: beam-frame-spin 1.96s linear infinite;
          pointer-events: none;
          z-index: 0;
        }
        .beam-frame::after {
          filter: blur(10px);
          opacity: 0.5;
        }
        .beam-frame > .beam-frame-inner {
          position: relative;
          z-index: 1;
          background: var(--color-background, #08080A);
          border-radius: 6.5px;
        }
      `}</style>
      <div className="beam-frame">
        <div className="beam-frame-inner">{children}</div>
      </div>
    </>
  )
}
