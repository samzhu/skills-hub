# S002: 技能瀏覽與搜尋 UI + 關鍵字搜尋

> Spec: S002 | Size: S(11) | Status: ⏳ Design
> Date: 2026-04-25

---

## 1. Goal

讓使用者能在 Web 介面上瀏覽、關鍵字搜尋、按分類篩選已上架的技能，並查看個別技能的詳情。

依賴 S001（code-level：imports `SkillReadModel`, `SkillReadModelRepository`, `SkillQueryService`）。S001 ⏳ Design 中，可平行設計，實作需等 S001 完成。

**Scope 調整 note:** Architecture doc 將 `GET /api/v1/skills`（列表/搜尋）指定給 `search` module。S001 spec §4.7 原定在 `SkillQueryController` 實作此 endpoint，S002 將其移入 `search/SearchController`。S001 的 `SkillQueryController` 只保留 `GET /api/v1/skills/{id}`。S001 spec 待同步更新。

**Detail page scope:** S002 的詳情頁只顯示 read model metadata。SKILL.md 全文 rendered markdown 需要 S003（上傳）提供內容，隨 S003 一起加入。

## 2. Approach

| Approach | Chosen | Rationale |
|----------|--------|-----------|
| **A: search/ module + MongoTemplate Criteria.regex()** | ⭐ yes | 符合 architecture doc 的 module 劃分；Firestore MongoDB compat 不支援 $text search，regex 是唯一可靠的關鍵字搜尋方式；MongoTemplate 支援動態 filter 組合 |
| B: skill/query/ module 擴展 | no | 違反 architecture doc 的 module 邊界；S007 語意搜尋也在 search/ module，混在 skill/query 會造成後續拆分 |
| C: Repository query methods (findByNameContainingIgnoreCase) | no | 固定方法簽名無法處理 optional filters 的動態組合；keyword + category + sort + pagination 的排列組合過多 |

### Key Decisions

1. **搜尋在 search/ module** — `SearchController` + `SearchService` 使用 `MongoTemplate` + `Criteria`，查詢 S001 建立的 `skills` read model collection
2. **Regex keyword search** — `Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE)` 在 name + description 做 OR 匹配。Firestore MongoDB compat 支援 `$regex`
3. **分類統計用 aggregation** — `GET /api/v1/categories` 用 MongoTemplate aggregation 從 skills collection group by category + count
4. **前端 SPA routing** — React Router v7 declarative mode，`/` (首頁) + `/skills/:id` (詳情)
5. **TanStack Query 管理 server state** — `placeholderData: keepPreviousData` 做頁面切換時的平滑過渡
6. **shadcn/ui 按需安裝** — 只裝 S002 需要的元件（input, card, badge, checkbox, label, tabs, pagination, breadcrumb）
7. **Vite proxy** — dev mode `/api/v1` proxy 到 `http://localhost:8080`，避免 CORS 問題
8. **Semantic 搜尋模式 disabled** — 搜尋框有 Keyword/Semantic toggle（匹配 UI mockup），但 Semantic 模式灰色不可用，待 S007

### Challenges Considered

- **Firestore MongoDB compat 不支援 $text search** — 已確認。使用 regex 作為替代。regex 在大量資料時效能較差，但 MVP 規模（< 1000 skills）可接受。未來可加 Firestore native SDK 的 full-text search。
- **兩個 controller 映射同一 path** — S001 的 `SkillQueryController` 和 S002 的 `SearchController` 都可能映射 `/api/v1/skills`。解法：S001 只保留 `/{id}` 路徑，列表搜尋由 S002 的 `SearchController` 負責。
- **排序「按相關度」** — regex search 沒有 relevance score。S002 MVP 的「按相關度排序」實作為：name 完全匹配 > name 部分匹配 > description 匹配，透過 query 結果排序。S007 語意搜尋才有真正的 similarity score。

### 2.3 Research Citations

