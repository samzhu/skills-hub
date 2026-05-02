import { Link } from 'react-router'
import { Download } from 'lucide-react'
import { RiskBadge } from './RiskBadge'
import { IconTile } from './IconTile'
import type { Skill } from '@/types/skill'

/**
 * S085: 重寫對齊 prototype `skills_hub_homepage_mockup.html` `.sh-card` 結構。
 *
 * - hairline border（0.5px #E0DDD3）+ 14px padding + lg(12px) radius，no shadow
 * - top row：IconTile（category-tinted 30px）+ name (14px medium) + author (12px tertiary) + RiskBadge pill
 * - description (13px secondary, 2-line clamp)
 * - foot row：category badge + version mono pill + download stat + （semantic 模式）相符度 badge
 *
 * 整張卡片 `<Link>` 包覆，整片 hover bg 變淺。
 *
 * @param skill 技能讀取模型（或語意搜尋結果）
 * @param score 語意相似度（0.0–1.0）；語意搜尋模式下顯示「XX% 相符」badge
 */
export function SkillCard({ skill, score }: { skill: Skill; score?: number }) {
  return (
    <Link to={`/skills/${skill.id}`} className="block group">
      <article className="relative overflow-hidden rounded-lg border border-border bg-card p-[14px] transition-colors group-hover:bg-muted/30">
        {/* top row：icon tile + name/author + risk pill */}
        <div className="mb-2 flex items-start justify-between gap-2.5">
          <div className="flex min-w-0 items-center gap-2">
            <IconTile name={skill.name} category={skill.category} size="md" />
            <div className="min-w-0">
              <h3 className="m-0 truncate text-sm font-medium">{skill.name}</h3>
              <p className="mt-px text-xs text-muted-foreground">{skill.author}</p>
            </div>
          </div>
          <RiskBadge level={skill.riskLevel} />
        </div>

        {/* description：13px secondary, 2-line clamp */}
        <p className="mb-3 text-[13px] leading-[1.5] text-muted-foreground line-clamp-2">
          {skill.description}
        </p>

        {/* foot row */}
        <div className="flex items-center justify-between text-xs text-muted-foreground">
          <div className="flex items-center gap-3">
            <span className="rounded-full bg-secondary px-2 py-0.5 text-[11px] text-foreground">{skill.category}</span>
            {/* S028: DRAFT/SUSPENDED 顯示 status pill；S060 truthy guard。 */}
            {skill.status && skill.status !== 'PUBLISHED' && (
              <span
                className={`rounded-full px-2 py-0.5 text-[11px] font-medium ${
                  skill.status === 'SUSPENDED'
                    ? 'bg-[#FCEBEB] text-[#791F1F]'
                    : 'bg-[#FAEEDA] text-[#633806]'
                }`}
              >
                {skill.status === 'SUSPENDED' ? '已停用' : '草稿'}
              </span>
            )}
          </div>
          <div className="flex items-center gap-3">
            {skill.latestVersion && (
              <span className="rounded font-mono text-[11px] bg-secondary text-foreground/80 px-1.5 py-0.5">
                v{skill.latestVersion}
              </span>
            )}
            <span className="flex items-center gap-1">
              <Download className="h-3 w-3" />
              {skill.downloadCount}
            </span>
            {/* 語意搜尋模式才顯示相符度 badge */}
            {score !== undefined && (
              <span className="rounded-full bg-[#EAF3DE] px-2 py-0.5 text-[11px] font-medium text-[#27500A]">
                {(score * 100).toFixed(0)}% 相符
              </span>
            )}
          </div>
        </div>
      </article>
    </Link>
  )
}
