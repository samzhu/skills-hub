import { apiFetch } from './client'

/**
 * S114a — Skill ACL grants API client。
 *
 * 對齊後端 `SkillGrantController` endpoints：
 * - `GET /api/v1/skills/{id}/grants` — 列出所有 grants
 * - `POST /api/v1/skills/{id}/grants` — 新增 grant
 * - `DELETE /api/v1/skills/{id}/grants/{grantId}` — 撤銷 grant
 */
export interface SkillGrant {
  id: string
  principalType: 'user' | 'group' | 'company' | 'public'
  principalId: string
  role: 'OWNER' | 'VIEWER'
  grantedBy: string
  grantedAt: string
}

export interface CreateGrantRequest {
  principalType: SkillGrant['principalType']
  principalId: string
  /** MVP: 只開放 VIEWER（OWNER one-per-skill 約束） */
  role: 'VIEWER'
}

export function fetchGrants(skillId: string): Promise<SkillGrant[]> {
  return apiFetch<SkillGrant[]>(`/skills/${skillId}/grants`)
}

export function createGrant(
  skillId: string,
  body: CreateGrantRequest,
): Promise<{ grantId: string }> {
  return apiFetch<{ grantId: string }>(`/skills/${skillId}/grants`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function revokeGrant(skillId: string, grantId: string): Promise<void> {
  return apiFetch<void>(`/skills/${skillId}/grants/${grantId}`, { method: 'DELETE' })
}
