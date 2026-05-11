import { describe, it, expect } from 'vitest'
import { getDisplayName } from './displayName'

/**
 * S154b T01 — `getDisplayName` 五層 fallback 邏輯驗證（純函式，無 React / DOM）。
 *
 * Priority chain (per spec §2.2)：
 *   1. authorDisplayName  (S154 backend live join 結果)
 *   2. authorEmail        (取 @ 前段)
 *   3. authorHandle       (user-facing slug)
 *   4. author             (platform user_id u_<6hex>，最終 fallback)
 *
 * 對齊 backend `DisplayNameResolver` 五層優先序 — 前後端永不顯 raw OAuth sub。
 *
 * @see ../components/SkillCard.tsx
 * @see https://docs.spring.io/.../S154 backend §2.5
 */
describe('getDisplayName', () => {
  it('returns authorDisplayName when present (priority 1)', () => {
    expect(
      getDisplayName({
        author: 'u_a3f9c1',
        authorDisplayName: 'Alice Chen',
        authorEmail: 'alice@example.com',
        authorHandle: 'alice',
      }),
    ).toBe('Alice Chen')
  })

  it('falls back to email local-part when displayName missing (priority 2)', () => {
    expect(
      getDisplayName({
        author: 'u_a3f9c1',
        authorEmail: 'alice@example.com',
        authorHandle: 'alice',
      }),
    ).toBe('alice')
  })

  it('falls back to handle when displayName + email missing (priority 3)', () => {
    expect(
      getDisplayName({
        author: 'u_a3f9c1',
        authorHandle: 'alice-team',
      }),
    ).toBe('alice-team')
  })

  it('falls back to author user_id when all author fields missing (priority 4)', () => {
    expect(getDisplayName({ author: 'u_a3f9c1' })).toBe('u_a3f9c1')
  })

  it('handles null author fields as missing (not "null" string)', () => {
    expect(
      getDisplayName({
        author: 'u_a3f9c1',
        authorDisplayName: null,
        authorEmail: null,
        authorHandle: null,
      }),
    ).toBe('u_a3f9c1')
  })

  it('handles empty string author fields as missing', () => {
    expect(
      getDisplayName({
        author: 'u_a3f9c1',
        authorDisplayName: '',
        authorEmail: '',
        authorHandle: '',
      }),
    ).toBe('u_a3f9c1')
  })
})
