# S006: Skill 下載 API + UI（含 SkillDownloaded event）

> Spec: S006 | Size: S(9) | Status: ✅ Done
> Date: 2026-04-25

---

## 1. Goal

讓使用者從 Web 下載技能的 zip 檔，並記錄下載事件供 S008 數據分析使用。

依賴 S003（✅ shipped）— 使用 `StorageService.download()`、`SkillVersionReadModel`、`SkillVersionReadModelRepository`。

## 2. Approach

Download API in `skill.command` module（produces SkillDownloaded event）+ frontend download button on detail page.

### Key Decisions

1. **Download endpoint** — `GET /api/v1/skills/{id}/download` (latest) + `GET /api/v1/skills/{id}/versions/{ver}/download` (specific version). Returns zip as `application/octet-stream`.
2. **SkillDownloaded event** — Persisted to event store, projection updates `downloadCount` on skills read model.
3. **Download events read model** — `download_events` collection for S008 analytics. Simple `@EventListener` projection.
4. **Frontend** — Download button on SkillDetailPage + per-version download in VersionList. Install guide text below button.

## 3. SBE Acceptance Criteria

Verification command:

    Run: cd backend && ./gradlew test
    Pass: all tests carrying S006 AC ids are green.

**AC-1: 下載最新版本**

```
Given skill abc has v1.0.0 (stored in StorageService)
When  GET /api/v1/skills/abc/download
Then  returns 200 + application/octet-stream with zip content
And   domain_events has SkillDownloaded event
And   skills read model downloadCount incremented
```

**AC-2: 下載指定版本**

```
Given skill abc has v1.0.0 and v1.1.0
When  GET /api/v1/skills/abc/versions/1.0.0/download
Then  returns 200 + v1.0.0 zip content
```

**AC-3: Download event recorded**

```
Given user downloads skill abc v1.0.0
Then  download_events collection has entry {skillId, version, downloadedAt}
```

**AC-4: Frontend 下載按鈕**

```
Given skill detail page for abc
Then  顯示「下載」按鈕
And   版本歷史每行有下載按鈕
And   安裝指引文字顯示
```

## 4. Interface / API Design

```
GET /api/v1/skills/{id}/download
  → find latest version from skill_versions → StorageService.download(storagePath) → return bytes
  → publish SkillDownloaded event → projection updates downloadCount

GET /api/v1/skills/{id}/versions/{version}/download
  → find specific version → StorageService.download(storagePath) → return bytes
  → publish SkillDownloaded event
```

## 5. File Plan

| # | File | Action |
|---|------|--------|
| **Backend** ||
| 1 | `.../skill/domain/SkillDownloadedEvent.java` | new |
| 2 | `.../skill/command/SkillCommandService.java` | modify — add downloadSkill() |
| 3 | `.../skill/command/SkillCommandController.java` | modify — add GET download endpoints |
| 4 | `.../skill/query/SkillProjection.java` | modify — handle SkillDownloaded → increment downloadCount |
| 5 | `.../analytics/DownloadEventReadModel.java` | new — @Document("download_events") |
| 6 | `.../analytics/DownloadEventRepository.java` | new |
| 7 | `.../analytics/AnalyticsProjection.java` | new — @EventListener on SkillDownloaded |
| 8 | `.../analytics/package-info.java` | modify — add dependencies |
| **Frontend** ||
| 9 | `frontend/src/pages/SkillDetailPage.tsx` | modify — download button + install guide |
| 10 | `frontend/src/components/VersionList.tsx` | modify — per-version download link |
| **Tests** ||
| 11 | `.../skill/command/SkillDownloadTest.java` | new — AC-1, AC-2, AC-3 |
