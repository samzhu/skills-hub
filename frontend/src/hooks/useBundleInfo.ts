import { useQuery } from '@tanstack/react-query'
import { fetchBundleInfo, type BundleInfo } from '@/api/skills'

/**
 * S098a3-2 — Bundle metadata for PublishValidatePage upload-strip。
 *
 * `enabled: !!id` 對齊 useSkill / useRequest / useCollection canonical — undefined id
 * 不 fetch。Cache 60s（bundle 是 immutable per (skill, version)；refresh 無實際效益）；
 * `retry: false` 因 404 為 expected 路徑（既有 row file_count=0 仍 200，僅 skill_not_found
 * / bundle_not_published 走 404；caller 對 404 走 fallback derived placeholder）。
 */
export function useBundleInfo(id: string | undefined) {
  return useQuery<BundleInfo>({
    queryKey: ['bundle-info', id],
    queryFn: () => fetchBundleInfo(id!),
    enabled: !!id,
    staleTime: 60 * 1000,
    retry: false,
  })
}
