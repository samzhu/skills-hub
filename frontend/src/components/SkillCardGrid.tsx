import { SkillCard } from './SkillCard'
import { EmptyState } from './EmptyState'
import type { Skill } from '@/types/skill'

/**
 * 技能卡片網格：以兩欄響應式格線排列所有 `SkillCard`。
 *
 * 當 skills 陣列為空時，由 caller 透過 `query` 區分 tone：
 * - 沒帶 query（純瀏覽 0 筆 / 真正空殼）→ seed tone「邀請第一筆 skill」
 * - 帶 query（搜尋 0 筆）→ redirect tone「導向其他路徑」
 *
 * S094c：取代既有 generic 「找不到符合的技能 / 試試不同的關鍵字或分類」。
 *
 * @param skills 要顯示的技能列表
 * @param query  使用者搜尋字串（非空表示使用者主動搜尋過；用於 0-results tone 區分）
 * @param onClearQuery 清除搜尋字串並回到全部技能列表
 */
export function SkillCardGrid({ skills, query, onClearQuery }: { skills: Skill[]; query?: string; onClearQuery?: () => void }) {
  if (skills.length === 0) {
    if (query && query.trim().length > 0) {
      return (
        <EmptyState
          tone="redirect"
          query={query}
          headline="找不到符合的技能"
          sub="搜尋詞可能太特殊，或詞彙與技能描述不一致。試試以下其他路徑。"
          suggestions={[
            { text: '清除關鍵字並瀏覽全部技能', hint: '取消當前過濾', onClick: onClearQuery },
            { text: '發布你自己的技能', hint: '可能你的團隊也需要這個', href: '/publish' },
          ]}
        />
      )
    }
    return (
      <EmptyState
        tone="seed"
        eyebrow="尚未有任何技能"
        headline="技能庫等著被開啟。"
        sub="第一個發布的人定下基調 — 之後的人就跟著走。Skills Hub 隨團隊累積愈用愈值錢。"
        primaryAction={{ label: '發布第一個技能', href: '/publish' }}
      />
    )
  }

  return (
    // S098d: 3-col grid at xl breakpoint per prototype `Skills Hub Homepage.html`
    // `.skill-grid {grid-template-columns:repeat(3, 1fr)}`. 既有 sm:2-col 在 ≤ xl 仍 fallback。
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
      {skills.map((skill) => (
        <SkillCard key={skill.id} skill={skill} />
      ))}
    </div>
  )
}
