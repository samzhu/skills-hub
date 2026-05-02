import type { ReactNode } from 'react'
import { BorderBeam } from 'border-beam'

/**
 * S089 → S096b → S097: BeamFrame thin wrapper around official `border-beam` package.
 *
 * Hand-rolled implementation (S089 / S096b 5-color rewrite) 視覺效果不對 — official
 * `border-beam@1.0.1` 的 `colorful` size=md preset 才是 user 期望的「rainbow glow」.
 *
 * User-locked defaults per Engineering Handoff §8 + manual UX comparison:
 * - size="md"          — full border glow (vs `sm` button-sized / `line` bottom-only)
 * - colorVariant="colorful" — full rainbow spectrum
 * - duration={1.96}    — package default; matches Handoff §8 spec
 * - strength={0.7}     — beam intensity 70% (avoid over-saturation on dark bg)
 * - theme="dark" (default) — works with v2 dark theme `#08080A` page bg
 *
 * **Usage rules** (per Handoff §8): scarce motion primitive — **ONE per page**.
 * 限用於：hero search bar / primary CTA / FileDropZone / featured top-match card.
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
    <BorderBeam size="md" colorVariant="colorful" duration={1.96} strength={0.7}>
      {children}
    </BorderBeam>
  )
}
