import { Star } from 'lucide-react'

/**
 * S098e2-T03 — 5 顆星評分元件。
 *
 * 兩種模式：
 * - **readonly**（無 `onChange`）：顯示 `value` 1-5 的 fill 比例；半星用 `clip-path`
 *   風格不必，視 round-to-nearest-half 已足；<v 顯空心。
 * - **interactive**（有 `onChange`）：5 顆按鈕；hover/click 觸發 onChange(N)。
 *
 * 設計：用 1-5 顆星比較（不用 0），點 1 顆 = rating=1；最低有效值 1。
 * 對齊後端 `rating ∈ [1, 5]` constraint。
 */
interface RatingStarsProps {
  value: number
  onChange?: (rating: number) => void
  size?: number
}

export function RatingStars({ value, onChange, size = 16 }: RatingStarsProps) {
  const interactive = !!onChange

  if (interactive) {
    return (
      <div className="inline-flex items-center gap-0.5" role="radiogroup" aria-label="評分">
        {[1, 2, 3, 4, 5].map((n) => (
          <button
            key={n}
            type="button"
            role="radio"
            aria-checked={value === n}
            aria-label={`${n} 星`}
            onClick={() => onChange!(n)}
            className="cursor-pointer rounded p-0.5 transition hover:scale-110"
          >
            <Star
              width={size}
              height={size}
              className={n <= value ? 'fill-[#FAC775] text-[#FAC775]' : 'text-[#5E5B55]'}
            />
          </button>
        ))}
      </div>
    )
  }

  // Readonly — 整數 fill；可顯示 4.3 (round to 4 顆 + 1 顆有半 fill 的視覺近似)
  // 簡化：四捨五入至最接近整數，視覺上 fill N 顆
  const filled = Math.round(value)
  return (
    <span className="inline-flex items-center gap-0.5" aria-label={`評分 ${value} / 5`}>
      {[1, 2, 3, 4, 5].map((n) => (
        <Star
          key={n}
          width={size}
          height={size}
          className={n <= filled ? 'fill-[#FAC775] text-[#FAC775]' : 'text-[#5E5B55]'}
        />
      ))}
    </span>
  )
}
