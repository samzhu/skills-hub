import type { Skill } from '@/types/skill'
import { getDisplayName } from '@/lib/displayName'
import type { SkillScores } from '@/api/scores'
import type { SecurityReport } from '@/api/security'
import { IconTile } from '@/components/IconTile'
import { RiskBadge } from '@/components/RiskBadge'
import { BeamFrame } from '@/components/BeamFrame'
import { AuthGatedButton } from '@/components/AuthGatedButton'
import {
  useIsSubscribed,
  useSubscribeSkill,
  useUnsubscribeSkill,
} from '@/hooks/useSubscription'
import { HeroMetricsRow } from './HeroMetricsRow'
import { StatStrip } from './StatStrip'

interface Props {
  skill: Skill
  isOwner: boolean
  activeTab: string
  onTabChange: (tab: string) => void
  scores: SkillScores | null | undefined
  report: SecurityReport | null | undefined
  stats: number[]
  onDownload?: () => void
  onShareClick?: () => void
  /** S163b — owner-only [編輯] button click handler；undefined 時不 render button。 */
  onEditClick?: () => void
}

/** Star (subscribe) toggle button — lucide Star icon, uses subscription hooks. */
function StarButton({ skillId, isOwner }: { skillId: string; isOwner: boolean }) {
  const subscribed = useIsSubscribed(skillId)
  const subscribe = useSubscribeSkill()
  const unsubscribe = useUnsubscribeSkill()

  if (isOwner) return null

  return (
    <AuthGatedButton
      data-testid="star-btn"
      title={subscribed ? '取消訂閱' : '訂閱'}
      onClick={() => {
        if (subscribed) unsubscribe.mutate(skillId)
        else subscribe.mutate(skillId)
      }}
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 6,
        padding: '7px 12px',
        fontSize: 13,
        background: 'rgba(255,255,255,0.03)',
        border: '0.5px solid var(--line-2, rgba(255,255,255,0.12))',
        borderRadius: 8,
        cursor: 'pointer',
        color: subscribed ? '#EF9F27' : 'var(--ink-2, rgba(238,236,234,0.7))',
      }}
    >
      <svg width="14" height="14" viewBox="0 0 24 24" fill={subscribed ? '#EF9F27' : 'none'}
        stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
        <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
      </svg>
      {subscribed ? '已訂閱' : '訂閱'}
    </AuthGatedButton>
  )
}

/** Verified pill — blue background + green checkmark icon + "Verified" text. */
function VerifiedPill() {
  return (
    <span
      data-testid="verified-pill"
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 4,
        fontSize: 10,
        fontWeight: 500,
        padding: '2px 9px',
        borderRadius: 999,
        background: 'rgba(55,138,221,0.14)',
        color: '#B0D5F2',
      }}
    >
      <svg width="9" height="9" viewBox="0 0 10 10" fill="none" aria-hidden="true">
        <path d="M2 5l2 2 4-4" stroke="#6FD8B0" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      已驗證
    </span>
  )
}

function relativeTime(iso: string): string {
  const days = Math.floor((Date.now() - new Date(iso).getTime()) / 86400000)
  if (days === 0) return '今天'
  if (days < 30) return `${days} 天前`
  return `${Math.floor(days / 30)} 個月前`
}

/**
 * S142a — SkillDetailPage v2 always-visible page header.
 * Includes SkillInfo / Actions / HeroMetricsRow / StatStrip.
 * Tab-bar and body grid are rendered by the parent page.
 */
