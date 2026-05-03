# S096f2-T04: Frontend CollectionsPage 改寫 + CreateCollectionModal + InstallButton + AC-10/11/12 tests

## Spec
S096f2 — Collections Full Feature（spec doc: `docs/grimo/specs/2026-05-03-S096f2-collections-full.md`）

## BDD（涵蓋的 AC）

**AC-10: 「建立集合」按鈕啟用**
- Given：CollectionsPage 渲染後
- When：query 「建立集合」按鈕
- Then：button **不再 disabled**；點擊開 CreateCollectionModal

**AC-11: Create modal happy path**
- Given：alice 登入；3 個 PUBLISHED skill (sk1/sk2/sk3)
- When：alice 點「建立集合」→ modal 開 → 填 name "DevOps Starter" + description + category "DevOps" + skill picker 選 sk1/sk2/sk3 → 點 Submit
- Then：modal 關閉；CollectionsPage 出現新 card「DevOps Starter」(skillCount=3, installs=0)；URL-aware fetchMock 驗 POST /collections with body 含 4 個 fields

**AC-12: Install — N 個 download trigger**
- Given：collection card 對應 c1（含 3 個 skill）
- When：user 點 card 上「Install」按鈕
- Then：發 POST `/collections/c1/install`；接收 `downloadUrls: ["/api/v1/skills/sk1/download", ...]`；loop trigger 3 個 `<a download>` click（mock document.createElement('a') + element.click 計數；間隔 ≥ 30ms）；toast or invalidate `['collections']` 觸發 refetch

## Implementation outline

### `frontend/src/components/CreateCollectionModal.tsx` (new)

Mirror `CreateRequestModal` pattern（既有 component reference）：fixed overlay + form card + cancel/submit buttons。

Form fields：
- name input（max 200）
- description textarea（max 2000）
- category text input（max 100）
- skill picker — MVP 簡版：textarea 「每行貼一個 skill UUID」（per spec §2.6 trim list — fancy multi-select picker defer）

```tsx
export function CreateCollectionModal({ onClose }: { onClose: () => void }) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [category, setCategory] = useState('')
  const [skillIdsText, setSkillIdsText] = useState('') // newline-separated UUIDs
  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: () => {
      const skillIds = skillIdsText.split(/\s+/).filter(Boolean)
      return createCollection({
        name: name.trim(), description: description.trim() || null,
        category: category.trim(), skillIds,
      })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['collections'] })
      onClose()
    },
  })

  const canSubmit = name.trim() && category.trim() && skillIdsText.trim() && !mutation.isPending
  // ... layout
}
```

對齊 CreateRequestModal：無 toast，靠 modal close + list refetch 視覺回饋。

### `frontend/src/components/InstallButton.tsx` (new — 抽獨立 testability)

mutation + 依序 trigger N 個 browser download（per spec §4.7）：

```tsx
export function InstallButton({ collectionId, skillCount }: { collectionId: string; skillCount: number }) {
  const queryClient = useQueryClient()
  const mutation = useMutation({
    mutationFn: () => installCollection(collectionId),
    onSuccess: (data) => {
      // loop trigger N 個 <a download> click（50ms 間隔避 browser throttle）
      data.downloadUrls.forEach((url, i) => {
        setTimeout(() => {
          const a = document.createElement('a')
          a.href = url
          a.download = ''
          document.body.appendChild(a)
          a.click()
          a.remove()
        }, i * 50)
      })
      // 觸發 list refetch — install_count 自動更新
      queryClient.invalidateQueries({ queryKey: ['collections'] })
    },
  })

  return (
    <button
      type="button"
      onClick={() => mutation.mutate()}
      disabled={mutation.isPending}
      className="..."
    >
      {mutation.isPending ? '安裝中...' : `安裝 (${skillCount} 個技能)`}
    </button>
  )
}
```

50ms 間隔 per spec §2.5（hypothesis — 走粗 50ms；implementer 可調）。Test 驗 click 順序 + 數量。

### `frontend/src/pages/CollectionsPage.tsx` (modify — 取代既有 inline useQuery + disabled CTA)

```tsx
export function CollectionsPage() {
  const { data: collections, isLoading } = useCollections()
  const [showModal, setShowModal] = useState(false)

  return (
    <AppShell>
      <div className="mb-6">
        <h1>...</h1>
        <button
          type="button"
          onClick={() => setShowModal(true)}
          className="..."  // 不再 disabled
        >
          建立集合
        </button>
      </div>

      {isLoading ? <LoadingSpinner /> :
       !collections?.length ? <EmptyState ... /> :
       <div>{collections.map(c => <CollectionCard key={c.id} collection={c} />)}</div>}

      {showModal && <CreateCollectionModal onClose={() => setShowModal(false)} />}
    </AppShell>
  )
}

// CollectionCard 加 Install button
function CollectionCard({ collection }: { collection: SkillCollection }) {
  return (
    <div>
      ... 既有 card 內容 ...
      <InstallButton collectionId={collection.id} skillCount={collection.skillCount} />
    </div>
  )
}
```

「建立集合」CTA 從 disabled → active onClick 開 modal；CollectionCard 加 InstallButton。

### `frontend/src/pages/CollectionsPage.test.tsx` (modify — 取代既有 stub-state assertions)

既有 test 可能驗 disabled CTA / EmptyState — 升級為 AC-10/11/12 BDD：

```typescript
describe('CollectionsPage — AC-10/11/12', () => {
  it('AC-10: 「建立集合」按鈕 active；點擊開 modal', async () => { ... })
  it('AC-11: Modal 填 form + submit → POST /collections + modal close + list refetch', async () => { ... })
  it('AC-12: Install → POST + loop trigger N 個 <a> click（mock document.createElement）', async () => { ... })
})
```

URL-aware fetchMock 對齊 NotificationsPage.test.tsx 既驗 pattern。AC-12 mock `document.createElement('a')` + spy on `.click()` 計次（or 用 `vi.spyOn(HTMLAnchorElement.prototype, 'click')`）。

## Target Files

- `frontend/src/components/CreateCollectionModal.tsx` (new — mirror CreateRequestModal pattern；MVP textarea skill picker)
- `frontend/src/components/InstallButton.tsx` (new — mutation + loop browser download trigger)
- `frontend/src/pages/CollectionsPage.tsx` (modify — CTA active + Modal trigger + InstallButton on card + useCollections hook)
- `frontend/src/pages/CollectionsPage.test.tsx` (modify — AC-10/11/12 tests + URL-aware fetchMock)

## Depends On
- T01 + T02 + T03（backend endpoints + frontend api/hooks 全 ship）

## Status
pending
