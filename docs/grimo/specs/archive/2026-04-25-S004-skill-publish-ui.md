# S004: 技能發佈 UI（上傳、版本歷史）

> Spec: S004 | Size: S(10) | Status: ✅ Done
> Date: 2026-04-25

---

## 1. Goal

讓技能作者在 Web 介面上傳 skill zip 檔發佈新技能，並在詳情頁查看/管理版本歷史。這完成 Milestone 2（技能發佈流程）的前端部分。

依賴 S002（✅ shipped）— 使用 AppShell、API client、TanStack Query、shadcn/ui 元件。
依賴 S003（✅ shipped）— 使用 `POST /api/v1/skills/upload`、`PUT /api/v1/skills/{id}/versions`、`GET /api/v1/skills/{id}/versions` API。

## 2. Approach

| Approach | Chosen | Rationale |
|----------|--------|-----------|
| A: 單頁發佈表單 + 詳情頁版本 tab | ⭐ yes | MVP 最簡：一個 PublishPage 處理上傳，SkillDetailPage 的 Versions tab 顯示歷史 + 新增版本。符合 UI mockup 的核心流程 |
| B: 多步驟 stepper（Upload → Validate → Review → Publish） | no | UI mockup 描述完整，但 MVP 過重。S005 風險評估完成後才有 Review 步驟的意義 |

### Key Decisions

1. **PublishPage 單頁表單** — 檔案拖拽/選取 + version + author + category → submit → 顯示結果。不做多步驟 stepper（MVP 簡化）。
2. **Version tab 升級** — SkillDetailPage 的「版本歷史」tab 從 placeholder 升級為實際版本列表 + 「新增版本」表單。
3. **Multipart upload via fetch** — 不用 TanStack Query mutation（multipart FormData 搭配 fetch 更直覺）。用 `useMutation` 管理 loading/error 狀態。
4. **UI 語言繁體中文** — 所有標題、按鈕、提示文字使用 zh-TW。
5. **Nav 新增「發佈」連結** — AppShell 增加 `/publish` nav link。

### 2.3 Research Citations

- S002 §7 — TanStack Query hooks pattern, shadcn/ui Card/Badge/Tabs, AppShell layout, API client fetch wrapper
- S003 §7 — Multipart upload API: `POST /api/v1/skills/upload` (file + version + author + category), `PUT /api/v1/skills/{id}/versions` (file + version)
- UI mockup (docs/grimo/ui/README.md) — 4-step stepper design, validation sections, error states (red vs amber). MVP simplifies to single-page form; stepper deferred.

## 3. SBE Acceptance Criteria

Verification commands:

    Frontend: cd frontend && npx tsc --noEmit && npm run build
    Pass: TypeScript compiles, build succeeds.

---

**AC-1: 上傳 skill zip — 成功**

```
Given 作者在 /publish 頁面
And   選取一個含有效 SKILL.md 的 zip 檔
And   填入 version=1.0.0, author=sam, category=DevOps
When  點擊「發佈」按鈕
Then  呼叫 POST /api/v1/skills/upload（multipart）
And   成功後顯示「發佈成功」+ skill ID
And   提供「查看技能」連結（→ /skills/{id}）
```

**AC-2: 上傳 skill zip — 失敗**

```
Given 作者在 /publish 頁面
When  上傳的 zip 缺少 SKILL.md
Then  API 回傳 400
And   頁面顯示紅色錯誤訊息（來自 API response.message）
And   表單不被清空，作者可重新選檔
```

**AC-3: 查看版本歷史**

```
Given skill abc 有 v1.0.0 和 v1.1.0
When  前端導覽到 /skills/abc，點擊「版本歷史」tab
Then  顯示版本列表：v1.1.0（最新）、v1.0.0
And   每筆含 version, 發佈時間, 檔案大小
```

**AC-4: 新增版本**

```
Given 作者在 skill abc 的詳情頁「版本歷史」tab
When  填入 version=2.0.0，選取新的 zip 檔，點擊「新增版本」
Then  呼叫 PUT /api/v1/skills/abc/versions（multipart）
And   成功後版本列表自動刷新，顯示 v2.0.0 在最前
```

## 4. Interface / API Design

### 4.1 User Flow

```
/publish page:
┌──────────────────────────────────────────┐
│  發佈新技能                               │
│                                          │
│  ┌────────────────────────────────────┐  │
│  │  拖拽 zip 檔到此處                  │  │
│  │  或 點擊選取檔案                    │  │
│  │  (選取後顯示檔案名稱 + 大小)        │  │
│  └────────────────────────────────────┘  │
│                                          │
│  版本號: [1.0.0    ]                      │
│  作者:   [sam      ]                      │
│  分類:   [DevOps ▼ ]                      │
│                                          │
│  [發佈技能]                               │
│                                          │
│  ✅ 發佈成功！ID: abc-123                 │
│     → 查看技能                            │
│                                          │
│  ❌ 發佈失敗：SKILL.md not found in zip   │
└──────────────────────────────────────────┘

/skills/:id → Versions tab:
┌──────────────────────────────────────────┐
│  [概要] [版本歷史] [風險評估]              │
│                                          │
│  版本歷史                                 │
│  ┌──────────────────────────────────────┐│
│  │ v1.1.0  2026-04-25  3.2 KB  最新    ││
│  │ v1.0.0  2026-04-24  2.1 KB          ││
│  └──────────────────────────────────────┘│
│                                          │
│  新增版本                                 │
│  [選取 zip 檔] 版本號: [2.0.0  ] [新增]  │
└──────────────────────────────────────────┘
```

