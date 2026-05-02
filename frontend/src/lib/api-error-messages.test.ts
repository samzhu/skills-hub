import { describe, it, expect } from 'vitest'
import { localizeApiError } from './api-error-messages'
import { ApiError } from '@/api/client'

describe('localizeApiError — S092 field-level detail concat', () => {
  it('AC-1: VALIDATION_ERROR with backend detail concat 至訊息尾', () => {
    const err = new ApiError(400, "Field 'name' fails regex ^[a-z0-9-]{1,64}$ (got: BAD)", 'VALIDATION_ERROR')
    expect(localizeApiError(err)).toBe(
      "驗證失敗：Field 'name' fails regex ^[a-z0-9-]{1,64}$ (got: BAD)",
    )
  })

  it('AC-1b: VALIDATION_ERROR 無 message 回 generic 繁中', () => {
    const err = new ApiError(400, '', 'VALIDATION_ERROR')
    expect(localizeApiError(err)).toBe('驗證失敗，請確認資料格式正確。')
  })

  it('AC-2: CONSTRAINT_VIOLATION with backend detail concat', () => {
    const err = new ApiError(400, 'value too long for type character varying(50)', 'CONSTRAINT_VIOLATION')
    expect(localizeApiError(err)).toBe(
      '資料驗證失敗：value too long for type character varying(50)',
    )
  })

  it('AC-3: DUPLICATE_RESOURCE 仍用 fixed 模板（不洩漏 SQL detail）', () => {
    const err = new ApiError(409, 'A resource with the same identifier already exists', 'DUPLICATE_RESOURCE')
    expect(localizeApiError(err)).toBe('此名稱已被使用，請換一個名稱。')
  })

  it('AC-4: 未知 code fallback 至 err.message', () => {
    const err = new ApiError(500, 'Internal explosion', 'UNKNOWN_CODE')
    expect(localizeApiError(err)).toBe('Internal explosion')
  })

  it('AC-5: 非 ApiError 但 Error → err.message（網路錯誤類）', () => {
    expect(localizeApiError(new Error('Network unreachable'))).toBe('Network unreachable')
  })

  it('AC-6: 非 Error → 「未知錯誤」', () => {
    expect(localizeApiError('plain string')).toBe('未知錯誤')
    expect(localizeApiError(null)).toBe('未知錯誤')
  })
})
