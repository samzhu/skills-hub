export const skillKeys = {
  all: ['skills'] as const,
  detail: (id: string) => ['skills', id] as const,
  byAuthorName: (author: string | undefined, name: string | undefined) =>
    ['skills', 'by-author-name', author, name] as const,
  grants: (id: string | undefined) => ['grants', id] as const,
}
