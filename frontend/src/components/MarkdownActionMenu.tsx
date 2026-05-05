import { ChevronDown } from 'lucide-react'
import { toast } from 'sonner'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useCopySkillMarkdown } from '@/hooks/useCopySkillMarkdown'

interface MarkdownActionMenuProps {
  skillId: string
}

export function MarkdownActionMenu({ skillId }: MarkdownActionMenuProps) {
  const { prefetch, copy } = useCopySkillMarkdown(skillId)
  const markdownUrl = `/api/v1/skills/${skillId}/skill.md`

  function handleCopy() {
    copy()
      .then(() => toast.success('已複製到剪貼簿'))
      .catch(() => toast.error('複製失敗，請重試'))
  }

  return (
    <DropdownMenu onOpenChange={(open) => open && prefetch()}>
      <DropdownMenuTrigger
        aria-label="Markdown 操作"
        className="inline-flex items-center gap-1 rounded-md border px-3 py-2 text-[13px] font-medium hover:bg-accent"
      >
        Markdown
        <ChevronDown className="h-3.5 w-3.5" />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuItem onClick={handleCopy}>複製為 Markdown</DropdownMenuItem>
        <DropdownMenuItem asChild>
          <a href={markdownUrl} target="_blank" rel="noopener noreferrer">
            開啟 Markdown
          </a>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