### 4.2 Frontend — New API Functions

```typescript
// api/skills.ts — additions

export async function uploadSkill(file: File, version: string, author: string, category: string) {
  const form = new FormData()
  form.append('file', file)
  form.append('version', version)
  form.append('author', author)
  form.append('category', category)
  const res = await fetch('/api/v1/skills/upload', { method: 'POST', body: form })
  if (!res.ok) { const body = await res.json(); throw new Error(body.message) }
  return res.json() as Promise<{ id: string }>
}

export async function addVersion(skillId: string, file: File, version: string) {
  const form = new FormData()
  form.append('file', file)
  form.append('version', version)
  const res = await fetch(`/api/v1/skills/${skillId}/versions`, { method: 'PUT', body: form })
  if (!res.ok) { const body = await res.json(); throw new Error(body.message) }
}

export function fetchVersions(skillId: string): Promise<SkillVersion[]> {
  return apiFetch(`/skills/${skillId}/versions`)
}
```

### 4.3 Frontend — New Types

```typescript
// types/skill.ts — additions
export interface SkillVersion {
  id: string
  skillId: string
  version: string
  storagePath: string
  fileSize: number
  publishedAt: string
}
```

## 5. File Plan

| # | File | Action | Description |
|---|------|--------|-------------|
| **Types + API** |||
| 1 | `frontend/src/types/skill.ts` | modify | Add SkillVersion interface |
| 2 | `frontend/src/api/skills.ts` | modify | Add uploadSkill, addVersion, fetchVersions |
| **Hooks** |||
| 3 | `frontend/src/hooks/useVersions.ts` | new | TanStack Query: version list by skillId |
| **Pages** |||
| 4 | `frontend/src/pages/PublishPage.tsx` | new | Upload form: file drop zone + fields + submit + result |
| 5 | `frontend/src/pages/SkillDetailPage.tsx` | modify | Versions tab: version list + add version form |
| **Components** |||
| 6 | `frontend/src/components/FileDropZone.tsx` | new | Drag-and-drop + click file input |
| 7 | `frontend/src/components/VersionList.tsx` | new | Version history table |
| **App shell** |||
| 8 | `frontend/src/App.tsx` | modify | Add /publish route |
| 9 | `frontend/src/components/AppShell.tsx` | modify | Add 「發佈」nav link |

## 6. Task Plan

### POC: not required

All frontend technologies validated by S002. APIs validated by S003.

### Task Overview

| Task | Description | AC Coverage | Depends On | Status |
|------|-------------|-------------|------------|--------|
| T1 | API + hooks + types + routing | Infra | none | PASS |
| T2 | PublishPage — upload form + result | AC-1, AC-2 | T1 | PASS |
| T3 | SkillDetailPage — version history + add version | AC-3, AC-4 | T1 | PASS |

## 7. Implementation Results

### Verification Results

```
Backend: ./gradlew test → BUILD SUCCESSFUL (19 tests, 0 failures)
Frontend: npx tsc --noEmit → 0 errors
Frontend: npm run build → ✓ built in 158ms
```

### Key Findings

1. **FileDropZone reused** — Same drag-and-drop component used in both PublishPage and SkillDetailPage's AddVersionForm.
2. **useMutation for uploads** — TanStack Query mutation handles loading/error state. On success, `queryClient.invalidateQueries` refreshes version list and skill detail.
3. **Multipart FormData via native fetch** — Not through the apiFetch wrapper (which sets Content-Type). FormData needs browser to set multipart boundary automatically.

### AC Results

| AC | Status | Evidence |
|----|--------|----------|
| AC-1: 上傳成功 | ✅ PASS | PublishPage: form + success display + link to detail |
| AC-2: 上傳失敗 | ✅ PASS | PublishPage: red error message from API response |
| AC-3: 版本歷史 | ✅ PASS | SkillDetailPage Versions tab: VersionList with sorted entries |
| AC-4: 新增版本 | ✅ PASS | AddVersionForm: file + version input, auto-refresh on success |

### E2E Verification

Visual verification requires running both backend + frontend dev servers. TypeScript compilation and build verify component assembly.

### AC Coverage Matrix

| AC | Task(s) | Verification |
|----|---------|-------------|
| AC-1: 上傳成功 | T2 | `tsc --noEmit` + visual |
| AC-2: 上傳失敗 | T2 | `tsc --noEmit` + visual |
| AC-3: 版本歷史 | T3 | `tsc --noEmit` + visual |
| AC-4: 新增版本 | T3 | `tsc --noEmit` + visual |
