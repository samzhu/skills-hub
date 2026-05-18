import { apiFetch, ApiError } from './client'

/** S135b — 品質評分單一維度的分數與解說。 */
export interface DimensionScore {
  score: number      // validation: 0-100; implementation/activation: 0-3
  reasoning: string
}

/** S201 — dimension value can be a score object or warning messages from validation. */
export type DimensionValue = DimensionScore | string[]

/** 某一評估軸（VALIDATION / IMPLEMENTATION / ACTIVATION）的結果。 */
export interface AxisScore {
  totalScore: number // 0-100
  dimensions: Record<string, DimensionValue>
}

/** GET /api/v1/skills/{id}/scores 的回應 shape（S135a shipped；S142b 加 skillScore）。 */
export interface SkillScores {
  skillId: string
  skillVersionId: string
  skillVersion: string
  evaluatedAt: string
  evaluatorVersion: string
  validation: AxisScore
  implementation: AxisScore
  activation: AxisScore
  total: number      // 0-100，weighted: 0.2V + 0.4I + 0.4A
  /** S142b — 複合分數 0.6×quality + 0.4×security；security 未掃描時為 null */
  skillScore: number | null
}

/**
 * S135b — 取得技能品質評分。
 * 404 QUALITY_NOT_EVALUATED → return null（尚未評分，屬正常狀態）。
 * 其他錯誤 → rethrow 讓 React Query 處理重試。
 */
export async function fetchSkillScores(skillId: string): Promise<SkillScores | null> {
  try {
    return await apiFetch<SkillScores>(`/skills/${skillId}/scores`)
  } catch (err) {
    if (ApiError.is(err) && err.status === 404) return null
    throw err
  }
}
