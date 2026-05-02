# S100e — AnalyticsPage Top 10 link defensive guard

> Phase 5 | XS(2) | Spawned from production observation 2026-05-02
>
> 後續 S100a 上線運作的真實問題：當 backend runtime 落後 frontend deploy（stale runtime 沒帶 `author` 欄位），AnalyticsPage Top 10 會生出 `/skills/undefined/<name>` URL，user 點過去 404。本 spec 補 frontend defensive 不依賴 backend restart。

---

## 1. Goal

讓 AnalyticsPage 熱門技能 Top 10 的 link 不論 backend payload 有無 `author` 欄位都絕不送 user 至無效路徑。

當 backend 因部署落後或欄位 schema drift 漏掉 `author`：
- ❌ 現況：`<Link to="/skills/undefined/r19-lifecycle">` → 404 「找不到此技能」（user 看到「假資料」感受）
- ✅ 修後：rank 行 fallback 為非 link 的純 row（rank + name + downloads 完整保留），不產生壞 URL

User flow：
```
/analytics → 後端回 Top 10
  ├─ 有 author → 渲染 <Link>（既有 S100a 行為）
  └─ 無 author / author === "undefined" → 渲染 <div>（不可點，但顯示完整資訊）
```

## 2. Approach

### Chosen: Frontend type-narrow guard

```tsx
{stats.topSkills.map((skill, i) => {
  const hasValidAuthor =
    typeof skill.author === 'string' && skill.author.length > 0 && skill.author !== 'undefined'
  const RankRow = hasValidAuthor ? Link : 'div'
  const linkProps = hasValidAuthor ? { to: `/skills/${skill.author}/${skill.name}` } : {}
  return (
    <RankRow key={`${skill.author ?? '_'}/${skill.name}`} {...linkProps} className="...">
      ...
    </RankRow>
  )
})}
```

Rationale：
- 不需 backend 改動；S100a 已加 author，但 stale runtime 是 cross-cutting 風險
- 「`undefined` 字串」guard 防 React Router 把 JS undefined 拼進 URL 成字面 string `"undefined"`
- Polymorphic `as` pattern 不引入新 component；避免 shipping 1 component 只用 1 處的 over-engineering
- key 不依賴 author（fallback `_` 防 collision）

### Defer (polish backlog)
- Sentry / log warning 記 author 缺失事件（觀察 stale runtime 發生頻率）
- AnalyticsPage E2E test integrating Playwright（目前用 vitest unit）

### Rejected approaches
| 方案 | Pros | Cons |
|------|------|------|
| Frontend skill.id fallback `/skills/:id` | route 已存在 | TopSkill payload 也沒帶 id；要再改 backend |
| Backend force-fail 缺 author 的 row | early-fail | 與 graceful degradation 原則相反；Top 10 反而空洞 |
| **Frontend defensive guard（chosen）** | 0 backend 改動 / 0 deploy 依賴 / type-narrow 清楚 | 多 4 行 conditional |

## 3. Acceptance Criteria（SBE Given-When-Then）

- **AC-1（positive）**：Given Top 10 entry `{name: "x", author: "alice", downloads: 5}`, When AnalyticsPage 渲染, Then row 是 `<a href="/skills/alice/x">` 且包含 rank/name/downloads/progress-bar。
- **AC-2（negative — backend stale, no author key）**：Given Top 10 entry `{name: "x", downloads: 5}`（author 完全缺失）, When 渲染, Then row 是 `<div>`（non-link, no href），rank/name/downloads 完整渲染，不產生 `/skills/undefined/...` URL。
- **AC-3（negative — author === "undefined" string coercion）**：Given Top 10 entry `{name: "x", author: "undefined", downloads: 5}`（前端 path-template 把 JS undefined 字串化的防線）, When 渲染, Then 同 AC-2 fallback，不產生壞 URL。
- **AC-4（edge — empty Top 10）**：Given `topSkills: []`, When 渲染, Then 顯示「尚無下載記錄」 placeholder（既有 S088 行為不破壞）。

## 4. File Plan

| File | Action |
|------|--------|
| `frontend/src/pages/AnalyticsPage.tsx` | EDIT — 在 map 內加 hasValidAuthor guard + RankRow polymorphic switch |
| `frontend/src/pages/AnalyticsPage.test.tsx` | NEW — 4 ACs（vitest + RTL；mock useOverview 回各 fixture） |
| `frontend/src/api/analytics.ts` | NO CHANGE — type definition 保持 `author?: string` optional 反映實際運行時可能缺 |

## 5. Test Plan

```
cd frontend && npx vitest run src/pages/AnalyticsPage.test.tsx
```

Mock `useOverview` 4 fixture cases：
1. valid author → assert `getByRole('link', {name: /x/})` href 含 author
2. missing author → assert `queryByRole('link')` null + name 仍 render
3. author === "undefined" → 同 case 2 行為
4. empty topSkills → assert text 「尚無下載記錄」

Verification：vitest 4 PASS。
