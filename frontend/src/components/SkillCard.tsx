import { Link } from 'react-router'
import { Download } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { RiskBadge } from './RiskBadge'
import type { Skill } from '@/types/skill'

/**
 * 技能列表卡片元件：在技能網格中呈現單一技能的摘要資訊。
 *
 * 整張卡片包覆於 `<Link>` 中，使整個點擊區域皆可跳轉至詳情頁。
 * `line-clamp-2` 限制描述文字最多顯示兩行，超出部分以省略號截斷。
 *
 * @param skill 技能讀取模型
 */
export function SkillCard({ skill }: { skill: Skill }) {
  return (
    // 以 <Link> 包覆整個卡片，讓整個可點擊區域皆可導航至詳情頁
    <Link to={`/skills/${skill.id}`} className="block">
      <Card className="transition-shadow hover:shadow-md">
        <CardHeader>
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <CardTitle className="truncate text-base">{skill.name}</CardTitle>
              <p className="text-xs text-muted-foreground">{skill.author}</p>
            </div>
            <RiskBadge level={skill.riskLevel} />
          </div>
        </CardHeader>
        <CardContent>
          {/* line-clamp-2：Tailwind 的 -webkit-line-clamp 工具類，超出兩行以 ... 截斷 */}
          <CardDescription className="line-clamp-2 text-sm">
            {skill.description}
          </CardDescription>
          <div className="mt-3 flex items-center gap-3 text-xs text-muted-foreground">
            <Badge variant="secondary" className="text-xs">{skill.category}</Badge>
            {skill.latestVersion && (
              <span>v{skill.latestVersion}</span>
            )}
            <span className="flex items-center gap-1">
              <Download className="h-3 w-3" />
              {skill.downloadCount}
            </span>
          </div>
        </CardContent>
      </Card>
    </Link>
  )
}
