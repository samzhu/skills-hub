# S135b — Frontend Quality Display

> Spec: S135b | Size: S(9-11) | Status: 📋 planned
> Date: 2026-05-06
> Parent META: S135 | Depends: S135a ✅ (shipped v3.14.0)

---

## §1 Goal

把 S135a 已 ship 的 `GET /api/v1/skills/{id}/scores` endpoint 連到前端：

1. **SkillDetailPage hero 區塊**：MetricCard grid 下方加「品質分數」橫條（Quality progress bar + Security indicator），讓用戶在瀏覽第一眼就看到信任信號
2. **SkillDetailPage 「品質」tab**：3-axis dimension 明細表（Validation / Implementation / Activation）+ 每 dimension reasoning，hover/expand 可查看 LLM 解說
3. **SkillCard foot row**：Quality 分數 pill（已評分才顯示），類似既有「相符度」badge 的呈現方式

**非目標（defer）**：
- Security 獨立 tab（既有 Risk 評估 tab 已涵蓋）
- Impact 軸（S136 Backlog）
- 管理員手動 re-evaluate UI（後續 spec）
- SKILL.md 渲染樣式 / sidebar 重整（S137 UI Polish Pass）

---

## §2 Approach

### §2.1 API 消費

直接消費 S135a `GET /api/v1/skills/{id}/scores`：
- 200 → `{ skillId, skillVersionId, skillVersion, evaluatedAt, evaluatorVersion, validation, implementation, activation, total }`
- 404 QUALITY_NOT_EVALUATED → 顯示 fallback（"評分計算中"）
- 每 axis：`{ totalScore: 0-100, dimensions: { dimName: { score: 0-3, reasoning } } }`
- total = round(0.2 × V + 0.4 × I + 0.4 × A)

### §2.2 三層展示結構

```
SkillDetailPage
  MetricCard grid（既有）
  ↓ 新增
  QualitySection（§2.3）
    ├─ QualityBar（Quality %）
    └─ SecurityIndicator（既有 risk_level → 文字描述）
  ↓ 既有
  Tabs
    + 新增 "品質" tab → QualityTab（§2.4）

SkillCard
  foot row 右側 → QualityScorePill（§2.5）
```

### §2.3 QualitySection（hero 區）

放在 `<Separator>` 之前（MetricCard grid 之後）：

```
品質分數  92%
┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫ 92%
安全等級  低風險
```

- progress bar：`<progress>` 或 `<div>` 寬度 = total %；顏色依 score tier：
  - ≥ 80 → `text-[#9FE1CB]`（綠）
  - 60-79 → `text-[#FAC775]`（黃）
  - < 60 → `text-[#F2A6A6]`（紅）
- Security indicator：從既有 `skill.riskLevel`（不重複 API call）

### §2.4 QualityTab — dimension 明細

| 維度 | 軸 | Score | 說明 |
|------|----|-------|------|
| Line Count (Validation) | VALIDATION | 100 | "412 lines / 500 max" |
| ... | ... | ... | ... |

- 每 axis 一個 section（Validation / 實作品質 / 觸發能力）
- 中文 axis label 對照：
  - VALIDATION → 規格驗證
  - IMPLEMENTATION → 實作品質（對應 Tessl "Implementation"）
  - ACTIVATION → 觸發能力（對應 Tessl "Discovery"，後端命名 Activation）
- dimension score 0-3 顯示成 circle dot 或 fraction（1/3, 2/3, 3/3）
- reasoning 預設顯示（不 collapse，字數短）

### §2.5 QualityScorePill（SkillCard foot row）

- 只在 `total !== undefined && total > 0` 才顯示
- 顯示：`{total}分` 加顏色 dot
- 顏色規則同 QualitySection
- 與「相符度」badge 同 style（rounded-full px-2 py-0.5 text-[11px]）

### §2.6 Loading 與 Fallback

| 狀態 | QualitySection | QualityTab |
|------|----------------|------------|
| loading | skeleton div（w-full h-4 bg-muted animate-pulse） | skeleton rows |
| 404 not evaluated | "評分計算中，請稍後重新整理" + 灰色 bar 0% | "此版本尚未評分" |
| error | silent（不顯示 QualitySection）| "無法載入品質評分" |

### §2.7 Trim Path

Size = S；若 wall hit：
1. Defer：QualityScorePill on SkillCard（AC-4）→ 後續 polish
2. Core ship：QualitySection + QualityTab（AC-1、AC-2、AC-3）

---

## §3 SBE Acceptance Criteria

### AC-1: SkillDetailPage hero 顯示品質進度條
```
Given 已評分的 skill（scores API 回 200 total=92）
When  訪問 /skills/{id}
Then  MetricCard grid 下方顯示「品質分數 92%」progress bar（滿版寬）
And   bar 顏色為綠色（≥80 tier）
And   安全等級行顯示 risk_level 對應文字（如「低風險」）
```

### AC-2: 品質 tab 顯示 3-axis 明細與 reasoning
```
Given 已評分的 skill
When  點 SkillDetailPage「品質」tab
Then  顯示 3 個 axis section（規格驗證 / 實作品質 / 觸發能力）
And   每 section 列出各 dimension 名稱 + score (0-3) + reasoning 文字
And   axis totalScore 顯示在 section header（如「規格驗證 100%」）
```

