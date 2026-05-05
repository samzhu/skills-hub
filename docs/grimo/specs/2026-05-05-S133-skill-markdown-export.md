# S133: Skill Markdown Export — Agent-Friendly Copy / Open

> Spec: S133 | Size: XS(8) | Status: ⏳ Design
> Date: 2026-05-05

---

## 1. Goal

每個 skill 詳情頁右上加一個「Markdown ▾」dropdown，仿 platform.claude.com docs
的 "Copy page as Markdown" / "Open Markdown" 模式，把既有 `SKILL.md` 用 agent /
curl / RAG pipeline 友善的形式對外暴露。

```
[人類]   點「Markdown ▾」→ 選「複製為 Markdown」→ paste 進 Claude / Cursor → agent 直接吃
[人類]   點「Markdown ▾」→ 選「開啟 Markdown」→ 新分頁顯示 raw text/markdown
[Agent] curl https://.../api/v1/skills/{id}/skill.md → 200 + text/markdown + 完整 SKILL.md
```

對齊業界既驗模式（Mintlify / Fern / Anthropic docs 三家都用 `.md` URL 後綴 +
dropdown）。為什麼是 XS：後端 80% 已存在（`FileBrowserController` 既能回
`text/markdown`，per S074 ship），本 spec 只加一個短網址 alias + 前端 UI 包裝。

## 2. Approach

XS spec — 不做 approach 比較，直接設計（per planning-spec rubric）。

### 2.1 設計決策

| 決策 | 選擇 | 理由 |
|------|------|------|
| 後端 raw md endpoint | 加 alias `/api/v1/skills/{id}/skill.md` 委派給既有 `FileBrowserService.readFile(id, "SKILL.md")` | 既有 `/files/SKILL.md` 已 80% 滿足；alias 短網址對 agent/curl 友善；統一在 `/api/v1/` 命名空間避開 SPA route 衝突 |
| URL 樣式 | `/api/v1/skills/{id}/skill.md`（API 前綴）| `/skills/{id}.md` 會撞 React Router SPA route；需 backend 在 SPA fallback 之前 intercept，可逆性下降 |
| Cache strategy | `Cache-Control: public, max-age=60` + ETag | latest 版可能變動；60s 足夠 agent burst fetch + 寬鬆讓作者 republish 後 < 1min 看到新版 |
| ACL gate | `@PreAuthorize("hasPermission(#id, 'Skill', 'read')")` 對齊 `SkillQueryController.getById` | 對齊 S122 read-side ACL chain；anonymous 對 PRIVATE skill → 401 |
| Dropdown UI | shadcn `DropdownMenu`（須跑 `npx shadcn@latest add dropdown-menu`）| `radix-ui` v1.4.3 已在 deps，但 shadcn 需要 local wrapper file `components/ui/dropdown-menu.tsx`（per shadcn 2026-02 changelog） |
| Trigger button | 「Markdown ▾」文字 + `ChevronDown` icon | 對齊 platform.claude.com 截圖的 label 化模式；比純 icon 更直白；未來可擴展成「分享 / 匯出」menu |
| Copy 內容 shape | SKILL.md 原始 body（含 frontmatter） | Mintlify / Fern 一致；`name` + `description` frontmatter 已自描述；不注入 URL header 避免 agent cache stale URL |
| Clipboard pattern | `navigator.clipboard.write([new ClipboardItem({'text/plain': fetchPromise})])` | Safari-safe 唯一正解；`writeText()` + `await fetch` 在 Safari 會 silent fail（transient activation 失效） |
| Prefetch | `DropdownMenu onOpenChange` 開啟時預抓 | ~200-500ms cache 預熱；click 時若 cache hit 走 `Promise.resolve` 同步路徑 |
| Toast 反饋 | sonner（既有 `frontend/package.json` 已含）| 與既有 LAB 模式錯誤提示一致 |
| 顯示條件 | 僅 `status === 'PUBLISHED'` 時 mount dropdown | DRAFT 沒可下載版本；SUSPENDED endpoint 已 403；對齊既有 `下載` button gate (SkillDetailPage.tsx:272) |
| Out of scope | versioned URL / site-wide `/llms.txt` / MCP server / CLI install | 留 follow-up spec — 現階段 latest 版已涵蓋主要 agent UX |

