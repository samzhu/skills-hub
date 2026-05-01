import { useQuery } from '@tanstack/react-query'
import { fetchSkillFile, fetchSkillFiles } from '../api/skills'

/**
 * S082：列出 skill zip 內所有 entries 的 React Query hook（接 S074 `GET /files`）。
 *
 * 快取鍵 `['skills', skillId, 'files']` 與既有 `['skills', skillId]` 同根 — 切換 skill 時
 * 整批 invalidate；上傳新版本時亦應 invalidate（latest version 變動 → 內容可能不同）。
 */
export function useSkillFiles(skillId: string) {
  return useQuery({
    queryKey: ['skills', skillId, 'files'],
    queryFn: () => fetchSkillFiles(skillId),
    // skillId 為空字串時停用，與 useSkill / useVersions 一致
    enabled: !!skillId,
  })
}

/**
 * S082：讀取單一 entry 內容（接 S074 `GET /files/{*path}`）。
 *
 * 快取以 (skillId, path) 為 key — 切檔不重複 fetch。Blob + contentType 一起回，
 * caller (FilesPanel) 自行決定如何渲染（text decode / binary fallback / 大檔錯誤）。
 *
 * @param skillId 技能 ID（空字串停用）
 * @param path    zip entry path；空字串停用（user 還沒選檔）
 */
export function useSkillFile(skillId: string, path: string) {
  return useQuery({
    queryKey: ['skills', skillId, 'files', path],
    queryFn: () => fetchSkillFile(skillId, path),
    enabled: !!skillId && !!path,
  })
}
