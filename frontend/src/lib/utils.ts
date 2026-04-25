import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

/**
 * 合併 Tailwind CSS class 名稱的工具函式。
 *
 * 先用 `clsx` 處理條件式 class（支援陣列、物件語法），
 * 再用 `tailwind-merge` 去除重複並讓後方的 class 正確覆蓋前方同功能的 class
 * （例如 `p-2` 會被後方的 `p-4` 取代，而非兩者共存）。
 *
 * @param inputs 任意數量的 class 值，支援字串、陣列、條件物件
 * @returns 合併後的 class 字串
 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}
