import { ApiError } from '@/api/client'

/**
 * S040 — 後端 ErrorResponse `error` code → 繁中訊息映射。
 *
 * 對齊 CLAUDE.md「UI 語言: 繁體中文」原則：mutation 與 query path 顯示給 user 的錯誤訊息
 * 應為繁中；後端 message 原文為英文（per `qa-strategy.md`「API 錯誤訊息: 英文（給前端轉譯用）」）。
 *
 * 變更注意：PAYLOAD_TOO_LARGE 模板含「10 MB」需與 backend `application.yaml`
 * `spring.servlet.multipart.max-file-size` 同步；變更 yaml 上限時需修此處。
 */
const ERROR_MESSAGES: Record<string, string> = {
  PAYLOAD_TOO_LARGE: '檔案過大，請選擇 10 MB 以內的 zip 套件。',
  VALIDATION_ERROR: 'zip 套件驗證失敗，請確認格式正確。',
  MULTIPART_ERROR: '上傳請求格式不正確，請重新整理頁面後重試。',
  INVALID_REQUEST_BODY: '請求內容缺失或格式錯誤，請重試。',
  VERSION_EXISTS: '此版本號已存在，請改用其他版本號。',
  STATE_CONFLICT: '操作與目前狀態衝突，請重新整理後重試。',
  DUPLICATE_RESOURCE: '此名稱已被使用，請換一個名稱。',
  CONCURRENT_MODIFICATION: '資源被其他請求同時修改，請重試。',
  NOT_FOUND: '找不到指定的資源。',
  SKILL_SUSPENDED: '此技能已被停用，無法操作。',
}

/**
 * 把 mutation/query throw 的 error 翻譯為繁中顯示字串。
 *
 * 規則：
 * - {@link ApiError} 含已知 `code` → 用 `ERROR_MESSAGES` 模板
 * - 其他 Error → 直接 fallback 至 `error.message`（保留 backend 詳情或網路錯誤訊息）
 * - 非 Error → 「未知錯誤」
 */
export function localizeApiError(err: unknown): string {
  if (err instanceof ApiError && err.code && ERROR_MESSAGES[err.code]) {
    return ERROR_MESSAGES[err.code]
  }
  if (err instanceof Error) return err.message
  return '未知錯誤'
}