### AC-3: 未評分 skill 顯示 fallback
```
Given 剛 publish 尚未評分的 skill（scores API 回 404 QUALITY_NOT_EVALUATED）
When  訪問 /skills/{id}
Then  QualitySection 顯示灰色 0% bar + "評分計算中，請稍後重新整理"
And   品質 tab 顯示 "此版本尚未評分"
And   不顯示任何 undefined / NaN
```

### AC-4: SkillCard 顯示品質分數 pill（已評分）
```
Given 已評分的 skill（total = 92）出現在 HomePage / SearchResultsPage
When  渲染 SkillCard
Then  foot row 右側顯示「92分」pill（綠色，≥80 tier）
And   未評分的 SkillCard 不顯示 pill（不閃爍）
```

### AC-5: loading 期間不閃爍 / Layout shift
```
Given useSkillScores 正在 fetch
When  SkillDetailPage 渲染
Then  QualitySection 顯示 skeleton（h-4 bg-muted animate-pulse）
And   Tabs 結構不變（tab list 不因 loading 而移位）
```

---

## §4 Interface / API Design

### §4.1 Types

```typescript
// frontend/src/api/scores.ts
export interface DimensionScore {
  score: number;       // 0-3
  reasoning: string;
}

export interface AxisScore {
  totalScore: number;  // 0-100
  dimensions: Record<string, DimensionScore>;
}

export interface SkillScores {
  skillId: string;
  skillVersionId: string;
  skillVersion: string;
  evaluatedAt: string;
  evaluatorVersion: string;
  validation: AxisScore;
  implementation: AxisScore;
  activation: AxisScore;
  total: number;       // 0-100 weighted average
}

/** null = 404 not evaluated yet */
export async function fetchSkillScores(skillId: string): Promise<SkillScores | null>;
```

### §4.2 Hook

```typescript
// frontend/src/hooks/useSkillScores.ts
export function useSkillScores(skillId: string): {
  scores: SkillScores | null | undefined;  // undefined = loading, null = not evaluated
  isLoading: boolean;
  error: Error | null;
};
// staleTime: 60_000 (1 min) — score 不常變；不需 30s 頻繁 refetch
```

### §4.3 Components（public surface）

```typescript
// frontend/src/components/QualitySection.tsx
interface QualitySectionProps {
  scores: SkillScores | null | undefined;
  riskLevel: string;  // from skill — 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH'
}
export function QualitySection(props: QualitySectionProps): JSX.Element;

// frontend/src/components/QualityTab.tsx
interface QualityTabProps {
  scores: SkillScores | null | undefined;
}
export function QualityTab(props: QualityTabProps): JSX.Element;

// frontend/src/components/QualityScorePill.tsx
interface QualityScorePillProps {
  skillId: string;
}
/** 自行 fetch；null → 不渲染 */
export function QualityScorePill(props: QualityScorePillProps): JSX.Element | null;
```

### §4.4 Axis label 中文對照（不可更動）

```typescript
const AXIS_LABELS: Record<string, string> = {
  validation:     '規格驗證',
  implementation: '實作品質',
  activation:     '觸發能力',
};
```

### §4.5 Score tier → CSS color

```typescript
function scoreTierColor(total: number): string {
  if (total >= 80) return '#9FE1CB';   // 綠
  if (total >= 60) return '#FAC775';   // 黃
  return '#F2A6A6';                     // 紅
}
```

---

## §5 File Plan

### New files

| File | Description |
|------|-------------|
| `frontend/src/api/scores.ts` | `fetchSkillScores()` — GET /api/v1/skills/{id}/scores；404 → null |
| `frontend/src/hooks/useSkillScores.ts` | React Query hook；staleTime 60s |
| `frontend/src/components/QualitySection.tsx` | Hero area：quality bar + security indicator |
| `frontend/src/components/QualityTab.tsx` | 3-axis breakdown table with reasoning |
| `frontend/src/components/QualityScorePill.tsx` | Foot-row pill for SkillCard（self-fetching）|

### Modified files

| File | Change |
|------|--------|
| `frontend/src/pages/SkillDetailPage.tsx` | 加 `useSkillScores` + `<QualitySection>` before `<Separator>` + 加「品質」tab entry |
| `frontend/src/components/SkillCard.tsx` | foot row 右側加 `<QualityScorePill skillId={skill.id}>` |

### Test files

| File | Description |
|------|-------------|
| `frontend/src/components/QualitySection.test.tsx` | AC-1 / AC-3 / AC-5：3 狀態（loaded/null/loading）snapshot |
| `frontend/src/components/QualityTab.test.tsx` | AC-2 / AC-3：軸標題 + dimension row + fallback |
| `frontend/src/components/QualityScorePill.test.tsx` | AC-4：evaluated → pill 顯示；null → 不渲染 |

### Vite proxy（如需新 endpoint）

`GET /api/v1/skills/{id}/scores` 走既有 vite proxy `^/api → http://localhost:8080`，不需額外配置。

---

<!-- §6-§7 由 /planning-tasks 實作後填入 -->
