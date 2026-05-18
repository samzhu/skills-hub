import { apiFetch, apiFetchVoid, ApiError } from './client'
import type { Skill, SpringPage, CategoryCount, SkillVersion, Visibility, ValidationFinding } from '../types/skill'

/**
 * 技能搜尋參數。所有欄位皆為可選；
 * 未傳入時後端使用預設值（page=0, size=20，無關鍵字/分類過濾）。
 */
export interface SkillSearchParams {
  /** 關鍵字，比對技能名稱與描述（後端使用 $regex，全表掃描） */
  keyword?: string
  /** 分類名稱精確比對 */
  category?: string
  /** S094a: 作者名稱精確比對 (case-insensitive)；帶值時 backend 跳過 PUBLISHED filter，
   * 讓作者看到自己的 DRAFT/SUSPENDED；不帶則維持公開查詢只露 PUBLISHED */
  author?: string
  /** 頁碼（0-indexed），預設 0 */
  page?: number
  /** 每頁筆數，預設 20 */
  size?: number
  /**
   * S100b: server-side sort。Spring Pageable 接收 `sort=field,direction` query param。
   * 白名單對齊 backend SkillQueryService.SORTABLE_PROPERTIES：
   * - 'recommended'  → downloadCount,desc（S106 對齊 design intent；同 most-downloaded
   *                     mapping 但 UX chip distinct，future 改 recommendation algorithm
   *                     時直接改 mapping，UI 結構不變）
   * - 'newest'       → createdAt,desc
   * - 'most-downloaded' → downloadCount,desc
   * - 'risk-low'     → riskLevel,asc (NONE→LOW→MEDIUM→HIGH 字典序)
   */
  sort?: 'recommended' | 'newest' | 'most-downloaded' | 'risk-low'
}

/**
 * 以關鍵字 + 分類 + 作者搜尋技能，回傳分頁結果。
 *
 * @param params 搜尋條件（皆可選）
 * @returns Spring Data 分頁包裝的技能列表
 */
export function fetchSkills(params: SkillSearchParams): Promise<SpringPage<Skill>> {
  const search = new URLSearchParams()
  if (params.keyword) search.set('keyword', params.keyword)
  if (params.category) search.set('category', params.category)
  if (params.author) search.set('author', params.author)
  // page 使用 0-indexed（Spring Data Pageable 慣例），未傳入預設第 0 頁
  search.set('page', String(params.page ?? 0))
  search.set('size', String(params.size ?? 20))
  // S100b: server-side sort — frontend mode → Spring Pageable `sort=field,direction`
  // S106: 'recommended' explicit mapped (避免 fall-through 到 backend default 與 'newest'
  // 行為重複；UX 4 chip 各自 distinct param，future evolve recommendation algorithm 時
  // 改 mapping 即可)。
  if (params.sort) {
    const sortMap = {
      recommended: 'downloadCount,desc',
      newest: 'createdAt,desc',
      'most-downloaded': 'downloadCount,desc',
      'risk-low': 'riskLevel,asc',
    } as const
    search.set('sort', sortMap[params.sort])
  }
  return apiFetch(`/skills?${search}`)
}

/**
 * 依 ID 取得單一技能的完整資訊。
 *
 * @param id 技能的 UUID
 * @returns 技能讀取模型
 */
export function fetchSkillById(id: string): Promise<Skill> {
  return apiFetch(`/skills/${id}`)
}

export function deleteSkill(id: string): Promise<void> {
  return apiFetchVoid(`/skills/${id}`, { method: 'DELETE' })
}

