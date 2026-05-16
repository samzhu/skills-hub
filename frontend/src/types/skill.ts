/**
 * 技能生命週期狀態（對齊 backend `SkillStatus` enum；S018 三狀態機）。
 * - DRAFT：草稿，尚未對外發佈
 * - PUBLISHED：已上架，可供搜尋與下載
 * - SUSPENDED：已停用，因安全風險或違規而下架，不可下載（S028 frontend sync）
 */
export type SkillStatus = 'DRAFT' | 'PUBLISHED' | 'SUSPENDED'

/** 技能公開狀態。公開讀取由後端 `skills.is_public` 判斷，不是 ACL 字串。 */
export type Visibility = 'PUBLIC' | 'PRIVATE'

/**
 * 風險評估等級（由後端 RiskScanner 分析後設定）。
 * S096c: 4-tier per ADR-future + PRD D27.
 * - NONE：pure docs skill — 0 findings + 無 scripts/ + 無 allowed-tools
 * - LOW：0 findings 但有 capability declaration；OR findings 全 LOW severity
 * - MEDIUM：含腳本但未偵測到高風險模式
 * - HIGH：偵測到高風險模式，應謹慎使用
 */
export type RiskLevel = 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH'

/** S098b3-2 — 一個結構化驗證 finding，對應 PublishFailedPage ErrRow UI。 */
export interface ValidationFinding {
  section: string
  severity: 'error' | 'warning'
  title: string
  hint: string | null
}

export interface ViewerPermissions {
  isOwner: boolean
  canView: boolean
  canDownload: boolean
  canEdit: boolean
  canDelete: boolean
  canShare: boolean
  canManageGrants: boolean
}

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
  /** S154 — platform user_id (`u_<6hex>`)；對應 backend `Skill.author` 欄位。**不直接顯給 user**，走 `getDisplayName(skill)` 取人類可讀名。 */
  author: string
  /** S154b — backend live-join 算出的 author display name（5-layer fallback）；nullable 表示 backend 也找不到（user 已刪等 edge case）。 */
  authorDisplayName?: string | null
  /** S154b — author handle slug（user-facing）；用於 install command + `/users/{handle}` 等 URL；nullable 表示 backend 沒對應 user row。 */
  authorHandle?: string | null
  /** S154b — author email；**僅在** `contact_email_public=true` 時 backend 回此值，否則 backend 端 strip 為 null。前端 conditional `<mailto:>` button 用。 */
  authorEmail?: string | null
  /** 技能分類 canonical 值（lowercase；V20 起 DB 一律 lowercase 存）— 用於 search/filter key。 */
  category: string
  /** S159b Round 2 — 分類 display 名稱（保留原 CamelCase 如 "DevOps"）；舊資料可能 null，UI 用 `categoryLabel(skill)` 取（fallback `capitalize(category)`）。 */
  categoryDisplay?: string | null
  /** 最新版本號（SemVer），尚未發佈任何版本時為 null */
  latestVersion: string | null
  /** 風險評估結果；尚未評估時為 null */
  riskLevel: RiskLevel | null
  /** 技能生命週期狀態 */
  status: SkillStatus
  /** S184 — PageHeader visibility button 的狀態來源。 */
  visibility: Visibility
  /** 累計下載次數 */
  downloadCount: number
  /** S098e2-T02：review 平均星等（1.00-5.00；尚無 review 時為 0.00） */
  averageRating: number
  /** S098e2-T02：review 總數（尚無 review 時為 0） */
  reviewCount: number
  /** S114a — 技能擁有者的 userId（對應 ACL owner grant principal）。 */
  ownerId?: string
  /** S169 — 後端以目前 viewer 視角算好的 detail action 權限。 */
  viewerPermissions?: ViewerPermissions | null
  /** 建立時間（ISO 8601 UTC） */
  createdAt: string
  /** 最後更新時間（ISO 8601 UTC） */
  updatedAt: string
  /** S142b — 已驗證旗標：status=PUBLISHED && riskLevel != null（backend 計算） */
  verified: boolean
  /** S142b — 最新版本的發布時間（ISO 8601 UTC）；無版本時為 null */
  latestVersionPublishedAt: string | null
  /** S142b — 授權協定（from latest version frontmatter）；未填時為 null */
  license: string | null
  /** S142b — 相容性標籤列表（from latest version frontmatter）；未填時為空陣列 */
  compatibility: string[]
  /** S142b — 版本總數 */
  versionCount: number
  /** S142b — 目前開放的 flag 回報數 */
  openFlagCount: number
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
  /** 技能分類 canonical lowercase（V20 起 lowercase）— search/filter key */
  category: string
  /** S159b Round 2 — 分類 display 名稱保留原 CamelCase；舊資料 null，UI 用 `categoryLabel(...)` fallback */
  categoryDisplay?: string | null
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
  /** 套件的位元組大小（bytes） */
  fileSize: number
  /**
   * S098a3-2/S117 — 套件內檔案數量（zip entries 計算）。
   * 0 表示資料庫尚未填入該欄位（pre-S098a3-2 上傳之歷史 row 為 fallback signal）。
   */
  fileCount: number
  /** 版本發布時間（ISO 8601 UTC） */
  publishedAt: string
}
