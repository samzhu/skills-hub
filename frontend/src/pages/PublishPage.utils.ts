/**
 * S099b2 — Frontmatter live validation。
 * 簡化 parser：檢 `---\n...\n---` block 存在 + 內含 `name:` 與 `description:` 欄位。
 * 不做完整 YAML parse（backend 負責）；只 fail-fast 給作者立刻 feedback。
 *
 * Returns { hasFrontmatter, hasName, hasDescription, errors }；errors 是中文人類訊息列表。
 */
export function validateFrontmatter(content: string): {
  hasFrontmatter: boolean
  hasName: boolean
  hasDescription: boolean
  errors: string[]
} {
  const errors: string[] = []
  const trimmed = content.trim()
  if (!trimmed) {
    return { hasFrontmatter: false, hasName: false, hasDescription: false, errors }
  }
  if (!trimmed.startsWith('---')) {
    errors.push('SKILL.md 必須以 YAML frontmatter 開頭（首行 ---）')
    return { hasFrontmatter: false, hasName: false, hasDescription: false, errors }
  }
  const lines = trimmed.split('\n')
  let endIdx = -1
  for (let i = 1; i < lines.length; i++) {
    if (lines[i].trim() === '---') {
      endIdx = i
      break
    }
  }
  if (endIdx === -1) {
    errors.push('Frontmatter 缺少結束 ---（需在第 N 行單獨一個 ---）')
    return { hasFrontmatter: false, hasName: false, hasDescription: false, errors }
  }
  const fmBlock = lines.slice(1, endIdx)
  const hasName = fmBlock.some((l) => /^name:\s*\S/.test(l))
  const hasDescription = fmBlock.some((l) => /^description:\s*\S/.test(l))
  if (!hasName) errors.push('缺必填欄位：name')
  if (!hasDescription) errors.push('缺必填欄位：description')
  return { hasFrontmatter: true, hasName, hasDescription, errors }
}
