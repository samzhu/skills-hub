import { describe, it, expect } from 'vitest'
import { getAuthorRouteSegment, getDisplayName } from './displayName'

/**
 * S192 T04 — `getDisplayName` 只產人類可讀 label；technical segment 走 `getAuthorRouteSegment`。
 *
 * Priority chain (per S192 §2.5)：
 *   1. authorDisplayName  (S154 backend live join 結果)
 *   2. authorEmail        (取 @ 前段)
 *   3. authorHandle       (user-facing slug)
 *   4. missing label      (回空字串，避免一般 UI 顯示 platform user_id)
 *
 * 對齊 S192 display contract — platform user_id 只能出現在 route / install command segment。
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

  it('AC-S192-11: getDisplayName never returns raw platform user id', () => {
    expect(getDisplayName({ author: 'u_a3f9c1' })).toBe('')
  })

  it('handles null author fields as missing (not "null" string)', () => {
    expect(
      getDisplayName({
        author: 'u_a3f9c1',
        authorDisplayName: null,
        authorEmail: null,
        authorHandle: null,
      }),
    ).toBe('')
  })

  it('handles empty string author fields as missing', () => {
    expect(
      getDisplayName({
        author: 'u_a3f9c1',
        authorDisplayName: '',
        authorEmail: '',
        authorHandle: '',
      }),
    ).toBe('')
  })
})

describe('getAuthorRouteSegment', () => {
  it('AC-S192-12: prefers authorHandle for commands and routes', () => {
    expect(getAuthorRouteSegment({ author: 'u_a3f9c1', authorHandle: 'alice' })).toBe('alice')
  })

  it('AC-S192-12: may fall back to platform user id for commands and routes', () => {
    expect(getAuthorRouteSegment({ author: 'u_a3f9c1', authorHandle: null })).toBe('u_a3f9c1')
  })
})
