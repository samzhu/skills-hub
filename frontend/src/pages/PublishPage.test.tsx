import { describe, it, expect } from 'vitest'
import { validateFrontmatter } from './PublishPage'

/**
 * S099b2 — validateFrontmatter pure-function tests。
 * Cover positive / negative (3+ types) / edge cases per S099 testing methodology
 * (3-5 反例 / round)。
 */

describe('validateFrontmatter — S099b2', () => {
  // Positive
  it('AC-1: valid frontmatter with name + description passes', () => {
    const r = validateFrontmatter('---\nname: my-skill\ndescription: A useful skill\n---\n# body')
    expect(r.hasFrontmatter).toBe(true)
    expect(r.hasName).toBe(true)
    expect(r.hasDescription).toBe(true)
    expect(r.errors).toEqual([])
  })

  // Negative — empty
  it('AC-2 (negative empty): empty content yields all-false + no errors', () => {
    const r = validateFrontmatter('')
    expect(r.hasFrontmatter).toBe(false)
    expect(r.hasName).toBe(false)
    expect(r.hasDescription).toBe(false)
    expect(r.errors).toEqual([])
  })

  // Negative — no frontmatter delimiter
  it('AC-3 (negative format): content without leading --- shows frontmatter error', () => {
    const r = validateFrontmatter('# Just markdown\nno frontmatter')
    expect(r.hasFrontmatter).toBe(false)
    expect(r.errors).toContain('SKILL.md 必須以 YAML frontmatter 開頭（首行 ---）')
  })

  // Negative — missing closing delimiter
  it('AC-4 (negative format): missing closing --- shows error', () => {
    const r = validateFrontmatter('---\nname: x\ndescription: y\n# body without closing')
    expect(r.hasFrontmatter).toBe(false)
    expect(r.errors).toContain('Frontmatter 缺少結束 ---（需在第 N 行單獨一個 ---）')
  })

  // Negative — missing required field name
  it('AC-5 (negative missing field): missing name yields field error', () => {
    const r = validateFrontmatter('---\ndescription: only desc\n---\n')
    expect(r.hasFrontmatter).toBe(true)
    expect(r.hasName).toBe(false)
    expect(r.hasDescription).toBe(true)
    expect(r.errors).toContain('缺必填欄位：name')
  })

  // Negative — missing required field description
  it('AC-6 (negative missing field): missing description yields field error', () => {
    const r = validateFrontmatter('---\nname: only-name\n---\n')
    expect(r.hasFrontmatter).toBe(true)
    expect(r.hasName).toBe(true)
    expect(r.hasDescription).toBe(false)
    expect(r.errors).toContain('缺必填欄位：description')
  })

  // Edge — name with empty value
  it('AC-7 (edge): name with empty value treated as missing', () => {
    const r = validateFrontmatter('---\nname:\ndescription: x\n---\n')
    expect(r.hasName).toBe(false)
    expect(r.errors).toContain('缺必填欄位：name')
  })

  // Edge — extra whitespace before delimiter
  it('AC-8 (edge): leading whitespace before --- accepted (after trim)', () => {
    const r = validateFrontmatter('   \n---\nname: x\ndescription: y\n---\n')
    expect(r.hasFrontmatter).toBe(true)
    expect(r.hasName).toBe(true)
  })
})
