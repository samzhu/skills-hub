import { useMutation, useQueryClient } from '@tanstack/react-query'
import { setSkillVisibility } from '@/api/skills'
import { skillKeys } from '@/api/queryKeys'
import type { Skill, Visibility } from '@/types/skill'

/**
 * S184 — Owner-only public/private toggle. The button reads skill.visibility
 * from skill detail JSON and sends the target state; it never looks up public grant ids.
 */
export function VisibilityToggleButton({
  skillId,
  visibility,
}: {
  skillId: string
  visibility: Visibility
}) {
  const queryClient = useQueryClient()
  const isPublic = visibility === 'PUBLIC'
  const target: Visibility = isPublic ? 'PRIVATE' : 'PUBLIC'
  const label = isPublic ? '轉為私人' : '公開分享'

  const mutation = useMutation({
    mutationFn: () => setSkillVisibility(skillId, target),
    onSuccess: (response) => {
      queryClient.setQueryData<Skill | undefined>(skillKeys.detail(skillId), (old) =>
        old ? { ...old, visibility: response.visibility, updatedAt: response.updatedAt } : old,
      )
      queryClient.invalidateQueries({ queryKey: skillKeys.all })
      queryClient.invalidateQueries({ queryKey: skillKeys.grants(skillId) })
    },
  })

  return (
    <button
      type="button"
      aria-label={label}
      data-testid="visibility-toggle"
      disabled={mutation.isPending}
      onClick={() => mutation.mutate()}
      style={{
        padding: '8px 14px',
        fontSize: 13,
        background: 'rgba(255,255,255,0.06)',
        border: '0.5px solid var(--line-2, rgba(255,255,255,0.12))',
        borderRadius: 8,
        cursor: mutation.isPending ? 'wait' : 'pointer',
        opacity: mutation.isPending ? 0.6 : 1,
      }}
    >
      {mutation.isPending ? '處理中...' : label}
    </button>
  )
}
