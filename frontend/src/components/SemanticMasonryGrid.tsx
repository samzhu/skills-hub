import { SkillCard } from '@/components/SkillCard'
import type { SemanticSearchResult, Skill } from '@/types/skill'

interface SemanticMasonryGridProps {
  results: SemanticSearchResult[]
}

/**
 * S203: semantic result cards use CSS multi-column masonry.
 * `break-inside-avoid` keeps each SkillCard intact across column breaks.
 */
export function SemanticMasonryGrid({ results }: SemanticMasonryGridProps) {
  return (
    <div
      data-testid="semantic-masonry-grid"
      className="columns-1 gap-4 sm:columns-2 xl:columns-3"
    >
      {results.map((result, index) => (
        <div
          key={result.id}
          data-testid="semantic-masonry-item"
          className="mb-4 break-inside-avoid"
        >
          <SkillCard
            skill={result as unknown as Skill}
            score={result.score}
            featured={index === 0}
          />
        </div>
      ))}
    </div>
  )
}
