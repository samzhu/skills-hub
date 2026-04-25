import { useRef, useState, type DragEvent } from 'react'
import { Upload } from 'lucide-react'
import { cn } from '@/lib/utils'

/** FileDropZone 的 props 定義 */
interface FileDropZoneProps {
  /** 使用者選取或拖拽檔案後的回呼，傳入選中的 File 物件 */
  onFileSelect: (file: File) => void
  /** 目前已選取的檔案；null 表示尚未選取，元件顯示提示文字 */
  selectedFile: File | null
  /** 允許的副檔名（傳給 input[accept]），預設為 `.zip` */
  accept?: string
}

/**
 * 拖拽上傳區域元件：支援拖拽放下（drag-and-drop）及點擊選取兩種方式選取檔案。
 *
 * 整個 div 作為點擊代理，觸發隱藏的 `<input type="file">` 以開啟系統檔案選擇器。
 * 選取後顯示檔案名稱與大小預覽，方便使用者確認選取結果。
 */
export function FileDropZone({ onFileSelect, selectedFile, accept = '.zip' }: FileDropZoneProps) {
  const [isDragging, setIsDragging] = useState(false)
  // 透過 ref 程式化觸發隱藏的 <input> click，達到自訂樣式的點擊選取效果
  const inputRef = useRef<HTMLInputElement>(null)

  /**
   * 處理檔案拖放：必須呼叫 `e.preventDefault()` 以覆寫瀏覽器預設行為
   * （否則會在新分頁開啟檔案）。
   */
  const handleDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault()
    setIsDragging(false)
    const file = e.dataTransfer.files[0]
    if (file) onFileSelect(file)
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
          if (file) onFileSelect(file)
        }}
      />
    </div>
  )
}
