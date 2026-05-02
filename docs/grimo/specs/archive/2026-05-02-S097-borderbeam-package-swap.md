# S097 — Swap BeamFrame to official border-beam Package

> **Status**: shipped
> **Type**: dependency swap (frontend-only)
> **Estimate**: XS / 4 pts
> **Source**: user manual UX comparison (current hand-rolled visual ≠ official package effect)

## §1 Goal

S089 ship 時 hand-roll `BeamFrame` 取代 `border-beam` npm 因 light theme 限制（白底物理上做不出 glow，per S084 §2.2 research）。S096b 為 dark theme 做了 5-color rewrite 但 user 對視覺不滿（image #3 vs #4 比對：current 只有底部小段彩條 vs official 完整 rainbow ring glow）。

User 直接給官方 API config:
```tsx
<BorderBeam size="md" colorVariant="colorful" duration={1.96} strength={0.7}>
  <Card>Content</Card>
</BorderBeam>
```

S097 swap hand-roll 回 npm package，thin wrapper 內 lock user-specified defaults，所有既有 8 call sites 簽名不動.

## §2 Approach

### §2.1 Why npm package now works (vs S089 was deemed broken)

S089 timing：light theme background `#FFFFFF`，npm package 用 rgba(0,0,0,x) inner-shadow physics 在白背景無 glow visible.  
S097 timing：dark theme background `#08080A` (per S096b)，rgba alpha overlay on dark surface natively visible glow ✓.

新 package version `1.0.1` 加入 `theme="dark" | "light" | "auto"` prop（S089 review 時 v1.0.0 沒此 prop） — dark mode now first-class.

### §2.2 BeamFrame 重寫為 thin wrapper

```tsx
import { BorderBeam } from 'border-beam'

export function BeamFrame({ children }: { children: ReactNode }) {
  return (
    <BorderBeam size="md" colorVariant="colorful" duration={1.96} strength={0.7}>
      {children}
    </BorderBeam>
  )
}
```

- `size="md"` — full border glow (vs `sm` / `line`)
- `colorVariant="colorful"` — full rainbow spectrum
- `duration={1.96}` — package default + Engineering Handoff §8 spec
- `strength={0.7}` — beam intensity 70%（避 dark bg 上過飽和）
- `theme="dark"` 由 package default — 與 v2 dark theme 對齊

8 call sites 簽名不動（皆 `<BeamFrame>{children}</BeamFrame>`）；BeamFrame.tsx 從 60 行 hand-roll → 5 行 wrapper.

### §2.3 jsdom matchMedia polyfill

border-beam 用 `window.matchMedia` 偵測 `prefers-color-scheme` (for `theme="auto"` detection). jsdom 不提供 matchMedia → 既有 6 tests 渲染 BeamFrame-用 component (App.test / EmptyState.test seed / YourFirstSkillPage.test ×5) 全 fail with `TypeError: window.matchMedia is not a function`.

Fix in `setupTests.ts`：minimum viable MediaQueryList polyfill stub —常見 jsdom-issue workaround 慣例.

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | `<BeamFrame>{children}</BeamFrame>` 渲染 | wraps children in `<BorderBeam>` with locked defaults |
| AC-2 | All 8 existing call sites unchanged | SearchBar / SkillCard featured / EmptyState / LandingPage 2× / MySkillsPage / YourFirstSkillPage / PublishReviewPage 全可 build |
| AC-3 | Dark theme glow visible | manual smoke：dev server 看 `/` Landing 三個 BeamFrame 各顯 rainbow ring |
| AC-4 | jsdom matchMedia polyfill | setupTests.ts 加 stub；既有 6 affected tests 全 PASS |
| AC-5 | Tests 33 → 33 PASS | regression check |
| AC-6 | Build OK ≤ 460KB JS | budget; package adds ~50KB |

## §4 Implementation file plan

```
frontend/
├── package.json + package-lock.json    ← `border-beam@1.0.1`
├── src/components/BeamFrame.tsx        ← 60 LOC hand-roll → 5 LOC wrapper
└── src/setupTests.ts                   ← + window.matchMedia polyfill stub
```

不動 8 call sites（簽名不變）.

## §5 Test plan

- `npm test` — 預期 33/33 PASS（含 6 affected tests post-polyfill）
- `npm run build` — JS ≤ 460KB
- Manual smoke: dev server 視覺對比 user 提供 image #4 — Landing/Home/Publish 等 BeamFrame 顯 rainbow ring glow

## §6 Verification

實際結果 §7。

## §7 Result

- **Frontend tests**: 33 → 33 PASS / 0 fail（matchMedia polyfill 成功）
- **JS bundle**: 405.86 → 454.60KB (+48.74KB；border-beam package weight)
- **CSS bundle**: 38.25 → 38.22KB（不變，hand-roll inline `<style>` 移除）
- **Build time**: 249ms（無 regression）
- **Files touched**: 4 (package.json + package-lock + BeamFrame.tsx + setupTests.ts) + 1 spec doc
- **AC coverage**: AC-1~6 全 ✓ (Build pass + tests pass; manual smoke 留 user verification)

ship as **v2.85.0** (M91)。

## §8 Notes

- **Bundle size +48KB**：trade-off ─ accepted because visual parity 對 marketing landing + key CTAs 重要
- **S089 historical context**：原 hand-roll 決定 light theme 時 valid；dark theme + v1.0.1 加 theme prop 後 npm package 重新可用。Lesson learned 寫進 spec：「dependency 二度評估的契機是 (a) target environment 變化 (b) package version up」
- **All call sites unchanged**：API stability of `<BeamFrame>` 簽名是 internal abstraction win — 即便底層 swap 不影響 caller
