import { useRef } from 'react'

/**
 * S133: Safari-safe clipboard hook for SKILL.md content.
 * Safari requires `navigator.clipboard.write` to be called synchronously
 * within a user gesture (transient activation), so we pass a pending Promise
 * to ClipboardItem rather than awaiting fetch before calling write.
 */
export function useCopySkillMarkdown(skillId: string) {
  const cache = useRef<string | null>(null)

  function prefetch() {
    if (cache.current !== null) return
    fetch(`/api/v1/skills/${skillId}/skill.md`)
      .then((r) => (r.ok ? r.text() : Promise.reject(r)))
      .then((text) => {
        cache.current = text
      })
      .catch(() => {/* fail silently on prefetch */})
  }

  function copy(): Promise<void> {
    const blobPromise: Promise<Blob> =
      cache.current !== null
        ? Promise.resolve(new Blob([cache.current], { type: 'text/plain' }))
        : fetch(`/api/v1/skills/${skillId}/skill.md`)
            .then((r) => (r.ok ? r.text() : Promise.reject(r)))
            .then((text) => {
              cache.current = text
              return new Blob([text], { type: 'text/plain' })
            })

    return navigator.clipboard.write([
      new ClipboardItem({ 'text/plain': blobPromise }),
    ])
  }

  return { prefetch, copy }
}
