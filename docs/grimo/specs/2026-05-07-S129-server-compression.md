# S129 — Server Compression

**Status:** 📐 設計中
**Size:** XS(1 pt)
**Depends on:** —
**Target version:** v4.9.0

---

## §1 Goal

為所有 REST API 回應啟用 HTTP Gzip 壓縮，減少 bandwidth — 特別對 JSON 集合回應（skill list、search results）效果明顯。純 Spring Boot `server.compression` YAML 設定，零 Java code 異動。

---

## §2 Approach

在 `application.yaml` 加 `server.compression` block：

```yaml
server:
  compression:
    enabled: true
    mime-types: >-
      text/html,text/xml,text/plain,text/css,
      text/javascript,application/javascript,
      application/json,application/xml
    min-response-size: 2048
```

- `min-response-size: 2048` — 小於 2 KB 的回應不壓縮（壓縮 overhead 大於收益）
- `mime-types` 涵蓋 API 主要 MIME：`application/json`（主要）+ HTML/JS（靜態資源備用）
- Spring Boot 內建 Tomcat `GzipOutputInterceptor` 處理；無需額外 filter bean

**Trim / Defer:** 無 — 規模 XS，一個 diff 即完整。

---

## §3 Acceptance Criteria

**AC-1 — JSON 回應含 Content-Encoding: gzip**
```
Given: 客戶端送 GET /api/v1/skills，Header 含 Accept-Encoding: gzip
When:  回應 body ≥ 2 KB（集合回應通常 >> 2 KB）
Then:  回應 Header 含 Content-Encoding: gzip
```

**AC-2 — 小回應不壓縮**
```
Given: 回應 body < 2 KB（如空集合 [] 或單一小物件）
When:  客戶端送 Accept-Encoding: gzip
Then:  回應 Header 不含 Content-Encoding（Tomcat 不壓縮小回應）
```

**AC-3 — 未送 Accept-Encoding 的客戶端不受影響**
```
Given: 客戶端送 GET /api/v1/skills，無 Accept-Encoding header
When:  server 回應
Then:  回應 Header 不含 Content-Encoding；body 原文傳送
```

---

## §4 File Plan

| File | Action |
|------|--------|
| `backend/src/main/resources/application.yaml` | Add `server.compression` block |

---

## §5 Test Plan

- Smoke test：`curl -s -I -H "Accept-Encoding: gzip" http://localhost:8080/api/v1/skills` → 確認 `Content-Encoding: gzip`
- Unit test：不需要（pure YAML 配置；Tomcat 壓縮邏輯由 Spring Boot 負責測試）
- 若有現有 integration test 呼叫 `/api/v1/skills`，確認 build 不因新 header 失敗（MockMvc 預設不送 Accept-Encoding，不受影響）
