import { useRef, useState, type DragEvent } from 'react'
import { Upload } from 'lucide-react'
import { cn } from '@/lib/utils'

/** S037: 預設 10MB，與 backend application.yaml `spring.servlet.multipart.max-file-size` 對齊。 */
const DEFAULT_MAX_SIZE_BYTES = 10 * 1024 * 1024

/** FileDropZone 的 props 定義 */
interface FileDropZoneProps {
  /** 使用者選取或拖拽檔案後的回呼，傳入選中的 File 物件 */
  onFileSelect: (file: File) => void
  /** 目前已選取的檔案；null 表示尚未選取，元件顯示提示文字 */
  selectedFile: File | null
  /** 允許的副檔名（傳給 input[accept]），預設為 `.zip` */
  accept?: string
  /**
   * S037：檔案大小上限（bytes）；預設 10MB（對齊 backend）。
   * 超限時 inline 錯誤訊息顯示，`onFileSelect` 不會被呼叫，避免 user 浪費頻寬上傳到 backend 才得 413。
   */
  maxSizeBytes?: number
}

/**
 * 拖拽上傳區域元件：支援拖拽放下（drag-and-drop）及點擊選取兩種方式選取檔案。
 *
 * 整個 div 作為點擊代理，觸發隱藏的 `<input type="file">` 以開啟系統檔案選擇器。
 * 選取後顯示檔案名稱與大小預覽，方便使用者確認選取結果。
 *
 * S037：加 `maxSizeBytes` prop（預設 10MB）— 超限時顯示 inline 錯誤、不呼叫 `onFileSelect`，
 * 避免 user 上傳大檔後等回應才知失敗。
 */
export function FileDropZone({
  onFileSelect,
  selectedFile,
  accept = '.zip',
  maxSizeBytes = DEFAULT_MAX_SIZE_BYTES,
}: FileDropZoneProps) {
  const [isDragging, setIsDragging] = useState(false)
  const [sizeError, setSizeError] = useState<string | null>(null)
  // 透過 ref 程式化觸發隱藏的 <input> click，達到自訂樣式的點擊選取效果
  const inputRef = useRef<HTMLInputElement>(null)

  /**
   * S037：集中 file 處理 — drag 與 click 兩條 path 都先過此 size guard。
   * 超限時 set inline error；通過則 clear error 並呼叫 caller `onFileSelect`。
   */
  const handleFile = (file: File) => {
    if (file.size > maxSizeBytes) {
      const limitMb = (maxSizeBytes / 1_048_576).toFixed(0)
      const fileMb = (file.size / 1_048_576).toFixed(1)
      setSizeError(`檔案大小 ${fileMb} MB 超過 ${limitMb} MB 限制`)
      return
    }
    setSizeError(null)
    onFileSelect(file)
  }

  /**
   * 處理檔案拖放：必須呼叫 `e.preventDefault()` 以覆寫瀏覽器預設行為
   * （否則會在新分頁開啟檔案）。
   */
  const handleDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault()
    setIsDragging(false)
    const file = e.dataTransfer.files[0]
    if (file) handleFile(file)
  }

  /**
   * 處理拖拽懸停：同樣需要 `preventDefault()` 才能接受 drop 事件。
   * 注意：onDragLeave 在滑鼠移入子元素時也會觸發，可能造成拖拽樣式短暫閃爍，
   * 此為已知的 drag-and-drop 行為，MVP 階段可接受。
   */
  const handleDragOver = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault()
    setIsDragging(true)
  }

  return (
    <div>
      <div
        // 點擊整個區域時，程式化觸發隱藏 input 的 click 事件以開啟系統選檔對話框
        onClick={() => inputRef.current?.click()}
        onDrop={handleDrop}
        onDragOver={handleDragOver}
        onDragLeave={() => setIsDragging(false)}
        className={cn(
          'flex cursor-pointer flex-col items-center justify-center rounded-lg border-2 border-dashed p-8 transition-colors',
          isDragging ? 'border-primary bg-primary/5' : 'border-muted-foreground/25 hover:border-primary/50'
        )}
      >
        <Upload className="mb-2 h-8 w-8 text-muted-foreground" />
        {selectedFile ? (
          <div className="text-center">
            <p className="font-medium">{selectedFile.name}</p>
            {/* 顯示 KB（1 KB = 1024 bytes），小數點後一位，方便確認檔案大小 */}
            <p className="text-sm text-muted-foreground">
              {(selectedFile.size / 1024).toFixed(1)} KB
            </p>
          </div>
        ) : (
          <div className="text-center">
            <p className="font-medium">拖拽 zip 檔到此處</p>
            <p className="text-sm text-muted-foreground">或點擊選取檔案</p>
          </div>
        )}
        {/* 實際的 file input 隱藏，樣式由外層 div 代替 */}
        <input
          ref={inputRef}
          type="file"
          accept={accept}
          className="hidden"
          onChange={(e) => {
            const file = e.target.files?.[0]
            if (file) handleFile(file)
          }}
        />
      </div>
      {/* S037: size guard 錯誤訊息 — 超限時 inline 顯示，不阻止 user 重新選檔 */}
      {sizeError && (
        <p className="mt-2 text-sm text-destructive">{sizeError}</p>
      )}
    </div>
  )
}