### 2.2 為什麼不重寫 FileBrowserController

`FileBrowserController` 通用、處理任何 zip entry path、有 zip-slip 防禦、有 1MB
preview gate（`FileBrowserService.MAX_FILE_SIZE`）。SKILL.md alias 是「特化的便利
URL」而非「不同行為」，委派而非 fork：

```java
// 反模式（拒）：複製整套 zip 解析、SUSPENDED guard、storage download
// 正模式（採）：alias controller call fileBrowserService.readFile(id, "SKILL.md")
//                 → 共用 fail-fast、ACL、防禦邏輯
```

### 2.3 Research Citations

**Industry pattern — `.md` URL suffix（同 URL append `.md` 是 2026 共識）：**
- Mintlify Feb 2025 announcement — 所有 hosted docs page append `.md` 即取 raw markdown：[x.com/mintlify/status/1889358844847071660](https://x.com/mintlify/status/1889358844847071660)
- Fern customize-llm-output — 支援 `.md` / `.mdx` 後綴，配合 `<llms-ignore>` / `<llms-only>` 過濾標籤：[buildwithfern.com/learn/docs/ai-features/customize-llm-output](https://buildwithfern.com/learn/docs/ai-features/customize-llm-output)
- llmstxt.org spec — 規格未要求 content negotiation，採 explicit `.md` 後綴：[llmstxt.org](https://llmstxt.org/)

**Clipboard API — Safari async trap（load-bearing 決策）：**
- WebKit blog Async Clipboard API — `ClipboardItem` 接 `Promise<Blob>` 是 Safari 唯一正解；`writeText()` 必須同步呼叫：[webkit.org/blog/10855/async-clipboard-api/](https://webkit.org/blog/10855/async-clipboard-api/)
- kian.org.uk — Safari transient activation 必須 synchronously consume：[kian.org.uk/writing-to-clipboard-in-safari-transient-activation/](https://kian.org.uk/writing-to-clipboard-in-safari-transient-activation/)
- Wolfgang Rittner Safari fix — 同 `ClipboardItem + Promise` 模式：[wolfgangrittner.dev/how-to-use-clipboard-api-in-safari/](https://wolfgangrittner.dev/how-to-use-clipboard-api-in-safari/)
- MDN Clipboard.writeText() — HTTPS-only、focus required：[developer.mozilla.org/.../Clipboard/writeText](https://developer.mozilla.org/en-US/docs/Web/API/Clipboard/writeText)
- Mintlify discussion #847 — 確認 Mintlify 自家實作在 Safari 確實踩雷：[github.com/orgs/mintlify/discussions/847](https://github.com/orgs/mintlify/discussions/847)

**shadcn/ui DropdownMenu — install 路徑（2026 unified Radix 變更）：**
- shadcn 2026-02 changelog — `new-york` style 已從 individual `@radix-ui/react-*` 遷至 unified `radix-ui` package；CLI 仍寫 local wrapper：[ui.shadcn.com/docs/changelog/2026-02-radix-ui](https://ui.shadcn.com/docs/changelog/2026-02-radix-ui)
- shadcn DropdownMenu component docs：[ui.shadcn.com/docs/components/radix/dropdown-menu](https://ui.shadcn.com/docs/components/radix/dropdown-menu)

**Existing project surface（Step 0.5 mapping）：**
- `FileBrowserController.java:48` — 既有 `GET /api/v1/skills/{id}/files/{*path}` 處理任意 zip entry
- `FileBrowserService.java:170` — `inferMimeType` 對 `.md` 回 `text/markdown`
- `FileBrowserService.java:101-131` — `readFile()` 已含 zip-slip + `MAX_FILE_SIZE` 1MB gate + `SUSPENDED` guard
- `SkillQueryController.java:74-78` — `@PreAuthorize hasPermission read` 為既驗 ACL pattern（S122）
- `SecurityConfig.java:126-135` — CORS allowlist 全域涵蓋 `/api/**`（S128）
- `SkillDetailPage.tsx:272-283` — 下載按鈕現位於 `SkillHero` 右側 button cluster；新 dropdown 加在此

### 2.4 Confidence Classification

| Decision | Confidence | Evidence |
|----------|-----------|----------|
| 後端 alias 委派 `FileBrowserService.readFile` | **Validated** | 既有 service public method 簽名已驗（S074 ship）；無新行為 |
| `Content-Type: text/markdown` for SKILL.md | **Validated** | `FileBrowserService.java:170` raw source confirmed |
| ACL gate `@PreAuthorize hasPermission read` | **Validated** | S122 同 pattern 已在 prod；`SkillQueryController.java:74-78` |
| Safari `ClipboardItem + Promise` 為唯一正解 | **Validated** | WebKit 官方 blog + MDN + Mintlify 自家踩雷 issue 三方交叉驗證 |
| shadcn `DropdownMenu` 仍走 `shadcn add` CLI | **Validated** | shadcn 2026-02 changelog 明確說明 local wrapper 仍由 CLI 寫 |

無 Hypothesis、無 Unknown — 不需 POC。

## 3. SBE Acceptance Criteria

驗收命令（per `qa-strategy.md`）：
- 後端：`./gradlew test`（V01）— SkillMarkdownControllerTest carrying `@Tag("AC-N")`
- 前端：`cd frontend && npm test`（V04）— Vitest carrying `describe('AC-N: ...')`

```
AC-1: Agent 可直接 curl 取得 raw SKILL.md
  Given 已 PUBLISHED 的 PUBLIC skill `id=abc-123`
  When agent 對 GET /api/v1/skills/abc-123/skill.md 發 request
  Then 回 HTTP 200
  And Content-Type: text/markdown
  And Body 為該 skill 最新版本 zip 內 SKILL.md 完整內容（含 YAML frontmatter）
  And Cache-Control header 含 public, max-age=60

AC-2: 無讀取權限的 PRIVATE skill 走 401/403
  Given PRIVATE skill `id=xyz-456`，無 ACL grant 給 anonymous
  When anonymous user 對 GET /api/v1/skills/xyz-456/skill.md 發 request
  Then 回 HTTP 401（per Spring Security ExceptionTranslationFilter for AnonymousAuthenticationToken）
  And Body 不含 skill 任何 metadata

AC-3: SUSPENDED skill 走 403（對齊既有 download 行為）
  Given PUBLISHED 但 SUSPENDED 的 skill `id=def-789`
  When 對 GET /api/v1/skills/def-789/skill.md 發 request
  Then 回 HTTP 403 SKILL_SUSPENDED（複用既有 SkillSuspendedException → GlobalExceptionHandler mapping）

AC-4: 不存在的 skill 走 404
  Given skill `id` 不存在
  When 對 GET /api/v1/skills/{id}/skill.md 發 request
  Then 回 HTTP 404（複用既有 NoSuchElementException → 404 mapping）

AC-5: SkillDetailPage 對 PUBLISHED skill 顯示「Markdown ▾」dropdown
  Given user 訪問 /skills/abc-123 且該 skill 為 PUBLISHED
  When SkillDetailPage 渲染完成
  Then 在 hero 區下載按鈕旁出現「Markdown ▾」trigger
  And 點擊 trigger 展開 menu，顯示「複製為 Markdown」+「開啟 Markdown」兩 item

AC-6: DRAFT / SUSPENDED skill 不顯示 dropdown
  Given user 訪問 /skills/{id} 且該 skill status 為 DRAFT 或 SUSPENDED
  When SkillDetailPage 渲染完成
  Then「Markdown ▾」trigger 不出現（對齊既有「下載」button 顯示條件）

AC-7: 「複製為 Markdown」走 Safari-safe clipboard pattern
  Given user 在 PUBLISHED skill 詳情頁打開 dropdown
  When 點擊「複製為 Markdown」
  Then 呼叫 navigator.clipboard.write([new ClipboardItem({'text/plain': Promise<Blob>})])（NOT writeText with awaited fetch）
  And 寫入內容 = SKILL.md raw body
  And 顯示 sonner toast「已複製到剪貼簿」

AC-8: 「開啟 Markdown」新分頁開啟 raw URL
  Given user 在 PUBLISHED skill 詳情頁打開 dropdown
  When 點擊「開啟 Markdown」
  Then 新分頁開啟 GET /api/v1/skills/{id}/skill.md
  And anchor target="_blank" + rel="noopener noreferrer"
```

## 4. Interface / API Design

### 4.1 Backend — `SkillMarkdownController`

```java
package io.github.samzhu.skillshub.skill.query;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S133：Skill SKILL.md raw markdown 短網址 alias。
 *
 * <p>對齊 Mintlify / Fern / platform.claude.com 業界 `.md` URL pattern；agent /
 * curl / RAG pipeline 友善 endpoint。委派 {@link FileBrowserService#readFile} 共用
 * zip-slip + size + SUSPENDED 防禦。
 *
 * <p>ACL：對齊 {@link SkillQueryController#getById} 既驗 read permission gate（S122）。
 */
@RestController
@RequestMapping("/api/v1/skills/{id}")
public class SkillMarkdownController {

    private final FileBrowserService fileBrowserService;

    public SkillMarkdownController(FileBrowserService fileBrowserService) {
        this.fileBrowserService = fileBrowserService;
    }

    /**
     * 回傳 skill 最新版本的 SKILL.md raw content。
     *
     * <p>Content-Type: text/markdown（per FileBrowserService.inferMimeType()）。
     * Cache: public, max-age=60 — agent burst fetch 友善，author republish 後 < 1min 同步。
     */
    @GetMapping("/skill.md")
    @PreAuthorize("hasPermission(#id, 'Skill', 'read')")
    ResponseEntity<byte[]> getSkillMd(@PathVariable UUID id) {
        var preview = fileBrowserService.readFile(id.toString(), "SKILL.md");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(preview.contentType()))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(60)).cachePublic())
                .body(preview.content());
    }
}
```

### 4.2 Frontend — `MarkdownActionMenu` 元件

```tsx
// frontend/src/components/MarkdownActionMenu.tsx
import { ChevronDown, Copy, ExternalLink } from 'lucide-react'
import { toast } from 'sonner'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useCopySkillMarkdown } from '@/hooks/useCopySkillMarkdown'

interface Props { skillId: string }

export function MarkdownActionMenu({ skillId }: Props) {
  const url = `/api/v1/skills/${skillId}/skill.md`
  const { prefetch, copy } = useCopySkillMarkdown(skillId)

  return (
    <DropdownMenu onOpenChange={(open) => open && prefetch()}>
      <DropdownMenuTrigger
        className="inline-flex items-center gap-1.5 rounded-md border border-border bg-transparent px-3 py-2 text-[13px] font-medium text-foreground hover:bg-secondary/50"
        aria-label="Markdown 操作"
      >
        Markdown
        <ChevronDown className="h-3.5 w-3.5" />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuItem onClick={() => copy().then(() => toast.success('已複製到剪貼簿'))}>
          <Copy className="mr-2 h-4 w-4" /> 複製為 Markdown
        </DropdownMenuItem>
        <DropdownMenuItem asChild>
          <a href={url} target="_blank" rel="noopener noreferrer">
            <ExternalLink className="mr-2 h-4 w-4" /> 開啟 Markdown
          </a>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
```

### 4.3 Frontend — `useCopySkillMarkdown` hook（Safari-safe pattern）

```ts
// frontend/src/hooks/useCopySkillMarkdown.ts
import { useCallback, useRef } from 'react'

export function useCopySkillMarkdown(skillId: string) {
  const url = `/api/v1/skills/${skillId}/skill.md`
  const cache = useRef<string | null>(null)

  const prefetch = useCallback(async () => {
    if (cache.current) return
    try {
      cache.current = await fetch(url).then((r) => (r.ok ? r.text() : Promise.reject(r)))
    } catch {
      // prefetch 失敗靜默；click 時會再試
    }
  }, [url])

  // 必須從 click handler 同步呼叫到 navigator.clipboard.write — Safari transient activation 限制
  const copy = useCallback((): Promise<void> => {
    const blobPromise: Promise<Blob> = cache.current
      ? Promise.resolve(new Blob([cache.current], { type: 'text/plain' }))
      : fetch(url).then((r) => r.text()).then((text) => {
          cache.current = text
          return new Blob([text], { type: 'text/plain' })
        })
    return navigator.clipboard.write([new ClipboardItem({ 'text/plain': blobPromise })])
  }, [url])

  return { prefetch, copy }
}
```

### 4.4 Frontend — `SkillDetailPage` 整合點

`SkillHero` 右側 button cluster（line 272-283 既有 `<SubscribeButton>` + 下載 anchor）
插入 `<MarkdownActionMenu skillId={skill.id} />`，僅在 `skill.status === 'PUBLISHED'`
時 render（對齊既有 cluster gate）。

## 5. File Plan

| File | Action | Description |
|------|--------|-------------|
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillMarkdownController.java` | new | T01 — alias controller，委派 FileBrowserService |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillMarkdownControllerTest.java` | new | T01 test — `@WebMvcTest` slice extends `WebMvcSliceTestBase`；AC-1 ~ AC-4 |
| `frontend/src/components/ui/dropdown-menu.tsx` | new (via `npx shadcn@latest add dropdown-menu`) | T02 — shadcn local wrapper |
| `frontend/src/hooks/useCopySkillMarkdown.ts` | new | T03 — Safari-safe clipboard hook |
| `frontend/src/hooks/useCopySkillMarkdown.test.ts` | new | T03 test — mock fetch + navigator.clipboard.write；驗 ClipboardItem 路徑 + cache hit/miss；AC-7 |
| `frontend/src/components/MarkdownActionMenu.tsx` | new | T04 — dropdown UI 元件 |
| `frontend/src/components/MarkdownActionMenu.test.tsx` | new | T04 test — render + click + dropdown 開合；AC-5 / AC-7 / AC-8 |
| `frontend/src/pages/SkillDetailPage.tsx` | modify | T05 — `SkillHero` 加 `<MarkdownActionMenu>` mount + AC-6 status gate |
| `frontend/src/pages/SkillDetailPage.test.tsx` | modify | T05 test — AC-5 / AC-6 dropdown 顯示條件 |
| `frontend/package.json` | modify | T02 — `sonner` 已存在則無變動；確認 shadcn add 寫入後的 deps |
| `docs/grimo/specs/spec-roadmap.md` | modify | T06 doc-sync — 加 S133 entry，status ⏳ Design → ✅ ship 後更新 |
| `docs/grimo/CHANGELOG.md` | modify | T06 doc-sync — 在 ship 時 append（由 /shipping-release 處理） |

---

## 6. Task Plan

POC: **not required** — §2.4 confidence table 顯示 5 個 load-bearing decisions 全 **Validated**（既有 `FileBrowserService.readFile` 行為已驗 / `@PreAuthorize hasPermission read` S122 既驗 / Safari `ClipboardItem + Promise` 由 WebKit blog + MDN + Mintlify 自家 issue 三方交叉驗證 / shadcn 2026-02 changelog 明確說明 install 行為）。無 Hypothesis、無 Unknown。

| # | Task | AC | Status |
|---|------|----|--------|
| T01 | Backend `SkillMarkdownController` alias + WebMvc slice test | AC-1 / AC-2 / AC-3 / AC-4 | pending |
| T02 | Frontend shadcn DropdownMenu install + `useCopySkillMarkdown` hook + `MarkdownActionMenu` 元件 + SkillDetailPage mount + Vitest | AC-5 / AC-6 / AC-7 / AC-8 | pending |
| T03 | E2E manual smoke — 真 browser × 真 backend；驗 8 個 AC end-to-end + 2 個 boundary scenario（large SKILL.md / unicode）| ALL | pending |

Execution order: T01 → T02 → T03（T02 frontend test 用 mock 不打真 backend，但 T03 需要 T01 的 endpoint live）。

### E2E Smoke Rationale（per planning-tasks protocol）

T02 frontend Vitest 必須 mock `fetch` 與 `navigator.clipboard.write`（jsdom 不原生支援
ClipboardItem）。Stubs 證明 logic、不證明 assembly：CORS / Spring Security / FileBrowserService
真實鏈路、Chrome/Safari ClipboardItem polyfill 真實行為、shadcn DropdownMenu dark theme 對齊 —
皆需真 browser × 真 backend smoke。T03 為 mandatory final task per protocol。

---

<!-- Section 7 added by /planning-tasks after Phase 4 consolidation -->
