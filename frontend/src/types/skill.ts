/**
 * 技能生命週期狀態（對齊 backend `SkillStatus` enum；S018 三狀態機）。
 * - DRAFT：草稿，尚未對外發佈
 * - PUBLISHED：已上架，可供搜尋與下載
 * - SUSPENDED：已停用，因安全風險或違規而下架，不可下載（S028 frontend sync）
 */
export type SkillStatus = 'DRAFT' | 'PUBLISHED' | 'SUSPENDED'

/**
 * 風險評估等級（由後端 RiskScanner 分析 scripts/ 目錄後設定）。
 * - LOW：僅含 SKILL.md，無可執行腳本
 * - MEDIUM：含腳本但未偵測到高風險模式
 * - HIGH：偵測到高風險模式，應謹慎使用
 */
export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH'

/**
 * 技能讀取模型，對應後端 SkillReadModel 的 JSON 序列化結果。
 */
export interface Skill {
  /** 技能唯一識別碼 (UUID) */
  id: string
  /** 技能名稱 */
  name: string
  /** 技能功能描述 */
  description: string
  /** 作者名稱 */
  author: string
  /** 技能分類（如 DevOps、AI、Testing） */
  category: string
  /** 最新版本號（SemVer），尚未發佈任何版本時為 null */
  latestVersion: string | null
  /** 風險評估結果；尚未評估時為 null */
  riskLevel: RiskLevel | null
  /** 技能生命週期狀態 */
  status: SkillStatus
  /** 累計下載次數 */
  downloadCount: number
  /** 建立時間（ISO 8601 UTC） */
  createdAt: string
  /** 最後更新時間（ISO 8601 UTC） */
  updatedAt: string
}

/**
 * Spring Data 分頁回應的通用包裝型別。
 * `page.number` 為 0-indexed，符合 Spring Data Pageable 慣例。
 *
 * @template T 分頁內容的元素型別
 */
export interface SpringPage<T> {
  content: T[]
  page: {
    /** 目前頁碼（0-indexed） */
    number: number
    /** 每頁筆數 */
    size: number
    /** 資料總筆數 */
    totalElements: number
    /** 總頁數 */
    totalPages: number
  }
}

/**
 * 技能分類及其技能數量，用於側邊欄篩選。
 * 後端保證每個技能只屬於一個分類，因此 count 加總等於技能總數。
 */
export interface CategoryCount {
  /** 分類名稱 */
  name: string
  /** 該分類下的技能數量 */
  count: number
}

/**
 * 語意搜尋結果 DTO，對應後端 SemanticSearchResult 的 JSON 序列化結果。
 *
 * 由 GET /api/v1/search/semantic?q=... 回傳，每筆記錄包含技能摘要與語意相似度分數。
 * score 為 cosine similarity（0.0–1.0），越高表示與查詢語意越相近。
 */
export interface SemanticSearchResult {
  /** 技能唯一識別碼 (UUID) */
  id: string
  /** 技能名稱 */
  name: string
  /** 技能功能描述 */
  description: string
  /** 作者名稱 */
  author: string
  /** 技能分類 */
  category: string
  /** 最新版本號；尚未發佈任何版本時為 null */
  latestVersion: string | null
  /** 風險評估等級；尚未評估時為 null */
  riskLevel: RiskLevel | null
  /** 累計下載次數 */
  downloadCount: number
  /** 與查詢的語意相似度（0.0–1.0） */
  score: number
}

/**
 * 技能版本讀取模型，對應後端 SkillVersionReadModel 的序列化結果。
 * 版本清單由 API 依 publishedAt 降冪排序，第一筆為最新版。
 */
export interface SkillVersion {
  /** 版本記錄唯一識別碼（UUID） */
  id: string
  /** 所屬技能的聚合根識別碼 */
  skillId: string
  /** 語意化版本號（SemVer，如 1.0.0） */
  version: string
  /** 套件在 GCS 中的完整物件路徑（前端僅用於組合下載 URL） */
  storagePath: string
  /** 套件的位元組大小（bytes） */
  fileSize: number
  /** 版本發布時間（ISO 8601 UTC） */
  publishedAt: string
}
