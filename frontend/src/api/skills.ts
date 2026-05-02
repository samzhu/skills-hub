import { apiFetch, ApiError } from './client'
import type { Skill, SpringPage, CategoryCount, SkillVersion } from '../types/skill'

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
   * - 'recommended'  → 不傳 sort（後端 default = createdAt DESC，與 size 內隱排序對齊）
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
  if (params.sort && params.sort !== 'recommended') {
    const sortMap = {
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

/**
 * S096c — 依 (author, name) canonical route 取得 Skill (per ADR-003).
 * `/skills/:author/:name` 為 v2 canonical；`/skills/:id` 仍為永久 alias。
 */
export function fetchSkillByAuthorAndName(author: string, name: string): Promise<Skill> {
  return apiFetch(`/skills/${encodeURIComponent(author)}/${encodeURIComponent(name)}`)
}

/**
 * S096g1 — Request Board: skill needs that the community wants published.
 * Stub backend returns empty list; voting/claim/domain events defer to S096g2.
 */
export interface SkillRequest {
  id: string
  title: string
  description: string
  votes: number
  status: 'OPEN' | 'IN_PROGRESS' | 'FULFILLED'
  createdAt: string
}

export function fetchRequests(): Promise<SkillRequest[]> {
  return apiFetch<SkillRequest[]>('/requests')
}

/**
 * S096f1 — Collections: curated skill bundles for one-click install.
 * Stub backend returns empty list; install/create/single endpoints defer to S096f2.
 */
export interface SkillCollection {
  id: string
  name: string
  description: string
  skillCount: number
  installs: number
  category: string
  createdAt: string
}

export function fetchCollections(): Promise<SkillCollection[]> {
  return apiFetch<SkillCollection[]>('/collections')
}

/**
 * S096h1 — Notification list + unread count (per PRD §P9 + Handoff §2.17).
 * Stub backend returns []; real projection from domain_events 留 S096h2.
 */
export interface Notification {
  id: string
  category: 'versions' | 'flags' | 'reviews' | 'requests'
  title: string
  body: string
  skillId: string | null
  read: boolean
  createdAt: string
}

export function fetchNotifications(): Promise<Notification[]> {
  return apiFetch<Notification[]>('/notifications')
}

export function fetchUnreadCount(): Promise<{ count: number }> {
  return apiFetch<{ count: number }>('/notifications/unread-count')
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
 * @param file     技能 zip 套件
 * @param version  語意化版本號（SemVer，如 1.0.0）
 * @param author   作者名稱
 * @param category 技能分類
 * @returns 後端分配的技能 UUID
 */
export async function uploadSkill(file: File, version: string, author: string, category: string): Promise<{ id: string }> {
  const form = new FormData()
  form.append('file', file)
  form.append('version', version)
  form.append('author', author)
  form.append('category', category)
  const res = await fetch('/api/v1/skills/upload', { method: 'POST', body: form })
  if (!res.ok) {
    // S040: 與 apiFetch 對齊 — 拋 ApiError 攜 status + code，讓 caller 可走 i18n 翻譯
    const body = await res.json().catch(() => ({}))
    const b = body as { message?: string; error?: string }
    throw new ApiError(res.status, b.message ?? `Upload failed: ${res.status}`, b.error)
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
 * @param version 新版本的語意化版本號
 */
export async function addVersion(skillId: string, file: File, version: string): Promise<void> {
  const form = new FormData()
  form.append('file', file)
  form.append('version', version)
  const res = await fetch(`/api/v1/skills/${skillId}/versions`, { method: 'PUT', body: form })
  if (!res.ok) {
    // S040: 與 apiFetch 對齊 — 拋 ApiError 攜 status + code
    const body = await res.json().catch(() => ({}))
    const b = body as { message?: string; error?: string }
    throw new ApiError(res.status, b.message ?? `Version upload failed: ${res.status}`, b.error)
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
