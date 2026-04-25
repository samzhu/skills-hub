import { apiFetch } from './client'
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
  /** 頁碼（0-indexed），預設 0 */
  page?: number
  /** 每頁筆數，預設 20 */
  size?: number
}

/**
 * 以關鍵字 + 分類搜尋技能，回傳分頁結果。
 *
 * @param params 搜尋條件（皆可選）
 * @returns Spring Data 分頁包裝的技能列表
 */
export function fetchSkills(params: SkillSearchParams): Promise<SpringPage<Skill>> {
  const search = new URLSearchParams()
  if (params.keyword) search.set('keyword', params.keyword)
  if (params.category) search.set('category', params.category)
  // page 使用 0-indexed（Spring Data Pageable 慣例），未傳入預設第 0 頁
  search.set('page', String(params.page ?? 0))
  search.set('size', String(params.size ?? 20))
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
    const body = await res.json().catch(() => ({}))
    throw new Error((body as { message?: string }).message ?? `Upload failed: ${res.status}`)
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
    const body = await res.json().catch(() => ({}))
    throw new Error((body as { message?: string }).message ?? `Version upload failed: ${res.status}`)
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
