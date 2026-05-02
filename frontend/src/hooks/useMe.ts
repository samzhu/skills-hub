import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '../api/client'

/**
 * S094a — fetch current user identity from backend `/api/v1/me`.
 *
 * Backend response shape (per S027 LAB security path)：
 * `{ sub: 'lab-user', roles: ['admin'], groups: [], companyId: null, deptId: null, scope: '' }`
 *
 * `sub` 是 author identity，用於 `/skills?author={sub}` filter 拉自己的 skills。
 */
export interface CurrentUser {
  sub: string
  roles: string[]
  groups: string[]
  companyId: string | null
  deptId: string | null
  scope: string
}

export function useMe() {
  return useQuery<CurrentUser>({
    queryKey: ['me'],
    queryFn: () => apiFetch('/me'),
    // identity 在 session 內穩定，cache 5min；refetch on window focus 不必要
    staleTime: 5 * 60 * 1000,
    refetchOnWindowFocus: false,
  })
}
