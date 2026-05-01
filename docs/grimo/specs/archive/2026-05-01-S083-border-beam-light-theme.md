# S083 — `BorderBeam` light theme tuning

> **Status**: in-flight
> **Type**: polish (visual quality)
> **Estimate**: XS / 1 pt
> **User-driven**: 「原生效果不錯, 你用就沒那麼好看, 研究一下」+ jakubantalik/border-beam playground 截圖

## §1 Problem

`SearchBar` 用 `<BorderBeam>` **沒傳任何 prop**，所以全套 defaults：
- `theme="dark"` ← **mismatch！我們背景是 #FFFFFF（DESIGN.md surface = warm off-white）**
- `duration=1.96` ← 略快（DESIGN.md §Elevation: 「4–5s per rotation」）
- `strength=1` ← 太強（user playground 偏好 0.7）
- `colorVariant="colorful"` ← OK 預設

`theme="dark"` 內部 tune 的 strokeOpacity / innerOpacity / bloomOpacity / saturation 為深色 canvas 設計；落在淺色背景時 saturation 偏高、glow 與背景對比不協調 → 看起來「霧、暗、偏粉」（user 形容「沒那麼好看」）。

## §2 Fix

`SearchBar.tsx` `<BorderBeam>` 傳 3 props：
- `theme="light"` — 切到 light-tuned ThemeColors（per package internal `sizeThemePresets`）
- `duration={4.5}` — 對齊 DESIGN.md §Elevation §3 「4-5s per rotation」
- `strength={0.7}` — 對齊 jakubantalik playground user 截圖偏好

`colorVariant` / `size` / `borderRadius` 維持 default（`'colorful'` / `'md'` / 自動偵測 child border-radius）。

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | HomePage SearchBar 視覺 | beam 在 light 背景下乾淨、不再霧；rotation 較慢、subtle |
| AC-2 | 既有 frontend test | 11 / 0 fail（無 prop interface 或行為 break） |
| AC-3 | DESIGN.md §Elevation 對齊 | 4.5s rotation duration |

## §4 Verification

- `npm test` PASS
- Chrome 視覺：HomePage 載入後 SearchBar 周圍 beam 清晰、慢轉、淺光暈

## §5 Result

待 ship 後填。

## §6 Follow-up

DESIGN.md §Components 規定 beam 「Every primary action wears the beam … 只有 page 的 main CTA 戴」。HomePage prototype 把 beam 放 search bar；其他 page 的「Publish 按鈕」/「Download 按鈕」是否也要戴 beam，留 S084+ per-page rework 評估。本 spec 只處理 search bar 既有 beam 的視覺品質。

**Result（填於 ship 後）**：
- 11 frontend tests / 0 fail；npm run build 成功
- Chrome smoke：HomePage 載入後 SearchBar beam 視覺淡且 subtle，rotation 變慢；不再霧
- 同步驗到 S081 tokens 在 list 頁全到位：「全部」active 用 accent-soft #EEEDFE / 「高風險」用 danger-soft #FCEBEB / warm off-white 背景 / Inter 字體
- ship v2.59.1 (M79)
