import { describe, it, expect, beforeEach } from 'vitest'
import { __test } from './client'

const { withCsrfHeader, readCookie, isMutation } = __test

function setCookie(value: string) {
  document.cookie = value
}

function clearAllCookies() {
  for (const c of document.cookie.split(';')) {
    const eq = c.indexOf('=')
    const name = eq > -1 ? c.substring(0, eq).trim() : c.trim()
    if (name) document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`
  }
}

describe('client.ts CSRF helpers (S160b\')', () => {
  beforeEach(() => clearAllCookies())

  describe('readCookie', () => {
    it('cookie 存在 → 回 value', () => {
      setCookie('XSRF-TOKEN=abc123')
      expect(readCookie('XSRF-TOKEN')).toBe('abc123')
    })

    it('cookie 不存在 → 回 null', () => {
      expect(readCookie('XSRF-TOKEN')).toBeNull()
    })

    it('multiple cookies → 抓對 name', () => {
      setCookie('foo=bar')
      setCookie('XSRF-TOKEN=tok42')
      setCookie('baz=qux')
      expect(readCookie('XSRF-TOKEN')).toBe('tok42')
    })

    it('URL-encoded value 自動 decode', () => {
      setCookie('XSRF-TOKEN=' + encodeURIComponent('a/b+c='))
      expect(readCookie('XSRF-TOKEN')).toBe('a/b+c=')
    })
  })

  describe('isMutation', () => {
    it('POST / PUT / DELETE / PATCH → true（含大小寫）', () => {
      expect(isMutation('POST')).toBe(true)
      expect(isMutation('PUT')).toBe(true)
      expect(isMutation('DELETE')).toBe(true)
      expect(isMutation('PATCH')).toBe(true)
      expect(isMutation('post')).toBe(true)
      expect(isMutation('Patch')).toBe(true)
    })

    it('GET / HEAD / OPTIONS / undefined → false（safe methods）', () => {
      expect(isMutation('GET')).toBe(false)
      expect(isMutation('HEAD')).toBe(false)
      expect(isMutation('OPTIONS')).toBe(false)
      expect(isMutation(undefined)).toBe(false)
      expect(isMutation('')).toBe(false)
    })
  })

  describe('withCsrfHeader', () => {
    it('S160 AC-3: POST + XSRF-TOKEN cookie → X-XSRF-TOKEN header 注入', () => {
      setCookie('XSRF-TOKEN=mytoken')
      const out = withCsrfHeader({ method: 'POST', body: 'x' })
      const headers = new Headers(out!.headers)
      expect(headers.get('X-XSRF-TOKEN')).toBe('mytoken')
    })

    it('GET 不加 header（safe method）', () => {
      setCookie('XSRF-TOKEN=mytoken')
      const out = withCsrfHeader({ method: 'GET' })
      // GET 路徑回原 init 物件未動
      expect(out).toEqual({ method: 'GET' })
    })

    it('POST 但無 cookie → 不加 header（idempotent，背景 CSRF off 路徑）', () => {
      const out = withCsrfHeader({ method: 'POST', body: 'x' })
      expect(out).toEqual({ method: 'POST', body: 'x' })
    })

    it('caller 已自訂 X-XSRF-TOKEN → 不覆蓋', () => {
      setCookie('XSRF-TOKEN=cookie-token')
      const out = withCsrfHeader({
        method: 'PUT',
        headers: { 'X-XSRF-TOKEN': 'caller-supplied' },
      })
      const headers = new Headers(out!.headers)
      expect(headers.get('X-XSRF-TOKEN')).toBe('caller-supplied')
    })

    it('保留既有 Content-Type 等 headers + 加上 X-XSRF-TOKEN', () => {
      setCookie('XSRF-TOKEN=tok')
      const out = withCsrfHeader({
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
      })
      const headers = new Headers(out!.headers)
      expect(headers.get('Content-Type')).toBe('application/json')
      expect(headers.get('X-XSRF-TOKEN')).toBe('tok')
    })

    it('DELETE + cookie → 加 header', () => {
      setCookie('XSRF-TOKEN=del-tok')
      const out = withCsrfHeader({ method: 'DELETE' })
      const headers = new Headers(out!.headers)
      expect(headers.get('X-XSRF-TOKEN')).toBe('del-tok')
    })

    it('init = undefined → 原樣回 undefined（不加 header）', () => {
      setCookie('XSRF-TOKEN=tok')
      expect(withCsrfHeader(undefined)).toBeUndefined()
    })
  })
})

describe('client.ts empty response contract (S184)', () => {
  it('AC-S184-2: production code 不使用 apiFetch<void>(...)', () => {
    const sourceModules = import.meta.glob('../**/*.{ts,tsx}', {
      eager: true,
      import: 'default',
      query: '?raw',
    }) as Record<string, string>
    const offenders = Object.entries(sourceModules)
      .filter(([file]) => !/\.test\.(ts|tsx)$/.test(file))
      .filter(([, source]) => source.includes('apiFetch<void>('))
      .map(([file]) => file)

    expect(offenders).toEqual([])
  })
})
