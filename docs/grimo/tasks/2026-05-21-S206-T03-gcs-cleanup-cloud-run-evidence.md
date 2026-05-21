# S206-T03: GCS cleanup and Cloud Run deploy evidence

## 對應規格
S206：Cloud Build Source Upload Pruning

## 這個 task 要做什麼
這個 task 完成後，舊的 Cloud Build source tarballs 會從 `gs://cfh-vibe-lab_cloudbuild/source/**` 清掉。接著要用裁剪後的 source upload 重新跑一次 Cloud Build，並把同一個 image tag 部署到 Cloud Run。部署完成後，要確認 latest ready revision 是新 image、`/actuator/health` 回 200，而且新 revision 部署後的 `severity>=ERROR` log 是 0 rows。

## 使用者情境（BDD）
Given（前提）S206-T01 的 `.gcloudignore` 已生效，`gcloud meta list-files-for-upload .` 不再列出 local cache / generated / secret paths  
When（動作）刪除 `gs://cfh-vibe-lab_cloudbuild/source/**` live objects，重新送 Cloud Build，並把同一個 image tag 部署到 Cloud Run  
Then（結果）`source/` object count 從目前 118 變成 0  
And（而且）Cloud Build result 是 SUCCESS，submit 開頭顯示 source file count ≤ 1000、source archive size ≤ 10 MiB  
And（而且）Cloud Run service replace 成功，latest ready revision 指向新部署的 image  
And（而且）新 revision 的 `/actuator/health` 回 HTTP 200  
And（而且）新 revision 部署後時間窗的 Cloud Run `severity>=ERROR` logs 是 0 rows

## 研究來源
- `docs/grimo/specs/2026-05-21-S206-cloud-build-source-upload-pruning.md`
- `cloudbuild.yaml`
- `scripts/gcp/04-deploy.sh`
- `scripts/gcp/service.yaml`
- `gcloud storage rm --help`

## 先做 POC
- POC：not required — 這個 task 的驗證就是實際 GCS cleanup、Cloud Build submit、Cloud Run deploy 和 health/log evidence。

## 正式程式怎麼做
- Class / file 名稱：N/A
- 入口：GCP resources + existing deploy tooling
- 必要行為：
  - 刪除 `gs://cfh-vibe-lab_cloudbuild/source/**` live objects，不刪 logs bucket，不刪 Artifact Registry image。
  - 刪除前後都記錄 object count。刪後 count 必須是 0。
  - 用既有 Cloud Build manual submit path 重新 build。不要把本機環境變數 export 指令或完整 submit command 寫進 spec。
  - 部署同一個 image tag 到 Cloud Run。不要把完整 deploy command 寫進 spec。
  - 記錄 build id、image tag、Cloud Build result、source file count / size、Cloud Run latest ready revision、health HTTP status、ERROR log count。
  - 查 Cloud Run logs 時，限制新 revision 和部署後時間窗，避免舊 noisy log 混進 S206 evidence。
- Finding / response / DB 欄位：
  - `sourceObjectCountBefore`: 刪除前 `source/` object count。
  - `sourceObjectCountAfter`: 刪除後 `source/` object count，預期 0。
  - `buildId`: Cloud Build build id。
  - `imageTag`: 本輪 build/deploy 共用 image tag。
  - `latestReadyRevision`: Cloud Run latest ready revision。
  - `healthStatus`: `/actuator/health` HTTP status，預期 200。
  - `errorLogCount`: 新 revision 部署後 `severity>=ERROR` log rows，預期 0。

## 單元測試 / 整合測試
- No JUnit/Vitest file — 這是 GCP integration evidence。
- Evidence 要寫回 S206 §7：
  - `AC-S206-5`: GCS source objects count after cleanup = 0。
  - `AC-S206-6`: Cloud Build SUCCESS、deploy success、latest ready revision = new image、health 200、new revision ERROR log count = 0。

## 會改哪些檔案
- `docs/grimo/specs/2026-05-21-S206-cloud-build-source-upload-pruning.md`（§7 evidence only，實作階段新增）
- GCS objects `gs://cfh-vibe-lab_cloudbuild/source/**`（delete）

## 驗證方式
執行：

```bash
gcloud storage ls gs://cfh-vibe-lab_cloudbuild/source/ --project=cfh-vibe-lab | wc -l
gcloud storage rm 'gs://cfh-vibe-lab_cloudbuild/source/**' --project=cfh-vibe-lab
gcloud storage ls gs://cfh-vibe-lab_cloudbuild/source/ --project=cfh-vibe-lab | wc -l
```

接著用既有 Cloud Build manual submit path 和既有 Cloud Run deploy path 完成 build/deploy。S206 §7 只能記錄 evidence 摘要，不記錄完整本機 submit/deploy command。

## 前置條件
- S206-T01 PASS
- S206-T02 PASS

## 狀態
pending（待做）