- [React Router v7 docs](https://reactrouter.com/) — v7 consolidates `react-router-dom` into `react-router`。所有 imports 從 `"react-router"` 而非 `"react-router-dom"`。
- [TanStack Query v5 migration](https://tanstack.com/query/latest/docs/framework/react/guides/migrating-to-v5) — `keepPreviousData` 改為 `placeholderData: keepPreviousData`（named import from `@tanstack/react-query`）。
- [shadcn/ui Tailwind v4 support](https://ui.shadcn.com/docs/tailwind-v4) — 從 shadcn@2.3.0 支援 TW4，`npx shadcn@latest init` 自動偵測，不需 tailwind.config.js。
- [Firestore MongoDB compatibility](https://cloud.google.com/firestore/docs/reference/mongodb-compatibility) — 不支援 $text、Change Streams。支援 $regex、基本 aggregation（$group, $count）。
- [react-markdown v9](https://github.com/remarkjs/react-markdown) — 支援 React 19。S002 不使用（deferred to S003），但研究結果記錄供後續 spec 使用。

## 3. SBE Acceptance Criteria

Verification commands:

    Backend: cd backend && ./gradlew test
    Frontend: cd frontend && npm test
    Pass: all tests carrying S002 AC ids are green.

---

**AC-1: 用關鍵字搜尋技能**

```
Given 平台上有 5 個已上架的 skills:
      - docker-compose-helper (category: DevOps, description: "Generate docker-compose files")
      - k8s-deployment (category: DevOps, description: "Scaffold Kubernetes manifests")
      - junit-generator (category: Testing, description: "Write JUnit 5 tests")
      - terraform-module-author (category: DevOps)
      - openapi-scaffold (category: Documentation)
When  GET /api/v1/skills?keyword=docker&page=0&size=20
Then  回傳 200 + content 包含 docker-compose-helper（name 匹配）
And   content 不包含 junit-generator, terraform-module-author, openapi-scaffold
And   每筆包含 id, name, description, author, category, latestVersion, riskLevel, downloadCount
And   page.totalElements = 1（或 2 如果 k8s-deployment description 也含 docker）
```

**AC-2: 按分類篩選技能**

```
Given 平台上有 skills 分屬 DevOps(3), Testing(1), Documentation(1)
When  GET /api/v1/skills?category=DevOps&page=0&size=20
Then  回傳 200 + content 只包含 category = DevOps 的 3 筆 skills
And   page.totalElements = 3
```

**AC-3: 關鍵字 + 分類組合篩選**

```
Given 同 AC-1 的資料
When  GET /api/v1/skills?keyword=docker&category=DevOps&page=0&size=20
Then  回傳 200 + content 只包含 category=DevOps 且 name/description 含 "docker" 的 skills
```

**AC-4: 分類列表 API**

```
Given 平台上有 skills 分屬 DevOps(3), Testing(1), Documentation(1)
When  GET /api/v1/categories
Then  回傳 200 + 陣列 [{ name: "DevOps", count: 3 }, { name: "Testing", count: 1 }, ...]
And   按 count 降序排列
```

**AC-5: 技能詳情頁**

```
Given 已有一個 id 為 "abc123" 的 skill（read model 含 name, description, author, category,
      latestVersion, riskLevel, status, downloadCount, createdAt）
When  前端導覽到 /skills/abc123
Then  頁面顯示 skill 的 name, description, author, category
And   顯示 latestVersion badge, riskLevel pill
And   顯示 downloadCount metric card
And   顯示 Tabs（Overview active, Versions, 其他 tab 為 placeholder）
```

**AC-6: 首頁卡片網格顯示**

```
Given 平台上有 6 個已上架的 skills
When  前端載入首頁 /
Then  顯示搜尋框（含 beam 邊框動畫）
And   顯示左側分類 sidebar（含 skill 數量）
And   顯示 2 欄卡片網格
And   每張卡片顯示 name, author, description (2 行截斷), risk badge, downloads, version
```

## 4. Interface / API Design

### 4.1 Data Flow

```
User types "docker" in SearchBar
    │
    ▼
useSkillList({ keyword: "docker", category: null, page: 0, size: 20 })
    │
    ▼
GET /api/v1/skills?keyword=docker&page=0&size=20
    │
    ▼
SearchController.search(keyword, category, pageable)
    │
    ▼
SearchService.search("docker", null, PageRequest.of(0, 20, Sort.by(DESC, "downloadCount")))
    │
    ├─ Criteria.where("name").regex(/docker/i)
    │  .or(Criteria.where("description").regex(/docker/i))
    ├─ Query(criteria).with(pageable)
    └─ mongoTemplate.find(query, SkillReadModel.class, "skills")
    │
    ▼
Page<SkillReadModel> → JSON → TanStack Query cache → SkillCardGrid renders
```

### 4.2 Backend — Search API (search/)

```java
// search/SearchController.java
@RestController
@RequestMapping("/api/v1")
public class SearchController {
    private final SearchService searchService;

    @GetMapping("/skills")
    public Page<SkillReadModel> search(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String category,
        @PageableDefault(size = 20, sort = "downloadCount",
                         direction = Sort.Direction.DESC) Pageable pageable) {
        return searchService.search(keyword, category, pageable);
    }

    @GetMapping("/categories")
    public List<CategoryCount> categories() {
        return searchService.getCategoryCounts();
    }
}
```

```java
// search/SearchService.java
@Service
public class SearchService {
    private final MongoTemplate mongoTemplate;

    public Page<SkillReadModel> search(String keyword, String category,
                                        Pageable pageable) {
        var criteria = new Criteria();
        var filters = new ArrayList<Criteria>();

        // Only show published skills
        filters.add(Criteria.where("status").is("PUBLISHED"));

        if (StringUtils.hasText(keyword)) {
            var pattern = Pattern.compile(
                Pattern.quote(keyword), Pattern.CASE_INSENSITIVE);
            filters.add(new Criteria().orOperator(
                Criteria.where("name").regex(pattern),
                Criteria.where("description").regex(pattern)
            ));
        }

        if (StringUtils.hasText(category)) {
            filters.add(Criteria.where("category").is(category));
        }

        criteria = criteria.andOperator(filters.toArray(Criteria[]::new));
        var query = new Query(criteria).with(pageable);
        var results = mongoTemplate.find(query, SkillReadModel.class);
        var total = mongoTemplate.count(
            Query.of(query).limit(-1).skip(-1), SkillReadModel.class);
        return new PageImpl<>(results, pageable, total);
    }

    public List<CategoryCount> getCategoryCounts() {
        var agg = Aggregation.newAggregation(
            Aggregation.match(Criteria.where("status").is("PUBLISHED")),
            Aggregation.group("category").count().as("count"),
            Aggregation.sort(Sort.Direction.DESC, "count")
        );
        return mongoTemplate
            .aggregate(agg, "skills", CategoryCount.class)
            .getMappedResults();
    }
}
```

```java
// search/CategoryCount.java
public record CategoryCount(
    @Field("_id") String name,
    long count
) {}
```

**Example data (GET /api/v1/skills?keyword=docker response):**

```json
{
  "content": [
    {
      "id": "abc-123",
      "name": "docker-compose-helper",
      "description": "Generate, validate, and troubleshoot docker-compose files",
      "author": "platform-team",
      "category": "DevOps",
      "tags": ["docker", "compose"],
      "latestVersion": "2.1.0",
      "riskLevel": "LOW",
      "status": "PUBLISHED",
      "downloadCount": 1284,
      "createdAt": "2026-01-12T10:00:00Z"
    }
  ],
  "page": { "number": 0, "size": 20, "totalElements": 1, "totalPages": 1 }
}
```

**Example data (GET /api/v1/categories response):**

```json
[
  { "name": "DevOps", "count": 58 },
  { "name": "Testing", "count": 42 },
  { "name": "Documentation", "count": 31 }
]
```

### 4.3 Frontend — Type Definitions

```typescript
// types/skill.ts
export interface Skill {
  id: string
  name: string
  description: string
  author: string
  category: string
  tags: string[]
  latestVersion: string | null
  riskLevel: string | null   // "LOW" | "MEDIUM" | "HIGH"
  status: string             // "DRAFT" | "PUBLISHED" | "SUSPENDED"
  downloadCount: number
  createdAt: string
  updatedAt: string
}

export interface SpringPage<T> {
  content: T[]
  page: {
    number: number
    size: number
    totalElements: number
    totalPages: number
  }
}

export interface CategoryCount {
  name: string
  count: number
}
```

### 4.4 Frontend — API Client

```typescript
// api/client.ts
const BASE = '/api/v1'

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, init)
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new Error(body.message ?? `API error ${res.status}`)
  }
  return res.json()
}
```

```typescript
// api/skills.ts
import { apiFetch } from './client'
import type { Skill, SpringPage, CategoryCount } from '../types/skill'

export interface SkillSearchParams {
  keyword?: string
  category?: string
  page?: number
  size?: number
  sort?: string
}

export function fetchSkills(params: SkillSearchParams): Promise<SpringPage<Skill>> {
  const search = new URLSearchParams()
  if (params.keyword) search.set('keyword', params.keyword)
  if (params.category) search.set('category', params.category)
  search.set('page', String(params.page ?? 0))
  search.set('size', String(params.size ?? 20))
  if (params.sort) search.set('sort', params.sort)
  return apiFetch(`/skills?${search}`)
}

export function fetchSkillById(id: string): Promise<Skill> {
  return apiFetch(`/skills/${id}`)
}

export function fetchCategories(): Promise<CategoryCount[]> {
  return apiFetch('/categories')
}
```

### 4.5 Frontend — Hooks

```typescript
// hooks/useSkillList.ts
import { useQuery, keepPreviousData } from '@tanstack/react-query'
import { fetchSkills, type SkillSearchParams } from '../api/skills'

export function useSkillList(params: SkillSearchParams) {
  return useQuery({
    queryKey: ['skills', 'list', params],
    queryFn: () => fetchSkills(params),
    placeholderData: keepPreviousData,
  })
}
```

```typescript
// hooks/useSkill.ts
import { useQuery } from '@tanstack/react-query'
import { fetchSkillById } from '../api/skills'

export function useSkill(id: string) {
  return useQuery({
    queryKey: ['skills', id],
    queryFn: () => fetchSkillById(id),
    enabled: !!id,
  })
}
```

### 4.6 Frontend — Page Layout (HomePage)

```
┌─────────────────────────────────────────────────────────────┐
│  [S] Skills Hub   Browse  My skills  Analytics  Docs   [MC] │
├─────────────────────────────────────────────────────────────┤
│           Discover agent skills                   [Publish] │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ 🔍 Try "deploy to kubernetes"...  [Keyword|Semantic] │  │ ← beam border
│  └───────────────────────────────────────────────────────┘  │
│  [Popular] [Newest] [Trending] [Top rated]    247 skills    │
├────────┬────────────────────────────────────────────────────┤
│CATEGOR │  ┌──────────────┐  ┌──────────────┐               │
│All  247│  │ docker-...   │  │ k8s-deploy.. │               │
│DevOps58│  │ platform-team│  │ infra-guild  │               │
│Test  42│  │ Low ↓1,284   │  │ Med ↓892     │               │
│Doc   31│  └──────────────┘  └──────────────┘               │
│        │  ┌──────────────┐  ┌──────────────┐               │
│RISK    │  │ junit-gen... │  │ terraform-.. │               │
│☑ Low   │  │ quality-team │  │ cloud-platf  │               │
│☑ Med   │  │ Low ↓641     │  │ Med ↓512     │               │
│☐ High  │  └──────────────┘  └──────────────┘               │
│        │                                                    │
│COMPAT  │  ← Page 1 of 3 →                                  │
│☑ Claude│                                                    │
└────────┴────────────────────────────────────────────────────┘
```

### 4.7 Frontend — Page Layout (SkillDetailPage)

```
┌─────────────────────────────────────────────────────────────┐
│  [S] Skills Hub    Browse / DevOps / docker-compose   [MC]  │
├─────────────────────────────────────────────────────────────┤
│  [DC] docker-compose-helper  v2.1.0  Low  ✓Verified        │
│       Generate, validate, and troubleshoot docker-compose.. │
│       by platform-team · MIT · Updated 3 days ago · DevOps  │
│                                              [Star][↓ DL]   │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐       │
│  │Downloads │ │Rating    │ │Versions  │ │Open flags│       │
│  │ 1,284    │ │ 4.8/5    │ │ 7        │ │ 0        │       │
│  │ ↑18%     │ │ 42 rev   │ │ 3d ago   │ │ no flags │       │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘       │
│  [Overview] [Risk assessment] [Versions] [Reviews] [Flags]  │
├────────────────────────────────────┬────────────────────────┤
│  Skill metadata:                   │ Details               │
│  name: docker-compose-helper       │ Published: Jan 12     │
│  description: Generate, validate.. │ License: MIT          │
│  author: platform-team             │ Category: DevOps      │
│  category: DevOps                  │                       │
│  tags: [docker, compose]           │ Compatibility         │
│                                    │ Claude Code · Cursor  │
│  (SKILL.md full content coming     │                       │
│   in S003)                         │ Version history       │
│                                    │ v2.1.0  Latest  ↓    │
│                                    │ v2.0.1  2w ago  ↓    │
│                                    │ v2.0.0  1mo ago ↓    │
└────────────────────────────────────┴────────────────────────┘
```

### 4.8 Vite Dev Proxy

```typescript
// vite.config.ts — add server.proxy
export default defineConfig({
  // ...existing config
  server: {
    proxy: {
      '/api/v1': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
```

## 5. File Plan

Package base: `io.github.samzhu.skillshub` (abbreviated as `...`)

| # | File | Action | Description |
|---|------|--------|-------------|
| **Backend — search module** |||
| 1 | `.../search/SearchService.java` | new | MongoTemplate + Criteria keyword search + category filter + pagination |
| 2 | `.../search/SearchController.java` | new | GET /api/v1/skills (search/browse), GET /api/v1/categories |
| 3 | `.../search/CategoryCount.java` | new | Record: category name + count |
| **Frontend — types + API** |||
| 4 | `frontend/src/types/skill.ts` | new | Skill, SpringPage, CategoryCount interfaces |
| 5 | `frontend/src/api/client.ts` | new | Base fetch wrapper with error handling |
| 6 | `frontend/src/api/skills.ts` | new | fetchSkills, fetchSkillById, fetchCategories |
| **Frontend — hooks** |||
| 7 | `frontend/src/hooks/useSkillList.ts` | new | TanStack Query: paginated skill search |
| 8 | `frontend/src/hooks/useSkill.ts` | new | TanStack Query: single skill by ID |
| 9 | `frontend/src/hooks/useCategories.ts` | new | TanStack Query: category list with counts |
| **Frontend — components (shadcn/ui install)** |||
| 10 | `frontend/src/components/ui/*` | install | `npx shadcn@latest add input card badge checkbox label tabs pagination breadcrumb separator` |
| **Frontend — custom components** |||
| 11 | `frontend/src/components/AppShell.tsx` | new | Top nav bar + layout wrapper (logo, nav links, user avatar) |
| 12 | `frontend/src/components/SearchBar.tsx` | new | Search input with beam border + keyword/semantic toggle |
| 13 | `frontend/src/components/SkillCard.tsx` | new | Skill card: icon, name, author, risk badge, desc, stats |
| 14 | `frontend/src/components/SkillCardGrid.tsx` | new | 2-column responsive grid of SkillCards |
| 15 | `frontend/src/components/CategorySidebar.tsx` | new | Categories, risk filters, compatibility filters |
| 16 | `frontend/src/components/RiskBadge.tsx` | new | Colored pill: Low(green), Med(amber), High(red) |
| 17 | `frontend/src/components/MetricCard.tsx` | new | Stat card: label, value, subtitle (used in detail page) |
| **Frontend — pages** |||
| 18 | `frontend/src/pages/HomePage.tsx` | new | Browse: SearchBar + CategorySidebar + SkillCardGrid + Pagination |
| 19 | `frontend/src/pages/SkillDetailPage.tsx` | new | Detail: header + metrics + tabs (Overview/Versions) + sidebar |
| **Frontend — app shell** |||
| 20 | `frontend/src/App.tsx` | modify | Replace BorderBeam demo with BrowserRouter + Routes + AppShell |
| 21 | `frontend/src/main.tsx` | modify | Wrap with QueryClientProvider |
| **Frontend — dev config** |||
| 22 | `frontend/vite.config.ts` | modify | Add proxy: /api/v1 → localhost:8080 |
| **Tests — backend** |||
| 23 | `.../search/SearchServiceTest.java` | new | AC-1: keyword search, AC-2: category filter, AC-3: combo, AC-4: categories |
| 24 | `.../search/SearchControllerTest.java` | new | MockMvc: AC-1 ~ AC-4 API tests |
| **Tests — frontend** |||
| 25 | `frontend/src/pages/__tests__/HomePage.test.tsx` | new | AC-6: card grid rendering, search interaction |
| 26 | `frontend/src/pages/__tests__/SkillDetailPage.test.tsx` | new | AC-5: detail page metadata display |
| **Docs** |||
| 27 | `docs/grimo/specs/spec-roadmap.md` | modify | S002 status 🔲 → ⏳ |

### Frontend Dependency Additions (package.json)

| Package | Version | Note |
|---------|---------|------|
| `lucide-react` | latest | Icons for search, download, star, etc. (shadcn/ui peer) |

No other new runtime dependencies. `react-markdown` deferred to S003.

## Estimation

| Dimension | Score | Reason |
|-----------|-------|--------|
| Technical risk | 1 | Known tech: React + REST + MongoDB regex |
| Uncertainty | 2 | UI layout details, search UX |
| Dependencies | 2 | S001 read model + S000 frontend scaffold |
| Scope | 3 | ~24 files (frontend pages, components, hooks, API, backend search, tests) |
| Testing | 2 | Component tests + integration test with Testcontainers |
| Reversibility | 1 | Easy |
| **Total** | **11** | **S** |
