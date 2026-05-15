# S181-T02: Deploy and collect LAB evidence

## 對應規格
S181：Authenticated State Conflict Observability

## 這個 task 要做什麼
把已提交的 S181 T01 build 成 Cloud Run image 並部署到 LAB。部署後用 Chrome 開正式站 validate URL，讓 `/api/v1/me` 或 `/api/v1/skills/{id}` 再次觸發 409，然後用 Cloud Run logs 取得 `State conflict` application log 內容，確認 log 真的帶出 path、method、exception class/message、root cause class/message。

## 使用者情境（BDD）
Given（前提）Cloud Run `skillshub` 已部署包含 commit `88eeb16` 的 image

When（動作）Chrome 使用既有登入 session 開啟 `https://skillshub-644359853825.asia-east1.run.app/publish/validate?id=028cecf1-3326-4327-bbe9-28b4e6fab6d5`

Then（結果）如果頁面或 API 仍回 HTTP 409，Cloud Run application log 會出現 `State conflict`

And（而且）該 log 包含 request `path`、`method`、`exceptionClass`、`message`、`rootCauseClass`、`rootCauseMessage`

And（而且）該 log 不包含 `Authorization`、cookie、session id、email、display name、picture URL、OAuth subject

## 研究來源
- `docs/grimo/specs/2026-05-15-S181-authenticated-state-conflict-observability.md`
- `scripts/gcp/BUILD.md`
- `scripts/gcp/04-deploy.sh`
- `docs/grimo/specs/2026-05-15-S180-skill-public-native-readback.md` 的 deploy evidence 格式

## 先做 POC
- POC：not required — 這個 task 是已驗證 code 的 deploy + production evidence 收集，不引入新 API 或新 framework behavior。

## 正式程式怎麼做
- Cloud Build source：從 `git HEAD` 建立乾淨 `/private/tmp` snapshot，避免上傳本機 unrelated `spec-roadmap.md` / S179 草稿。
- Image tag：`20260515-190626`
- Deploy：用 `scripts/gcp/04-deploy.sh` 渲染 Cloud Run service yaml 並 `gcloud run services replace`。
- Evidence：
  - Cloud Build id/result/image
  - Cloud Run revision/startup log
  - Chrome validate URL page text / console / failed requests
  - `gcloud logging read` 查 `State conflict`

## 單元測試 / 整合測試
- 無新增 JUnit。T01 已用 `GlobalExceptionHandlerTest` 驗證 local behavior。
- 本 task 驗證指令與手動步驟：
  - `gcloud builds submit --config=cloudbuild.yaml --project=cfh-vibe-lab --substitutions=_REGION=asia-east1,_TAG=20260515-190626`
  - `TAG=20260515-190626 ./scripts/gcp/04-deploy.sh`
  - Chrome 開 validate URL
  - `gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="skillshub" AND textPayload:"State conflict" AND timestamp>="<deploy timestamp>"' --project=cfh-vibe-lab --limit=20`

## 會改哪些檔案
- `docs/grimo/specs/2026-05-15-S181-authenticated-state-conflict-observability.md`
- `docs/grimo/tasks/2026-05-15-S181-T02-deploy-log-evidence.md`

## 驗證方式
執行：本 task 是 LAB 手動驗證；以 Cloud Build / Cloud Run / Chrome / Cloud Logging evidence 記錄在 task Result 與 spec §7。

## 前置條件
- S181-T01 PASS

## Result

### Build

PASS

```text
gcloud builds submit --config=cloudbuild.yaml --project=cfh-vibe-lab --substitutions=_REGION=asia-east1,_TAG=20260515-190626
ID b968943e-4f63-41db-aa8c-cee68fdaa963
IMAGE asia-east1-docker.pkg.dev/cfh-vibe-lab/skillshub/skillshub:20260515-190626
STATUS SUCCESS
```

### Deploy

PASS after switching to the prompt-defined manifest.

```text
gcloud run services replace temp/service.rendered.yaml --region=asia-east1 --project=cfh-vibe-lab --quiet
New revision: skillshub-00030-rd2
Revision status: Ready
Startup probe: /actuator/health/readiness succeeded after 1 attempt
```

First deploy attempt note:

- `scripts/gcp/04-deploy.sh` produced failed revision `skillshub-00029-2kc`.
- Failure log: `Provider ID must be specified for client registration 'skillshub'`.
- Diff from serving config: generated repo manifest lacked production OAuth client env, `spring.config.additional-location`, DB env, Secret Manager `/config` volume, Direct VPC egress annotations, and Cloud SQL proxy `--private-ip`.
- Recovery: update only the app image in `temp/service.rendered.yaml`, then run `gcloud run services replace temp/service.rendered.yaml`.

### Chrome + Cloud Run evidence

Target URL:

```text
https://skillshub-644359853825.asia-east1.run.app/publish/validate?id=028cecf1-3326-4327-bbe9-28b4e6fab6d5
```

Observed in Chrome after S181 deploy:

- Before clicking `登入`: page showed `登入` and `無法載入 skill`.
- Before clicking `登入`: Cloud Run returned 401 for `/api/v1/me`, skill detail, and bundle-info.
- After clicking `登入`: page returned to the validate URL and no longer showed `登入`.
- After authenticated reload: page still showed `無法載入 skill`.

Observed in Cloud Run logs on `skillshub-00030-rd2`:

```text
2026-05-15T19:22:17Z GET /api/v1/me 200
2026-05-15T19:22:17Z GET /api/v1/skills/028cecf1-3326-4327-bbe9-28b4e6fab6d5 403
2026-05-15T19:22:17Z GET /api/v1/skills/028cecf1-3326-4327-bbe9-28b4e6fab6d5/bundle-info 403
2026-05-15T19:22:17Z UserUpsertService: user refreshed userId=u_5e0652 provider=google
```

State conflict query:

```text
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="skillshub" AND resource.labels.revision_name="skillshub-00030-rd2" AND textPayload:"State conflict" AND timestamp>="2026-05-15T19:22:00Z"' --project=cfh-vibe-lab --limit=20
[]
```

Interpretation:

- S181 observability code is deployed and ready.
- The original authenticated 409 did not reproduce after login; `/api/v1/me` now returns 200.
- The remaining validate failure is authenticated 403 on skill detail and bundle-info.
- Next work unit should debug ACL/visibility for skill `028cecf1-3326-4327-bbe9-28b4e6fab6d5`, starting from user `u_5e0652`.

## Status
PASS
