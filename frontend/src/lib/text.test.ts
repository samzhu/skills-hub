import { describe, it, expect } from 'vitest'
import { capitalize, categoryLabel } from './text'

/**
 * S159b T02 — `capitalize` 純函式：首字母大寫，其餘不動。
 *
 * 對應 spec §2.4：DB V20 後 `skills.category` 一律 lowercase（"testing"），
 * UI display 層用此 helper 還原首字母大寫（"Testing"）。
 *
 * @see ./text
 * @see ../components/SkillCard.tsx
 */
describe('capitalize', () => {
  it('lowercase string → first letter uppercased', () => {
    expect(capitalize('testing')).toBe('Testing')
    expect(capitalize('devops')).toBe('Devops')
  })

  it('mixed case → only first letter touched，後段保留原樣', () => {
    expect(capitalize('aWS')).toBe('AWS')
    expect(capitalize('cI/CD')).toBe('CI/CD')
  })

  it('empty / null / undefined → 空字串（safe fallback）', () => {
    expect(capitalize('')).toBe('')
    expect(capitalize(null)).toBe('')
    expect(capitalize(undefined)).toBe('')
  })

  it('single character → uppercase', () => {
    expect(capitalize('a')).toBe('A')
  })
})

/**
 * S159b Round 2 — `categoryLabel` 雙欄位 display fallback。
 *
 * 對應 spec §7.5c：V21 後新資料 backend dual-write `categoryDisplay`（原 CamelCase
 * "DevOps"）；舊資料 `categoryDisplay = null` → fallback `capitalize(category)`。
 */
describe('categoryLabel', () => {
  it('優先用 categoryDisplay（保留 CamelCase）', () => {
    expect(categoryLabel({ category: 'devops', categoryDisplay: 'DevOps' })).toBe('DevOps')
    expect(categoryLabel({ category: 'dataops', categoryDisplay: 'DataOps' })).toBe('DataOps')
  })

  it('categoryDisplay null → fallback capitalize(category)', () => {
    expect(categoryLabel({ category: 'devops', categoryDisplay: null })).toBe('Devops')
    expect(categoryLabel({ category: 'testing' })).toBe('Testing')
  })
})
