// vitest-specific subpath（jest-dom 6.x；非 deprecated bare path）
// — 為 vitest 的 expect 注入 toBeInTheDocument 等 matcher
import '@testing-library/jest-dom/vitest'

// S097: jsdom 不支援 window.matchMedia（border-beam 用之偵測 prefers-color-scheme
// for theme="auto" detection）。Polyfill stub 給所有 component test renders。
// jsdom-fix-issues 慣例做法：minimum viable MediaQueryList shape.
if (typeof window !== 'undefined' && !window.matchMedia) {
  window.matchMedia = (query: string) =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},      // legacy IE
      removeListener: () => {},   // legacy IE
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }) as unknown as MediaQueryList
}