/** S187 — PUT /api/v1/skills/{id} 只改 category；description 由新版 SKILL.md 更新。 */
export function updateSkill(
  id: string,
  body: { category?: string | null }
): Promise<void> {
  return apiFetchVoid(`/skills/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export interface SkillVisibilityResponse {
  skillId: string
  visibility: Visibility
  updatedAt: string
}

export function setSkillVisibility(
  id: string,
  visibility: Visibility,
): Promise<SkillVisibilityResponse> {
  return apiFetch<SkillVisibilityResponse>(`/skills/${id}/visibility`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ visibility }),
  })
}

/**
 * S176 — 依 (author, name) legacy alias 取得 Skill。
 * `/skills/:id` 是 canonical identity；重名後 author/name route 只回 deterministic latest row。
 */
export function fetchSkillByAuthorAndName(author: string, name: string): Promise<Skill> {
  return apiFetch(`/skills/${encodeURIComponent(author)}/${encodeURIComponent(name)}`)
}

/**
 * S098a3-2 — Bundle metadata for PublishValidatePage upload-strip。
 *
 * `filename` 走 backend canonical derive `<name>-<version>.zip`；`fileCount=0` 表 legacy
 * row（V13 migration default；frontend hide 該欄）。404 路徑：skill 不存在 (NOT_FOUND)
 * vs 無 published version (`bundle_not_published`)。
 */
export interface BundleInfo {
  filename: string
  fileSize: number
  fileCount: number
  uploadedAt: string
}

export function fetchBundleInfo(id: string): Promise<BundleInfo> {
  return apiFetch<BundleInfo>(`/skills/${id}/bundle-info`)
}

/**
 * S096g2 → S156c — Request Board read model（voting-board pivot 後對齊 backend
 * RequestQueryController.RequestResponse）。
 *
 * Vote count 由 backend `request_votes` 表 atomic SQL 維護（mirror Skill downloadCount
 * S077 pattern）；frontend 收到的 `voteCount` 為 strong-consistent 即時值，無需 client 累計。
 *
 * S156c 移除 `status` / `claimerId` / `fulfilledSkillId` 三 field — claim/fulfill 機制已拆。
 */
export interface SkillRequest {
  id: string
  title: string
  description: string
  requesterId: string
  voteCount: number
  createdAt: string
  updatedAt: string
}

/**
 * S156c — Request list 支援 sort param（`?status=` 已隨 voting-board pivot 移除）。
 * Backend：`GET /api/v1/requests?sort=votes|created`；optional。
 */
export interface RequestsQuery {
  sort?: 'votes' | 'created'
}

export function fetchRequests(opts?: RequestsQuery): Promise<SkillRequest[]> {
  const params = new URLSearchParams()
  if (opts?.sort) params.set('sort', opts.sort)
  const qs = params.toString()
  return apiFetch<SkillRequest[]>(qs ? `/requests?${qs}` : '/requests')
}

/**
 * S156c — single Request detail response（GET /requests/{id}）含 comments + canDelete。
 *
 * `canDelete` 由 backend 計算：requester 視角 true、非 requester / 未登入 false。
 * Frontend 只讀，不重複實作 ownership 邏輯。
 */
export interface RequestComment {
  id: string
  /** S192: platform user_id；delete ownership comparison 用，不是人類 label。 */
  authorId: string
  /** S192: comment row 的人類可讀 author label。 */
  authorDisplayName?: string | null
  /** S192: comment author handle，可作人類可讀 fallback。 */
  authorHandle?: string | null
  content: string
  createdAt: string
}

export interface RequestDetail extends SkillRequest {
  comments: RequestComment[]
  canDelete: boolean
}

export function fetchRequest(id: string): Promise<RequestDetail> {
  return apiFetch<RequestDetail>(`/requests/${id}`)
}

export interface CommentBody {
  content: string
}

export function postComment(requestId: string, body: CommentBody): Promise<{ id: string }> {
  return apiFetch<{ id: string }>(`/requests/${requestId}/comments`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function deleteComment(requestId: string, commentId: string): Promise<void> {
  return apiFetchVoid(`/requests/${requestId}/comments/${commentId}`, { method: 'DELETE' })
}

export interface CreateRequestBody {
  title: string
  description: string
}

export function createRequest(body: CreateRequestBody): Promise<{ id: string }> {
  return apiFetch<{ id: string }>('/requests', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

/**
 * Vote toggle response — backend `RequestVoteService.VoteResult`：
 * `voted=true` 表示本次 toggle 後該 user 持有 vote；false 表示已 cancel。
 * `voteCount` 為 atomic GREATEST(0, count±1) 後值，可直接當 UI 顯示。
 */
export interface VoteResult {
  voted: boolean
  voteCount: number
}

export function toggleVote(requestId: string): Promise<VoteResult> {
  return apiFetch<VoteResult>(`/requests/${requestId}/vote`, { method: 'POST' })
}

export function deleteRequest(requestId: string): Promise<void> {
  return apiFetchVoid(`/requests/${requestId}`, { method: 'DELETE' })
}

/**
 * S096f1 / S096f2 — Collections: curated skill bundles for one-click install.
 *
 * S096f2-T03: list/single/install/create endpoints upgraded from stub。`description`
 * 改 nullable 對齊 backend `String description nullable`（Spring Data JDBC TEXT column）。
 */
export interface SkillCollection {
  id: string
  name: string
  description: string | null
  skillCount: number
  // S118: rename installs → installCount 對齊 CollectionDetail.installCount
  // (per Mode B Round 36 Bug AQ fix — 同 entity 跨 endpoint field name 一致性)
  installCount: number
  category: string
  createdAt: string
  /** S096f3: 集合內所有 skill 的最高風險等級；null = 尚未掃描或空集合 */
  maxRiskLevel: import('../types/skill').RiskLevel | null
}

/** S096f2 — single collection detail（GET /collections/{id}）含 skills summary。 */
export interface CollectionSkillSummary {
  id: string
  name: string
  category: string
  riskLevel: string | null
  latestVersion: string | null
}

export interface CollectionDetail {
  id: string
  name: string
  description: string | null
  category: string
  ownerId: string
  installCount: number
  createdAt: string
  skills: CollectionSkillSummary[]
}

/** S096f2 — POST /collections body shape；對齊 backend CreateCollectionBody record。 */
export interface CreateCollectionRequest {
  name: string
  description: string | null
  category: string
  skillIds: string[]
}

export function fetchCollections(category?: string): Promise<SkillCollection[]> {
  const qs = category ? `?category=${encodeURIComponent(category)}` : ''
  return apiFetch<SkillCollection[]>(`/collections${qs}`)
}

export function fetchCollection(id: string): Promise<CollectionDetail> {
  return apiFetch<CollectionDetail>(`/collections/${id}`)
}

/**
 * S164 — PUT /api/v1/collections/{id} 改 name / description / category / skillIds（整段覆蓋）。
 * 非 owner → 403；不存在 → 404；skillIds 含非 PUBLISHED → 400。
 */
export function updateCollection(
  id: string,
  body: CreateCollectionRequest,
): Promise<void> {
  return apiFetchVoid(`/collections/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

/** S164 — DELETE /api/v1/collections/{id}。非 owner → 403；不存在 → 404；204 success。 */
export function deleteCollection(id: string): Promise<void> {
  return apiFetchVoid(`/collections/${id}`, { method: 'DELETE' })
}

export function createCollection(body: CreateCollectionRequest): Promise<{ id: string }> {
  return apiFetch<{ id: string }>('/collections', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function installCollection(id: string): Promise<{ downloadUrls: string[] }> {
  return apiFetch<{ downloadUrls: string[] }>(`/collections/${id}/install`, { method: 'POST' })
}

/**
 * S096e1 — public Landing stats (per Engineering Handoff §2.1).
 * Aggregate-only payload；不需 auth。
 */
export interface PublicStats {
  totalSkills: number
  downloads30d: number
  activePublishers: number
  autoPublishPct: number
}

export function fetchPublicStats(): Promise<PublicStats> {
  return apiFetch<PublicStats>('/stats')
}

/**
 * S096d3 — per-skill 下載趨勢 (sparkline 資料源)。
 * 回傳長度 N 的整數陣列：index 0 = 最舊那天，index N-1 = 今天。
 * `period` 接受 `7d` | `30d` (default) | `90d`；其他 fallback 30d.
 */
export function fetchSkillStats(id: string, period: '7d' | '30d' | '90d' = '30d'): Promise<number[]> {
  return apiFetch<number[]>(`/skills/${id}/stats?period=${period}`)
}

/**
 * 取得所有技能分類及其數量，供側邊欄篩選使用。
 *
 * @returns 分類清單（後端依數量降冪排序）
 */
export function fetchCategories(): Promise<CategoryCount[]> {
  return apiFetch('/categories')
}

/**
 * 上傳新技能（multipart/form-data）。
 *
 * 注意：此函式刻意繞過 `apiFetch` 直接使用原生 `fetch`，
 * 因為 `FormData` body 必須讓瀏覽器自動設定 `Content-Type: multipart/form-data; boundary=...`，
 * 若透過 `apiFetch` 手動設定 header 會導致 boundary 遺失而解析失敗。
 *
 * @param file      技能 zip 套件
 * @param skillName 平台列表顯示用技能名稱
 * @param version   可選版本標籤；空白時後端自動產生 v1 / next number
 * @param category  技能分類
 * @returns 後端分配的技能 UUID
 */
/** S184 — Skill visibility is backed by skills.is_public, not public ACL strings. */
export type { Visibility } from '../types/skill'

// S154b — drop author parameter；backend §S154 §2.5 forge fix 後 server 一律從 auth context
// 取 author（caller body silent ignored），前端不再送以對齊 authoritative ownership semantics。
export async function uploadSkill(
  file: File,
  skillName: string,
  version: string | undefined,
  category: string,
  visibility: Visibility = 'PUBLIC',
): Promise<{ id: string }> {
  const form = new FormData()
  form.append('skillName', skillName)
  form.append('file', file)
  const trimmedVersion = version?.trim()
  if (trimmedVersion) form.append('version', trimmedVersion)
  form.append('category', category)
  form.append('visibility', visibility)
  const res = await fetch('/api/v1/skills/upload', { method: 'POST', body: form })
  if (!res.ok) {
    // S040: 與 apiFetch 對齊 — 拋 ApiError 攜 status + code，讓 caller 可走 i18n 翻譯
    const body = await res.json().catch(() => ({}))
    const b = body as { message?: string; error?: string; findings?: ValidationFinding[] }
    throw new ApiError(res.status, b.message ?? `Upload failed: ${res.status}`, b.error, b.findings)
  }
  return res.json() as Promise<{ id: string }>
}

/**
 * 為現有技能新增版本（multipart/form-data）。
 *
 * 同 `uploadSkill`，繞過 `apiFetch` 以避免 FormData boundary 問題。
 * 後端回應無 body（204 No Content），因此回傳 void。
 *
 * @param skillId 目標技能的 UUID
 * @param file    新版本的 zip 套件
 * @param version 可選版本標籤；空白時後端自動產生 next number
 */
export async function addVersion(skillId: string, file: File, version?: string): Promise<void> {
  const form = new FormData()
  form.append('file', file)
  const trimmedVersion = version?.trim()
  if (trimmedVersion) form.append('version', trimmedVersion)
  const res = await fetch(`/api/v1/skills/${skillId}/versions`, { method: 'PUT', body: form })
  if (!res.ok) {
    // S040: 與 apiFetch 對齊 — 拋 ApiError 攜 status + code
    const body = await res.json().catch(() => ({}))
    const b = body as { message?: string; error?: string; findings?: ValidationFinding[] }
    throw new ApiError(res.status, b.message ?? `Version upload failed: ${res.status}`, b.error, b.findings)
  }
}

/**
 * 取得某技能的所有版本，依發佈時間降冪排序（最新版在最前）。
 *
 * @param skillId 目標技能的 UUID
 * @returns 版本清單
 */
export function fetchVersions(skillId: string): Promise<SkillVersion[]> {
  return apiFetch(`/skills/${skillId}/versions`)
}

/** S098c2 — 結構化 version diff response（對齊 backend VersionDiffResponse）。 */
export interface VersionDiffResult {
  skillId: string
  from: VersionSnapshot
  to: VersionSnapshot
  fields: DiffField[]
}
export interface VersionSnapshot {
  version: string
  publishedAt: string
  fileSize: number
  fileCount: number
}
export interface DiffField {
  field: string
  fromValue: string | null
  toValue: string | null
  changeType: 'added' | 'removed' | 'changed'
}

export function fetchVersionDiff(skillId: string, from: string, to: string): Promise<VersionDiffResult> {
  return apiFetch(`/skills/${skillId}/diff?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`)
}

/** S098c3 — 兩版本 zip 包檔案列表差異（對齊 backend FileListDiffResponse）。 */
export interface FileListDiffResult {
  skillId: string
  fromVersion: string
  toVersion: string
  addedCount: number
  removedCount: number
  modifiedCount: number
  unchangedCount: number
  entries: FileDiffEntry[]
}
export interface FileDiffEntry {
  path: string
  changeType: 'added' | 'removed' | 'modified'
  fromSize: number | null
  toSize: number | null
}

export function fetchFileListDiff(skillId: string, from: string, to: string): Promise<FileListDiffResult> {
  return apiFetch(`/skills/${skillId}/file-list-diff?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`)
}

/**
 * S082：skill zip 內單一 entry metadata（對齊 S074 `FileEntryResponse` record）。
 *
 * @property path skill zip 內路徑（已過 zip-slip 防禦，不含 `..`/開頭 `/`）
 * @property size 解壓後位元組數
 * @property type 推測的 MIME（依副檔名；無法判別則 `application/octet-stream`）
 */
export interface SkillFile {
  path: string
  size: number
  type: string
}

/**
 * 列出 skill 最新 PUBLISHED 版本 zip 內所有 entries（S074 API）。
 * SUSPENDED → 403；DRAFT 無 PUBLISHED 版本 → 404。
 */
export function fetchSkillFiles(skillId: string): Promise<SkillFile[]> {
  return apiFetch(`/skills/${skillId}/files`)
}

/**
 * 讀取 skill zip 內單一 entry 內容（S074 API）。
 *
 * 走原生 fetch（非 apiFetch）— body 為任意 binary，apiFetch 預期 JSON 反序列化會失敗。
 * 1MB+ 檔案會被 backend 拒（413 PAYLOAD_TOO_LARGE per S074 cap）。
 *
 * @return Blob + Content-Type；caller 自行決定如何渲染（text decode / binary fallback）
 */
export async function fetchSkillFile(skillId: string, path: string): Promise<{ blob: Blob; contentType: string }> {
  const safePath = path.split('/').map(encodeURIComponent).join('/')
  const res = await fetch(`/api/v1/skills/${skillId}/files/${safePath}`)
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    const b = body as { message?: string; error?: string }
    throw new ApiError(res.status, b.message ?? `File fetch failed: ${res.status}`, b.error)
  }
  return {
    blob: await res.blob(),
    contentType: res.headers.get('Content-Type') ?? 'application/octet-stream',
  }
}
