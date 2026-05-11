import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  fetchMySubscriptionDetails,
  fetchMySubscriptions,
  subscribeSkill,
  unsubscribeSkill,
} from '@/api/subscriptions'
import type { SubscriptionSummary } from '@/api/subscriptions'

/**
 * S125c — 當前 user 訂閱的所有 skillId list（用於 SkillDetail subscribe button state）。
 *
 * Cache 30s 對齊 useNotifications 既驗 staleTime；窗口聚焦時 refetch（user 切回 tab
 * 即時反映訂閱狀態變更，例如另一頁 unsubscribe）。
 *
 * Public skills 既允許 anonymous 觀看，本 hook 在 anonymous user (LAB mode) 也應該
 * 可正常 fetch — backend `/me/subscriptions` 走 currentUserProvider.userId() 對 fallback
 * lab-user 仍可回對應訂閱清單（即 lab-user subscriber rows，若有）。
 */
export function useMySubscriptions() {
  return useQuery<string[]>({
    queryKey: ['my-subscriptions'],
    queryFn: fetchMySubscriptions,
    staleTime: 30 * 1000,
    refetchOnWindowFocus: true,
  })
}

/** S145 — 當前 user 訂閱管理列表的 skill summary rows。 */
export function useMySubscriptionDetails() {
  return useQuery<SubscriptionSummary[]>({
    queryKey: ['my-subscriptions', 'details'],
    queryFn: fetchMySubscriptionDetails,
    staleTime: 30 * 1000,
    refetchOnWindowFocus: true,
  })
}

/**
 * 當前 user 是否已訂閱指定 skill — derived from useMySubscriptions list contains。
 *
 * 不另開 endpoint 走 GET /skills/{id}/subscribe-status：
 * - useMySubscriptions 一次 fetch 已足以回答多 skill 的 isSubscribed
 * - 多 page 共用同 cache 不重 fetch
 * - SkillDetail page 同時 useMySubscriptions + 比對 skillId 取得 boolean
 */
export function useIsSubscribed(skillId: string): boolean {
  const { data } = useMySubscriptions()
  return data?.includes(skillId) ?? false
}

/**
 * Subscribe 指定 skill。Mutation 完 invalidate `['my-subscriptions']` cache + bell badge
 * `['notifications-unread']`（new version 通知陸續會來）+ 對應 skill 的 detail page 不需
 * invalidate（subscription 不影響 skill aggregate fields）。
 */
export function useSubscribeSkill() {
  const qc = useQueryClient()
  return useMutation<void, Error, string>({
    mutationFn: (skillId) => subscribeSkill(skillId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['my-subscriptions'] })
      qc.invalidateQueries({ queryKey: ['my-subscriptions', 'details'] })
    },
  })
}

/** Unsubscribe 指定 skill — mirror useSubscribeSkill 反向。 */
export function useUnsubscribeSkill() {
  const qc = useQueryClient()
  return useMutation<void, Error, string>({
    mutationFn: (skillId) => unsubscribeSkill(skillId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['my-subscriptions'] })
      qc.invalidateQueries({ queryKey: ['my-subscriptions', 'details'] })
    },
  })
}