export function PageHeader({ skill, isOwner, activeTab, onTabChange, scores, report, stats, onDownload, onShareClick, onEditClick }: Props) {
  const skillScore = scores?.skillScore ?? null

  return (
    <div
      data-testid="page-header"
      style={{
        padding: '22px 26px 0',
        borderBottom: '0.5px solid var(--line, rgba(255,255,255,0.08))',
        background: 'radial-gradient(ellipse 600px 220px at 60% 0%, rgba(127,119,221,.09), transparent 70%)',
      }}
    >
      {/* Top row */}
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 20, marginBottom: 16 }}>
        {/* Left: skill info */}
        <div style={{ display: 'flex', gap: 14 }}>
          <IconTile name={skill.name} category={skill.category} size="xl" />
          <div>
            {/* Name row */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginBottom: 4 }}>
              <span style={{ fontSize: 22, fontWeight: 500 }}>{skill.name}</span>
              {skill.latestVersion && (
                <span style={{ fontSize: 11, fontFamily: 'monospace', padding: '1px 6px', borderRadius: 5, background: 'var(--bg-3, rgba(255,255,255,0.06))', border: '0.5px solid var(--line-2, rgba(255,255,255,0.12))' }}>
                  v{skill.latestVersion}
                </span>
              )}
              {skill.riskLevel && (
                <RiskBadge level={skill.riskLevel} />
              )}
              {skill.verified && <VerifiedPill />}
            </div>
            {/* Description */}
            <p style={{ fontSize: 14, color: 'var(--ink-2, rgba(238,236,234,0.7))', lineHeight: 1.55, marginBottom: 8, display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
              {skill.description}
            </p>
            {/* Meta row */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>
              <span>作者：{getDisplayName(skill)}</span>
              {/* S154b — 聯絡作者 mailto link；只在作者 contact_email_public=true 時 backend
                  enrichAuthorIdentity expose authorEmail（隱私 opt-in）。empty → 不 render，
                  避免「按鈕點下去開空白 mailto」誤導。 */}
              {skill.authorEmail && (
                <>
                  <span>·</span>
                  <a
                    href={`mailto:${skill.authorEmail}`}
                    style={{ color: 'var(--ink-3, rgba(238,236,234,0.4))', textDecoration: 'underline' }}
                  >
                    聯絡作者
                  </a>
                </>
              )}
              {skill.license && <><span>·</span><span>{skill.license}</span></>}
              <span>·</span>
              <span>更新於 {relativeTime(skill.updatedAt)}</span>
              <span>·</span>
              <span>{skill.category}</span>
            </div>
          </div>
        </div>

        {/* Right: actions */}
        <div style={{ display: 'flex', gap: 8, flexShrink: 0, alignItems: 'flex-start' }}>
          {isOwner && onEditClick && (
            <button
              type="button"
              aria-label="編輯技能"
              data-testid="edit-skill-btn"
              onClick={onEditClick}
              style={{ padding: '8px 14px', fontSize: 13, background: 'rgba(255,255,255,0.06)', border: '0.5px solid var(--line-2, rgba(255,255,255,0.12))', borderRadius: 8, cursor: 'pointer' }}
            >
              編輯
            </button>
          )}
          {isOwner && onShareClick && (
            <button
              type="button"
              aria-label="分享"
              onClick={onShareClick}
              style={{ padding: '8px 14px', fontSize: 13, background: 'rgba(255,255,255,0.06)', border: '0.5px solid var(--line-2, rgba(255,255,255,0.12))', borderRadius: 8, cursor: 'pointer' }}
            >
              分享
            </button>
          )}
          <StarButton skillId={skill.id} isOwner={isOwner} />
          {skill.status === 'PUBLISHED' && (
            <BeamFrame>
              <button
                data-testid="download-cta"
                onClick={onDownload}
                style={{
                  padding: '8px 16px',
                  fontSize: 13,
                  fontWeight: 500,
                  background: '#EEECEA',
                  color: '#08080A',
                  border: 'none',
                  borderRadius: 8,
                  cursor: 'pointer',
                }}
              >
                下載技能
              </button>
            </BeamFrame>
          )}
        </div>
      </div>

      {/* Hero metrics + stat strip */}
      <HeroMetricsRow
        skillScore={skillScore}
        scores={scores}
        report={report}
        activeTab={activeTab}
        onTabChange={onTabChange}
      />
      <StatStrip skill={skill} stats={stats} />
    </div>
  )
}
