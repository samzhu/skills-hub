import type { Flag } from '../api/flags'

/**
 * S112 — Flag type / status 中譯與 pill 樣式 const。
 *
 * Type label 對齊後端 `FlagService.ALLOWED_TYPES`（S072）；status pill 樣式
 * 借用 SkillDetailPage `STATUS_PILL_STYLE` 同 semantic-soft palette
 * （warning-soft for OPEN、success-soft for RESOLVED）以維 visual consistency。
 */
export const FLAG_TYPE_LABEL: Record<Flag['type'], string> = {
  malicious: '惡意指令',
  spam: '垃圾內容',
  inappropriate: '不當內容',
  copyright: '版權問題',
  security: '資安疑慮',
  other: '其他',
}

export const FLAG_STATUS_LABEL: Record<Flag['status'], string> = {
  OPEN: '待處理',
  RESOLVED: '已處理',
}

export const FLAG_STATUS_STYLE: Record<Flag['status'], { backgroundColor: string; color: string }> = {
  OPEN: { backgroundColor: 'rgba(239,159,39,0.14)', color: '#FAC775' },
  RESOLVED: { backgroundColor: 'rgba(29,158,117,0.14)', color: '#6FD8B0' },
}
