/**
 * 技能生命週期狀態。
 * - DRAFT：草稿，尚未對外發佈
 * - PUBLISHED：已上架，可供搜尋與下載
 */
export type SkillStatus = 'DRAFT' | 'PUBLISHED'

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
