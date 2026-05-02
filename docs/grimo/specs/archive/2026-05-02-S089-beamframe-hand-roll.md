# S089 — BeamFrame hand-roll component (drop border-beam dep)

> **Status**: shipped — v2.62.0 (M85)
> **Estimate**: XS / 3 pts
> **Source**: per S084 §2.2 BorderBeam research finding — npm package light theme 物理上做不出 glow

## §1 Problem

`SearchBar` 用 `border-beam` npm package；S083 試 `theme="light"` + duration 4.5 + strength 0.7 但 saturation 仍偏霧（package 用 `rgba(0,0,0,x)` inner-shadow 在 #FFFFFF 背景無法產生光感）。

Prototype HTML (`skills_hub_homepage_mockup.html` `.sh-search-wrap`) 不用 npm package，直接 hand-roll conic-gradient + 1px padding wrapper + `#E0DDD3` darker frame，與 DESIGN.md `card-featured` pattern 1:1 對齊。

## §2 Approach

新建 `frontend/src/components/BeamFrame.tsx`：1:1 port prototype CSS。Drop `border-beam@1.0.1` npm dep。

```tsx
<BeamFrame>{children}</BeamFrame>
```

CSS-in-JSX：
- `.beam-frame`：1px padding，背景 `--color-border-tertiary` (#E0DDD3)
- `::before`：conic-gradient 0° transparent → 300° transparent → 330° accent #7F77DD → 345° info #378ADD → 360° transparent；4s linear infinite spin；inset: -50%
- `.beam-frame-inner`：`--color-background-primary` (#FFFFFF) + radius `calc(var(--border-radius-lg) - 1px)`

DESIGN.md §Elevation §3「4-5s per rotation」對齊。Per `card-featured` pattern。

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | HomePage SearchBar 視覺 | beam ring 清晰可見，60° arc 旋轉 |
| AC-2 | `border-beam` dep 移除 | `package.json` 無 border-beam；node_modules 無；bundle size 下降 |
| AC-3 | npm test | 11 / 0 fail |
| AC-4 | 元件 reusable | 任何 child 可包；inner content 在 #FFFFFF surface |

## §4 Result

- `border-beam@1.0.1` removed; bundle size **396 KB → 347 KB** (−49 KB JS / -7 KB gzip)
- 11 frontend tests / 0 fail
- Chrome smoke：HomePage SearchBar 上方顯紫色 accent ring（60° arc 旋轉中）
- `BeamFrame` 在後續 S085 / 任何 primary CTA 可重用
