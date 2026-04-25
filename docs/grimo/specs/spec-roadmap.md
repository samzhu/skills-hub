# Skills Hub — Spec Roadmap

## Milestone 0: Project Init ✅ `v0.1.0` (2026-04-25)
1/1 specs complete. Details → `specs/archive/2026-04-24-S000-project-init.md`

## Milestone 1: 技能瀏覽與搜尋 ✅ `v0.2.0` (2026-04-25)
2/2 specs complete. Details → `specs/archive/2026-04-25-S00[12]-*`

## Milestone 2: 技能發佈流程 ✅ `v0.3.0` (2026-04-25)
2/2 specs complete. Details → `specs/archive/2026-04-25-S00[34]-*`

## Milestone 3: 自動風險評估 ✅ `v0.4.0` (2026-04-25)
1/1 specs complete. Details → `specs/archive/2026-04-25-S005-*`

## Milestone 4: 一鍵安裝 — Web 下載 ✅ `v0.5.0` (2026-04-25)
1/1 specs complete. Details → `specs/archive/2026-04-25-S006-*`

---

## Milestone 5: 語意搜尋 ✅ `v0.7.0` (2026-04-25)
Goal: 使用者用自然語言描述需求，AI 推薦最適合的技能
Done when: S007 done

| # | Spec | Points | Dependencies | Status |
|---|------|--------|--------------|--------|
| S007 | 語意搜尋（Spring AI + Gemini + Firestore Vector） | M(14) | S001 | ✅ |

### S007: 語意搜尋

**Description:** SearchProjection 監聽 SkillCreated / SkillVersionPublished events，透過 Spring AI 整合 Gemini embedding API 產生 embedding，存入 Firestore（原生 SDK）。搜尋時用 `findNearest()` 做向量搜尋。前端搜尋框支援「關鍵字」/「語意」切換。

**SBE Acceptance Criteria:**
```
Scenario: 自然語言搜尋
  Given 平台上有 "docker-compose-helper" 和 "k8s-deployment" 等 skills
  When 使用者輸入「我想把應用部署到容器環境」
  Then 回傳語意相關的 skills
  And 結果按語意相關度排序

Scenario: 任務導向推薦
  Given 使用者輸入「幫我寫單元測試」
  Then 推薦測試相關的 skills
  And 附上匹配理由

Scenario: 無相關結果
  Given 沒有匹配的 skill
  Then 顯示「未找到匹配的技能」
```

**Estimation:**
| Dimension | Score | Reason |
|-----------|-------|--------|
| Technical risk | 3 | Firestore native SDK + MongoDB driver 混合 |
| Uncertainty | 2 | Embedding quality tuning |
| Dependencies | 2 | Spring AI, Gemini API, Firestore native SDK |
| Scope | 3 | ~8 files |
| Testing | 2 | Integration test with mock embeddings |
| Reversibility | 2 | Vector index migration |
| **Total** | **14** | **M** |

---

## Milestone 6: 使用數據分析 ✅ `v0.6.0` (2026-04-25)
1/1 specs complete. Details → `specs/archive/2026-04-25-S008-*`

---

## Summary

| Milestone | Specs | Total Points | 累計 | Status |
|-----------|-------|-------------|------|--------|
| M0: Project Init | S000 | S(10) | 10 | ✅ |
| M1: 技能瀏覽與搜尋 | S001, S002 | M(12) + S(11) = 23 | 33 | ✅ |
| M2: 技能發佈流程 | S003, S004 | M(12) + S(10) = 22 | 55 | ✅ |
| M3: 自動風險評估 | S005 | M(12) | 67 | ✅ |
| M4: 一鍵安裝（Web 下載） | S006 | S(9) | 76 | ✅ |
| M5: 語意搜尋 | S007 | M(14) | 90 | ✅ |
| M6: 使用數據分析 | S008 | S(11) | 101 | ✅ |

**Total: 9 specs, 101 story points — 9/9 specs shipped (101 points done)**

### Dependency Graph

```
S000 ──▶ S001 ──▶ S002             ✅
              │
              ├──▶ S003 ──▶ S004   ✅
              │       │
              │       ├──▶ S005    ✅
              │       │
              │       └──▶ S006 ──▶ S008  ✅
              │
              └──▶ S007                ✅
```

### Backlog (ES 進階功能)

| 優先級 | 功能 | 說明 |
|--------|------|------|
| ES-B1 | Event Replay | 從 domain_events 重建 read model |
| ES-B2 | Aggregate Snapshot | 定期快照 aggregate 狀態，加速載入 |
| ES-B3 | Event Upcasting | 事件 schema 版本遷移 |
| ES-B4 | Saga / Process Manager | 跨 aggregate 的長流程協調 |
