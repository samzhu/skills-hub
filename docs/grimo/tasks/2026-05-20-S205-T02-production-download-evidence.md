# S205-T02: Production download evidence

## 對應規格
S205：Download Filename UTF-8 Content-Disposition

## 這個 task 要做什麼
新 revision 部署後，要用正式站 API 證明 `OAuth 專家` 下載 response header 已有 `filename*`，而且同 revision log 不再出現 Tomcat header encoding error。這個 task 不改下載程式；它補 AC-S205-5 的真站 evidence。

## 使用者情境（BDD）
Given（前提）S205 修正已部署到 Cloud Run 新 revision
When（動作）執行正式站 curl：

```bash
curl -sS -D - -o /tmp/oauth-expert.zip \
  https://skillshub-644359853825.asia-east1.run.app/api/v1/skills/c80ca4cc-9ceb-4586-85bc-c0187d49fab3/download
```

Then（結果）response header 有 `content-disposition`
And（而且）header 包含 `filename*=UTF-8''OAuth%20%E5%B0%88%E5%AE%B6-1.zip`
And（而且）同 revision Cloud Run log 不再出現 `UnmappableCharacterException` 或 `MessageBytes.toBytes`

## 研究來源
- `docs/grimo/specs/2026-05-20-S205-download-filename-utf8-content-disposition.md` AC-S205-5
- Cloud Run log 現場症狀：download request 期間 Tomcat header encoding error
- S202 production E2E fixture policy：正式 artifact / 正式站 evidence 要和 local unit evidence 分開記錄

## 先做 POC
- POC：not required — 這是 deploy 後 evidence capture；local behavior 已由 S205-T01 測試。

## 正式程式怎麼做
- Class / file 名稱：不改 production code。
- 入口：正式站 `GET /api/v1/skills/{id}/download`。
- 必要行為：
  - 若尚未 deploy，先在 spec §7 Pending verification 記錄 curl/log 命令，不把本 task 假標 PASS。
  - deploy 後跑 curl，保存 response header 重點到 spec §7。
  - 查同 revision Cloud Run log，確認沒有 `UnmappableCharacterException` / `MessageBytes.toBytes`。

## 單元測試 / 整合測試
- 無新增 test class。
- Evidence 寫回 `docs/grimo/specs/2026-05-20-S205-download-filename-utf8-content-disposition.md` §7。

## 會改哪些檔案
- `docs/grimo/specs/2026-05-20-S205-download-filename-utf8-content-disposition.md`

## 驗證方式
執行：

```bash
curl -sS -D - -o /tmp/oauth-expert.zip \
  https://skillshub-644359853825.asia-east1.run.app/api/v1/skills/c80ca4cc-9ceb-4586-85bc-c0187d49fab3/download
```

再查 Cloud Run 同 revision log，確認沒有 header encoding error。

## 前置條件
- S205-T01 PASS
- 新 revision 已部署到 Cloud Run

## 狀態
DEFERRED（post-release：待 S205 新 revision 部署到 Cloud Run 後補 evidence；本 dev loop 不部署、不測正式站）

## Result（2026-05-21）

本 tick 未執行正式站 curl，也未查 Cloud Run log，原因是 `.codex/loop.dev.md` 和 automation prompt 明確禁止 production deploy / production site inspection。

已改由 `docs/grimo/specs/2026-05-20-S205-download-filename-utf8-content-disposition.md` §7.4 記錄 post-release verification 命令、預期 header、以及要排除的 log error：

- `curl -sS -D - -o /tmp/oauth-expert.zip https://skillshub-644359853825.asia-east1.run.app/api/v1/skills/c80ca4cc-9ceb-4586-85bc-c0187d49fab3/download`
- response header 要包含 `filename*=UTF-8''OAuth%20%E5%B0%88%E5%AE%B6-1.zip`
- 同 revision Cloud Run log 不可出現 `UnmappableCharacterException` 或 `MessageBytes.toBytes`

AC-S205-5 不標 PASS；它是 deploy 後補證據。AC-S205-1~4 的 local header contract 已由 T01 和 spec §7.1 驗證。
