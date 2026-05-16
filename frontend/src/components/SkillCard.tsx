import { Link } from 'react-router'
import { Download } from 'lucide-react'
import { RiskBadge } from './RiskBadge'
import { IconTile } from './IconTile'
import { BeamFrame } from './BeamFrame'
import { getDisplayName } from '@/lib/displayName'
import { categoryLabel } from '@/lib/text'
import type { Skill } from '@/types/skill'

/**
 * S085 → S096d2 — SkillCard polish per prototype `Skills Hub Homepage.html` `.sc`：
 * - bg-card (#0F0F12) + 0.5px hairline border + 16px (xl) radius + 14px padding
 * - top row: IconTile (32px md) + name 13px medium + author mono 11px + RiskBadge
 * - description 12.5px ink-2 clamp-2
 * - foot row: category pill / status pill (DRAFT/SUSPENDED) / version mono pill / downloads / similarity %
 * - **featured** variant (top-match in semantic search): wrap in BeamFrame for visual emphasis
 *
 * Per Engineering Handoff §8 BorderBeam usage rules：1 beam per page。featured
 * 限用於 top-match (search results 第一個 result)。
 *
 * @param skill 技能讀取模型（或語意搜尋結果）
 * @param score 語意相似度（0.0–1.0）；語意搜尋模式下顯示「XX% 相符」badge
 * @param featured 是否為 top-match — wrap in BeamFrame; /browse semantic mode 為第一個 result 啟用
 */
export function SkillCard({ skill, score, featured }: { skill: Skill; score?: number; featured?: boolean }) {
  const inner = (
    <Link to={`/skills/${skill.id}`} className="block group">
      <article
        className={
          featured
            ? 'relative overflow-hidden rounded-xl bg-card p-[14px] h-full'
            : 'relative overflow-hidden rounded-xl border border-border bg-card p-[14px] transition-colors group-hover:border-[rgba(255,255,255,0.10)]'
        }
      >
        {/* top row：icon tile + name/author + risk pill */}
        <div className="mb-2 flex items-start justify-between gap-2.5">
          <div className="flex min-w-0 items-center gap-2">
            <IconTile name={skill.name} category={skill.category} size="md" />
            <div className="min-w-0">
              <h3 className="m-0 truncate text-[13px] font-medium">{skill.name}</h3>
              <p className="mt-px font-mono text-[11px] text-muted-foreground">{getDisplayName(skill)}</p>
            </div>
          </div>
          <RiskBadge level={skill.riskLevel} />
        </div>

        {/* description：12.5px ink-2, 2-line clamp */}
        <p className="mb-3 text-[12.5px] leading-[1.55] text-muted-foreground line-clamp-2">
          {skill.description}
        </p>

        {/* foot row */}
        <div className="flex items-center justify-between text-[11px] text-muted-foreground">
          <div className="flex items-center gap-3">
            <span className="rounded-full bg-secondary px-2 py-0.5 text-[11px] text-foreground">{categoryLabel(skill)}</span>
            {/* S028: DRAFT/SUSPENDED 顯示 status pill；S060 truthy guard。 */}
            {skill.status && skill.status !== 'PUBLISHED' && (
              <span
                className="rounded-full px-2 py-0.5 text-[11px] font-medium"
                style={
                  skill.status === 'SUSPENDED'
                    ? { backgroundColor: 'rgba(226,75,74,0.14)', color: '#F2A6A6' }
                    : { backgroundColor: 'rgba(239,159,39,0.14)', color: '#FAC775' }
                }
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
              <span
                className="rounded-full px-2 py-0.5 text-[11px] font-medium"
                style={{ backgroundColor: 'rgba(29,158,117,0.14)', color: '#9FE1CB' }}
              >
                {(score * 100).toFixed(0)}% 相符
              </span>
            )}
          </div>
        </div>
      </article>
    </Link>
  )

  // featured wraps in BeamFrame (top-match in semantic search) per Engineering Handoff §8
  return featured ? <BeamFrame>{inner}</BeamFrame> : inner
}
