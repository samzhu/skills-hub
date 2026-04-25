import { SkillCard } from './SkillCard'
import type { Skill } from '@/types/skill'

/**
 * 技能卡片網格：以兩欄響應式格線排列所有 `SkillCard`。
 *
 * 當 skills 陣列為空時顯示空狀態提示，引導使用者調整搜尋條件。
 *
 * @param skills 要顯示的技能列表
 */
export function SkillCardGrid({ skills }: { skills: Skill[] }) {
  if (skills.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-muted-foreground">
        <p className="text-lg font-medium">找不到符合的技能</p>
        <p className="text-sm">試試不同的關鍵字或分類</p>
      </div>
    )
  }

  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
      {skills.map((skill) => (
        <SkillCard key={skill.id} skill={skill} />
      ))}
    </div>
  )
}
