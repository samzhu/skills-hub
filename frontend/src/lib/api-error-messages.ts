import { ApiError } from '@/api/client'

/**
 * S040 / S092 — 後端 ErrorResponse `error` code → 繁中訊息映射。
 *
 * 對齊 CLAUDE.md「UI 語言: 繁體中文」原則：mutation 與 query path 顯示給 user 的錯誤訊息
 * 應為繁中；後端 message 原文為英文（per `qa-strategy.md`「API 錯誤訊息: 英文（給前端轉譯用）」）。
 *
 * S092：對 field-level 錯誤（VALIDATION_ERROR / CONSTRAINT_VIOLATION）把 backend 具體
 * detail（如「Field 'name' fails regex ^[a-z0-9-]{1,64}$ (got: BAD)」）concat 到繁中
 * prefix 後面，讓 user 直接看到違規欄位 — 取代原本 generic「請確認格式正確」造成的
 * 定位困難。其他 code 仍用 fixed 模板（DUPLICATE/STATE 等沒可帶的 detail）。
 *
 * 變更注意：PAYLOAD_TOO_LARGE 模板含「10 MB」需與 backend `application.yaml`
 * `spring.servlet.multipart.max-file-size` 同步；變更 yaml 上限時需修此處。
 */
const ERROR_MESSAGE_BUILDER: Record<string, (backend?: string) => string> = {
  PAYLOAD_TOO_LARGE: () => '檔案過大，請選擇 10 MB 以內的 zip 套件。',
  VALIDATION_ERROR: (m) =>
    m && m.trim().length > 0 ? `驗證失敗：${m}` : '驗證失敗，請確認資料格式正確。',
  MULTIPART_ERROR: () => '上傳請求格式不正確，請重新整理頁面後重試。',
  INVALID_REQUEST_BODY: () => '請求內容缺失或格式錯誤，請重試。',
  VERSION_EXISTS: () => '此版本號已存在，請改用其他版本號。',
  STATE_CONFLICT: () => '操作與目前狀態衝突，請重新整理後重試。',
  DUPLICATE_RESOURCE: () => '此名稱已被使用，請換一個名稱。',
  CONSTRAINT_VIOLATION: (m) =>
    m && m.trim().length > 0 ? `資料驗證失敗：${m}` : '提交資料超過允許的長度或格式，請檢查後重試。',
  METHOD_NOT_ALLOWED: () => '此操作的請求方法不正確，請重新整理頁面後再試。',
  CONCURRENT_MODIFICATION: () => '資源被其他請求同時修改，請重試。',
  NOT_FOUND: () => '找不到指定的資源。',
  FORBIDDEN: () => '沒有權限執行此操作。',
  SKILL_SUSPENDED: () => '此技能已被停用，無法操作。',
}

/**
 * 把 mutation/query throw 的 error 翻譯為繁中顯示字串。
 *
 * 規則：
 * - {@link ApiError} 含已知 `code` → 用 `ERROR_MESSAGE_BUILDER`，動態帶入 backend message
 *   讓 field-level 錯誤暴露具體 detail
 * - 其他 Error → 直接 fallback 至 `error.message`（保留 backend 詳情或網路錯誤訊息）
 * - 非 Error → 「未知錯誤」
 */
export function localizeApiError(err: unknown): string {
  // S065: ApiError.is name-based check — HMR 安全
  if (ApiError.is(err) && err.code && ERROR_MESSAGE_BUILDER[err.code]) {
    return ERROR_MESSAGE_BUILDER[err.code](err.message)
  }
  if (err instanceof Error) return err.message
  return '未知錯誤'
}
