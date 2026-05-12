/**
 * S159b — Text display helpers for normalized storage.
 *
 * 與 backend V20 migration 對齊：`skills.category` 一律以 lowercase 存（"testing" / "devops"），
 * UI 顯示時透過此 helper 還原首字母大寫（"Testing" / "Devops"）— UX 不變但 storage 收斂。
 *
 * @see ../../docs/grimo/specs/2026-05-09-S159b-category-normalize.md
 */

/**
 * 將字串首字母大寫，其餘字元保留原樣（不破壞中段大寫如 "CI/CD" → "CI/CD"）。
 *
 * null / undefined / empty 一律回空字串（給 JSX 用 — `{capitalize(skill.category)}`
 * 在 category 缺值時不渲染 "undefined" 文字）。
 *
 * @param s 任意字串或 null/undefined
 * @returns 首字母大寫的字串，或空字串
 */
export function capitalize(s: string | null | undefined): string {
  if (!s) return ''
  return s.charAt(0).toUpperCase() + s.slice(1)
}

/**
 * S159b Round 2 — Skill 分類 display label：優先用 backend dual-write 的 categoryDisplay（保
 * 原 CamelCase 如 "DevOps"），舊資料 categoryDisplay 為 null 時 fallback `capitalize(category)`
 * （lossy first-letter capitalize）。
 *
 * 對齊 V21 migration：dual-column 設計避免 Round 1 lossy `"DevOps"→"devops"→"Devops"` UX
 * regression（V07 抓到 bug）。
 *
 * @param skill 含 `category` (canonical lowercase) 與 optional `categoryDisplay`
 * @returns UI 顯示用 label
 */
export function categoryLabel(skill: { category: string; categoryDisplay?: string | null }): string {
  return skill.categoryDisplay ?? capitalize(skill.category)
}
