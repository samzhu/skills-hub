這是 Skills Hub 的首頁設計，整體風格對齊 PRD 裡的調性 — 企業內部 registry，扁平、現代、克制。

幾個設計重點：

**頂部搜尋欄用了 beam 動畫邊框** — 呼應 D18 指定的 Border Beam 元件，也暗示這是平台的核心互動。右側切換「Semantic / Keyword」對應 P1 關鍵字搜尋與 P5 語意搜尋的雙模式。

**左側篩選分三層** — 分類（按 count 排序）、風險等級（配色對應 Security Model 的綠/黃/紅分級）、相容工具（對應 SKILL.md 的 `compatibility` 欄位，呼應「跨 30+ 工具可攜」的定位）。

**卡片網格用「特色卡 + 一般卡」的混排** — 第一張 `docker-compose-helper` 套了 beam 邊框動畫來凸顯 featured / verified 的技能，其他卡片維持乾淨的 0.5px 邊框。每張卡片包含 SKILL.md 規範裡的關鍵 metadata：name、author、description、version、風險標籤、下載數、星評。

**色彩語意一致** — 風險等級用 green / amber / red 的語意色，技能類型 icon 則用 purple / blue / teal / coral / amber / pink 這幾個分類色，避免跟風險色混淆。
==================================================================
這是 `docker-compose-helper` 的詳情頁，扣著 P1 詳情頁、P3 風險評估、P4 下載、P6 使用數據的 acceptance criteria 一起設計。

幾個設計決定：

**Header 做成「單一行動呼籲」** — 右上的「Download」按鈕套了 beam 邊框，是整頁最強的焦點，對應 P4「一鍵安裝」的核心動作；次要動作（Star）保持低調。版本號、風險等級、Verified 三個徽章緊跟標題，讓使用者 3 秒內判斷「這個技能值不值得裝」。

**四個 metric cards 對齊 PRD 關注的指標** — Downloads（含 7 天趨勢，對應 P6 SBE）、Rating（社群信號）、Versions（發佈活躍度）、Open flags（安全信號，對應 Security Model 的社群回報）。Open flags = 0 本身就是一個很強的信任訊號。

**Tabs 預留了完整生命週期的入口** — Overview 是 rendered SKILL.md；Risk assessment / Versions / Reviews / Flags 各自對應一個獨立子頁面。MVP 可以只做 Overview + Versions，其他 tab 先作為視覺 affordance。

**中間主欄模擬了 rendered SKILL.md** — 頂部用等寬字體還原 frontmatter 區塊（呼應 agentskills.io 規範），下方用 markdown 渲染「When to use this skill」和「Quick install」。這種「frontmatter 可見 + 正文可讀」的呈現方式，對開發者來說比純粹隱藏 metadata 更有信任感。

**Risk assessment 面板做成「檢查清單」而不是單一分數** — 每項掃描結果（危險指令、敏感路徑、secret patterns、外部依賴、檔案大小）都有獨立的 pass/warn icon 和 hint 說明。這直接映射 PRD 的自動掃描項目清單，比「高/中/低」一個字更有資訊量，也讓「為什麼是低風險」可驗證。

**右側欄的資訊密度刻意拉高** — 30 天趨勢迷你圖、Details（size、file count、scripts = None 這點尤其重要，因為「無 scripts」就是低風險的關鍵佐證）、Compatibility chips、Version history。Version history 每一行都是獨立下載入口，對應 P4 的「下載指定版本」scenario。
=====================================================================
這是發佈流程的 step 3（審核 + 確認），把 P2 的四個 SBE scenarios 一次收進同一個視圖。

幾個設計決定：

**4 步驟的 stepper 把 event sourcing 的領域事件視覺化** — Upload（檔案入庫）→ Validate（SKILL.md frontmatter 驗證）→ Review & publish（當前步驟，使用者確認）→ Live（`SkillVersionPublished` event 寫入）。使用者看到的流程就是後端的 event pipeline，不做多餘抽象。

**三個驗證 section 各對應一個 SBE scenario**：

- SKILL.md is valid — 對應「上傳合法 skill」scenario，直接秀 frontmatter + 欄位長度檢查（name ≤ 64、description ≤ 1024，對齊 agentskills.io 規範）
- Bundle structure — 呼應 skill 資料夾結構（SKILL.md required，references/ assets/ optional），讓作者確認解壓後的樣子
- Risk scan — 對應 P3 風險評估引擎的 5 個掃描項目，第一條「No scripts/ directory present」是關鍵判準，直接說明為什麼被自動標為低風險

**失敗態怎麼處理** — 雖然這張圖呈現的是 happy path，但三個 section 的 check icon 設計成可以隨時切換 pass / warn / fail。若是 PRD 裡的「上傳不合規」scenario（缺 SKILL.md），就會是第一個 section 顯示紅色 fail icon、標題改成「Missing required field: name」、footer 的 Publish 按鈕 disabled。若是含 `rm -rf` 的高風險 scripts，Risk scan section 會紅底，footer 改成「Submit for review」而非直接發佈。

**Version input 同時支援「輸入」和「bump 建議」** — 對應「更新已有 skill 的版本」scenario，顯示「Previous: v2.0.1」幫作者定位，hint 說明舊版本保留，符合 PRD 裡「舊版本仍可下載」的要求。

**右側「Safe to auto-publish」是分級制度的使用者面呈現** — 綠色 callout 明確告訴作者：這個 skill 會「立即上架」而非「進入待審核」，直接對應 Security Model 裡低風險/中風險/高風險三種不同的後續流程。如果 scan 結果是高風險，這張卡會換成 amber 底、標題改成「Will enter review queue」。

**sidebar 的 tips 有意識地引導使用者「寫好 description」** — 因為 P5 語意搜尋會用 description 做 embedding，description 寫得好不好直接影響這個 skill 能不能被找到。這條 tip 其實是在向作者傳達產品的搜尋機制。
==========================================================
兩個失敗態放在一起呈現，各自對應 PRD 裡不同的 scenario — State A 是「格式不合規」（P2，完全擋下），State B 是「含危險指令」（P3，放進審核佇列）。這兩個狀態刻意對比，因為它們對作者的意義完全不同。

幾個設計決定：

**兩種失敗用不同色系而不是都用紅色** — State A 用紅色（`#FCEBEB` + `#791F1F`），State B 用琥珀黃（`#FAEEDA` + `#633806`）。紅色 = 「系統拒絕接受」，琥珀黃 = 「系統接受但需要人類介入」。這對齊 PRD 裡「高風險 → 待審核」而非「高風險 → 拒絕」的分級邏輯。顏色在這裡承擔資訊密度，讓作者 1 秒內知道該做什麼。

**State A 的 stepper 停在 Validate 上，step 3、4 是灰的** — 對應「skill 不會上架」的 SBE scenario。後端層級上，連 `SkillCreated` event 都還沒發。右側 callout「Nothing stored yet」直接講這件事，避免作者以為已經部分上架了，離開頁面會擔心 zombie state。

**State A 的錯誤訊息精準到行號 + 具體欄位** — 對應 SBE「回傳具體的錯誤訊息（哪個欄位、什麼問題）」。`Missing required field: name at line 2` 就是 PRD 寫的內容，直接搬到 UI 上。第二條 WARN（description vague）是加值訊息 — 不阻擋發佈，但幫作者寫出能被語意搜尋找到的描述，呼應 P5 的 embedding 機制。

**State B 的代碼視圖用 3 種紅色強度疊加** — line 5、7、9 整行底色（`#FCEBEB`）標示「這一行有問題」、具體的危險 token（`bash`、`~/.config/legacy`、`~/.aws/credentials`）用更深的粉紅 highlight（`#F09595`）。這直接映射 PRD 的「列出具體的危險項目及所在檔案/行號」scenario，比只給行號多了精準的 token 定位。

**3 個 HIGH findings 對應 Security Model 的三個自動掃描項目** — curl|bash（危險 shell 指令）、rm -rf（危險 shell 指令）、`~/.aws/credentials`（敏感路徑存取）。第 4 條 INFO（external domain）對應「外部 URL 依賴列舉」。這讓作者看到的不只是「有問題」，而是「是哪一類問題、屬於哪條規則」。

**State B 的 footer 有 3 個按鈕而不是 2 個** — Withdraw（徹底放棄）、Publish（disabled，讓作者知道這條路被封了）、Submit for review（主要 CTA）。把 disabled 的 Publish 顯示出來而不是隱藏，是為了讓作者明白「你想直接發佈，但系統不允許」— disabled 是資訊，隱藏會讓人以為這個流程本來就沒有直接發佈的選項。這對 Publisher 的心智模型比較誠實。

**State B 的右側 callout 寫「Flag emitted · SkillRiskAssessed event with level = HIGH has been written」** — 特意暴露後端事件名稱給作者看。Skills Hub 的使用者主要是開發者，這種「你的行動在系統裡變成了什麼」的透明度反而加分，也幫作者理解後續審核佇列是怎麼運作的。對一般終端使用者可能會改寫得更日常。

這兩個狀態跟前一張「happy path」共用同一組視覺元件（stepper、section card、check icon、footer），證明整個發佈流程的 UI 系統可以承載從「全綠」到「全紅」的完整 spectrum，不需要為每個狀態重新設計。
========================================================
這是作者視角的「我的技能」列表頁，對應 PRD P6 的第三個 SBE scenario「技能作者查看自己的數據」。

幾個設計決定：

**Hero 區塊連著發佈者身分** — 「7 skills you've published as `platform-team`」把作者身分亮出來，呼應 PRD 的 `author` frontmatter 欄位。`platform-team` 在其他視圖（詳情頁、首頁卡片）都是 author 顯示值，這裡閉環。publish CTA 繼續用 beam 邊框，跟首頁、詳情頁保持一致的「主要動作」視覺識別。

**4 個 summary metrics 的選擇對應 PRD 的維度** — Total skills（含狀態拆解：5 published · 1 pending · 1 draft，一行 hint 就把整個 lifecycle 視覺化）、Total downloads（總量 + 週變化，對應 P6「下載趨勢」）、Avg rating（跨 skill 聚合，social proof）、Open flags（安全信號，呼應 Security Model 的社群回報機制）。Open flags = 2 + `↑ 1 new flag this week` 用紅色 delta 是故意的 — 作者需要看到這是 negative signal 而不是 neutral count。

**Tabs 照 lifecycle 狀態分** — All / Published / Pending review / Drafts，對應 PRD 裡的三種狀態（已上架 / 待審核 / 草稿）。「Pending review」的 badge 刻意套成 amber 色（`#FAEEDA`/`#633806`），跟前一張發佈流程的 State B 顏色一致 — 使用者看到這個琥珀色就知道「同一件事，從作者視角」。

**表格 column 設計緊扣作者需要決策的資訊** — Skill（識別）、Status（生命週期）、Downloads 30d + sparkline（健康度）、Latest version + date（活躍度）、Rating（市場接受度）、更多動作（⋯）。特意沒放 size、file count 這些技術 metadata — 這些在詳情頁裡，列表頁只放 actionable 的資訊。

**Sparkline 用顏色編碼趨勢而不是只看數字** — 上升用綠（`#1D9E75`）、下降用紅（`#A32D2D`）。`terraform-module-author` 的下降曲線 + 紅色 `↓ 3%` 是特意構造的場景，讓作者一眼看出「這個 skill 需要更新或促銷」。`gha-workflow-builder` 的 `↑ 52%` 則是新興 skill 的訊號，值得作者多投資。這種「一眼可讀的健康度」是 P6 儀表板的核心價值。

**Pending 那一行刻意用不同底色（`#FDF8EC`）且 Downloads/Rating 顯示為 `—`** — `env-bootstrap` 這個 skill 就是前一張 State B 提交的那個高風險 skill。標題下方直接寫「3 high-severity findings · waiting on security review」，並且 `Hidden from browse` 告訴作者「這個 skill 現在一般使用者看不到」。狀態圓點套了 pulse 動畫，暗示「still in progress」。這一行把整個 ES 事件流的輸出（SkillRiskAssessed event level = HIGH）具體化為作者可見的狀態。

**Draft row 用 opacity 0.85 + 灰色 icon（`?`）+ 所有 metric 都是 `—`** — `untitled-skill` 這種還沒傳 zip 的草稿直接視覺弱化，不搶主視線。但仍然在列表裡而不是隱藏，因為作者需要看到「我還有個東西沒完成」。

**Row 2 的「k8s-deployment」medium risk 在列表裡就顯示出來** — 雖然是已上架的正常狀態，但 `medium risk` 這個標籤放在 meta 那行，對作者是個提醒：這是一個需要持續關注的 skill。
=======================================
這是管理者視角的 Analytics 儀表板，對應 P6 的第二個 SBE scenario「平台總覽儀表板」。

幾個設計決定：

**Hero 選了 time range picker 而不是 action button** — 這頁不是拿來「做事」的，是拿來「看」的。時間窗（24h / 7d / 30d / 90d / All time）是 analytics 最基本的互動軸，放在最上方最顯眼處。右下角小字「last refreshed 4 minutes ago」對應 PRD 的事件驅動 projection — 數據不是即時的，是 AnalyticsProjection 消費事件後的 read model，坦承呈現這個真相比假裝 real-time 更誠實。

**4 個頂部 metric 對應 PRD 裡「平台總覽」的必要維度** — Total skills、Downloads 30d（套 beam 邊框，因為這是管理者最關心的「平台活躍度」單一指標）、Active publishers（生態健康度）、Open flags（安全信號 + 需要 triage 的 call to action）。Open flags 那格的 delta 用紅色 `↑ 3` + `needs triage` 是故意的 — 這是唯一需要管理者採取行動的 metric，視覺權重必須拉起來。

**主圖選「雙軸圖」：下載量線圖 + 新增版本柱狀圖** — 一張圖同時回答「使用量漲沒漲」和「供給側活不活躍」兩個問題，比單純的下載折線圖資訊密度高。圖表標題下方的 `Events sourced from SkillDownloaded and SkillVersionPublished · daily buckets` 直接暴露資料來源 — 向內部工程使用者傳達「你看到的數字是怎麼來的」，這對 debug 和信任都有幫助。

**Top skills 用 horizontal bar + 排名數字** — 不用圓餅圖是因為比較 7 個 skill 的相對量，bar chart 眼睛掃一眼就能讀。每條 bar 按最大值（`docker-compose-helper` 的 1,284）做 100% 錨點，其他按比例。排名數字獨立於 skill 名稱，讓「第幾名」變成快速掃描的 anchor。

**Risk distribution 用 donut chart + 文字圖例併排** — donut 中間放「68% low-risk」作為主訊息，右側的 legend 既標註顏色也給出絕對數（168 skills、62 skills、17 skills）。17 skills 後面加「in review」是關鍵細節 — 告訴管理者這 17 個高風險 skill 不是放任在上架狀態，而是正在審核。這呼應了 Security Model 的分級處理邏輯。

**Category mix 用不同顏色編碼 6 個類別** — 這是這整頁唯一「colors encode identity, not severity」的圖表。使用 purple / teal / coral / pink / blue / amber 六個 ramp 中的 mid-tone，對齊 PRD 提到的 DevOps / Testing / Documentation / Data / Frontend / Security 分類。不用連續色譜因為類別之間沒有排序關係。

**Activity feed 是這頁最「事件溯源原生」的區塊** — 每個條目對應一個 domain event，用顏色編碼事件類型：綠（pub = `SkillVersionPublished`）、紅（flag = `SkillFlagged`）、紫（new = `SkillCreated`）、琥珀（review = `SkillRiskAssessed` → approved）。標題下方直接寫「Live stream from domain_events」— 對 Skills Hub 這種事件驅動架構的平台，把 event log 做成管理者的 UI 是很自然的事。最新那筆正是前面發佈流程 State B 產生的 `env-bootstrap flagged` — 整個 UI 系列在這裡閉環。

**「New skills this week」區塊把 14 個新增 skills 精選出最新 4 個** — 對應 PRD 的「本週新增 skills」metric。每個 item 除了名稱作者時間，還帶了 risk pill — 讓管理者在掃「誰發了新東西」的時候順便看到安全訊號。

整個儀表板其實是 CQRS Query Side 的完整視覺化 — 每張圖的資料都是不同 projection（downloads projection、category projection、risk projection、activity feed projection）產生的 read model，並且資料來源被誠實地標示在 subtitle 裡。這讓 UI 本身就是架構的 documentation。
======================================
這是 P5 語意搜尋模式的結果頁，查詢是 PRD SBE 的直接延伸 —「我想把應用部署到容器環境」再加上「設定自動 scaling」，讓檢索難度稍微拉高一點，這樣不同 skills 的語意匹配度才會拉開差異。

幾個設計決定：

**頂部搜尋框套 beam 邊框，Semantic 模式有紫色 pulse dot** — 跟首頁的 search bar 是同一個元件，但搜尋模式切到 Semantic。紫點呼應 Spring AI + Gemini embedding 的技術棧顏色（`#7F77DD` 在整個設計系統裡代表「AI 推理」）。Keyword 模式是灰的，明確指示「你現在走的是語意管線」。

**AI intent summary 是語意搜尋最關鍵的 UX 差異化** — 純關鍵字搜尋不會告訴你「我怎麼理解你」，但語意搜尋必須暴露這個步驟，因為這是讓使用者建立信任的唯一方式。紫色卡片（`#EEEDFE`）+「Understood your intent」標籤 + 一段解釋 + 四個可刪除的 concept chips（containerization / orchestration / auto-scaling / deployment）。每個 chip 旁邊的 × 讓使用者可以否定系統的推論（「我其實不關心 orchestration」），這對應 PRD 的「調整描述」那條 scenario — 不用重打整句話，直接撥掉錯誤推論。

**Results meta 誠實標示技術棧** — `ranked by semantic similarity · embeddings via Gemini`。這句話不是給終端使用者看的行銷文案，是給工程使用者看的技術透明度。Skills Hub 的目標用戶都是開發者，他們想知道「這個排序是怎麼來的」。`7 skills` 這個小數字也對應 PRD 的「無相關結果」scenario — 如果是 0 個，這裡就會換成空狀態。

**Top match 用漸層底色 + 星形徽章 + 0.94 分數明顯區分** — `k8s-deployment` 是唯一一個命中全部 4 個推論 concept 的 skill。Best match 徽章用紫色（與 intent 卡片同色系），分數 0.94 以等寬字呈現 — 資訊密度高，讓開發者能快速判斷 top 1 和後面的差距。漸層底色用的是非常淺的紫（`#F7F6FE`），不搶戲但讓 top match 有視覺優先級。

**每張卡片有 description + why-match reasoning 兩層** — description 裡 highlight 關鍵詞（用紫色 mark，對應 intent concept），why-match 則用灰底小卡解釋「為什麼這個 skill 適合你」。這直接對應 PRD 的「任務導向推薦 / 附上匹配理由」SBE scenario。但 why-match 寫得有深度 — 不是「because it matches keywords」，而是 #2 `docker-compose-helper` 的「compose itself doesn't orchestrate auto-scaling. Useful as an upstream step before deploying to Kubernetes」— 這種「是部分匹配，但可以當作上游工具用」的判斷才是語意搜尋的真正價值。

**Similarity score 用「分數 + 迷你 bar」並置** — 0.94、0.82、0.74、0.68 都是等寬字體，bar 寬度按分數縮放。這讓「哪些結果是強匹配、哪些是邊緣」一眼可讀。分數本身對工程使用者是熟悉的 signal（cosine similarity），但 bar 是給非技術使用者準備的視覺輔助。

**最底下的 Refine chips 是語意搜尋獨有的 pattern** — 純關鍵字搜尋不會有這個。「Only low-risk skills」、「For Kubernetes specifically」、「Just the Docker side」、「Add monitoring & alerting」— 每一條都是對當前 intent 的語意調整，點擊就會重新跑 embedding + 向量搜尋。箭頭 ↗ 暗示這些是 sendPrompt 類型的 action（對應 visualize design system 的慣例）。這讓搜尋變成對話式 refinement 而不是一次性查詢。

**第二名 `docker-compose-helper` 排在第二是刻意的** — 它的下載數（1,284）比 `k8s-deployment`（892）高，但分數反而低。這證明語意排序 ≠ 熱門排序，系統真的在做語意匹配而不是 popularity proxy。這個反直覺反而是「語意搜尋 works」的最強證明。

**沒有左側分類 sidebar** — 跟首頁（有 sidebar）不同，搜尋結果頁把 sidebar 拿掉讓結果區變寬。語意搜尋的 mental model 是「描述你要的，系統幫你找」而不是「從分類樹往下鑽」— UI 必須反映這個差異，不然會把使用者拉回分類思維。Refine chips 是 sidebar 的替代品 — 同樣是 filter，但以對話式呈現。

到這裡我們走過了 Skills Hub 核心流程的完整 UI — 首頁發現、詳情、發佈（happy path + 失敗態 + 高風險態）、作者列表、平台 analytics、語意搜尋。下一個視圖可以往哪走：
=====================================
這是管理者的 review 工作區，把「佇列總覽 + 單一審核詳情」放在同一個 master-detail 視圖裡，而不是兩頁切換 — 因為審核是連續性工作，reviewer 需要在一個項目處理完之後立刻看到下一個，context switch 越少越好。

幾個設計決定：

**Admin 模式的視覺暗示** — 頂部 logo 旁邊加了紫色 `Admin` pill，右上角的 avatar 換成琥珀色（`#FAEEDA`/`#633806`，跟前面「Pending review」狀態同色系），讓使用者清楚知道「現在是在後台工作而不是一般使用」。同時頁面寬度比前幾頁飽滿（queue 放左、detail 放右），這是 admin tool 應有的資訊密度。

**Queue strip 4 個 metric 聚焦「Reviewer 今天要怎麼安排時間」** — Pending 7、High risk 3、Avg wait 1h 48m、Approved 7d 12。Avg wait 和 Approved 7d 是 reviewer 績效指標，暴露這些是為了讓審核者對自己的產能有感。High risk 用紅色 emphasis 提示「這幾件是優先級最高的」。

**Queue list 的項目資訊是 triage 導向的** — 每一筆卡片上頭：skill 名稱 + risk pill + 版本 + author + findings count + 等待時間。特別把 findings count（`3 high-severity`、`2 high · 3 medium`）放出來，因為這是 reviewer 決定「先看哪個」的核心依據。等待時間靠右對齊且用 tabular-nums，讓 scan 起來一目了然誰等最久。`active` 那一筆用左邊 2px 黑色邊條 + 淺灰底，是 admin tool 常見的 selection pattern，比用色塊更克制。

**Filter chips：All / High / Medium / Mine** — `Mine` 這個 chip 是 B6 人工審核流程提到的「指定審核者」概念的前置視覺 — 即使現在還不做 assignment，先在 UI 上留出這個心智模型的入口，未來接上不會打破體驗。

**Detail header 的琥珀色 icon + 紅色 risk pill 是刻意的視覺衝突** — skill 本身是 pending 狀態（琥珀），但它的風險等級是 high（紅）。兩個顏色同時出現是為了讓 reviewer 保持警覺：「這個 skill 還沒落地，但如果落地了會帶紅色標籤」。這種「狀態」和「分類」分離的 colorism，比統一成一個顏色更精準。

**最上方的 summary callout（紅底）是「一句話結論」** — `3 high-severity findings require resolution. All findings originate from scripts/bootstrap.sh.` 這讓 reviewer 在點進 detail 的 0.5 秒內就知道「這個 skill 為什麼在 queue 裡」，不需要往下翻。最後一句「blocked from the public registry until approved, rejected, or returned for changes」則是 reviewer 行動前的最後提醒。

**Tabs 預留了完整的 review context** — Risk findings（當前 on）、SKILL.md（看作者怎麼描述這個 skill）、Bundle contents（完整檔案清單）、Author history（看這個 author 以前發了什麼）。`Author history` 對 reviewer 判斷「這個 author 值得信任嗎」很關鍵 — 一個有 10 個低風險 skill 的 platform-team 跟一個首次投稿的 author 的同樣高風險，處理方式應該不同。subtitle 裡寫「first submission」就是這個訊號。

**每個 finding 獨立一張卡 + 紅色 header 底 + rule ID 暴露** — 這是把「自動掃描結果」轉成「reviewer 可操作的 unit」。每條 finding 有：嚴重度 badge（`HIGH` 等寬字，像 SAST 工具常見）、一句話標題、檔案與行號、rule ID（如 `rule:rce.curl-pipe-bash`）、只 highlight 相關 3 行代碼而不是整個檔案。rule ID 讓 reviewer 可以針對同一條 rule 去看歷史判例，長遠來說是 review 一致性的關鍵。

**每個 finding 有 3 個 per-finding action** — `Accept risk`（綠色，這條沒問題）、`Ask author to fix`（中性）、`Reject based on this`（紅色）。per-finding 的決策比整包 approve/reject 更精細 —「curl|bash 我覺得 OK 因為是內部域名，但 ~/.aws/credentials 必須刪掉」是很正常的 review 情境。第 4 條 INFO 沒有 action，因為 info 級別不需要決策。

**底部 Decision area 是 3 個 action + 1 個 note** — Reject（紅邊）、Request changes（中性，給作者改）、Approve & publish（黑底 + beam 邊框，是 primary action）。中間的 textarea 預填了一段 reviewer note，示範「這個欄位要寫什麼」— reviewer 可以 per-finding accept 掉其中一個、要求改另外兩個，note 裡寫明理由。整段 note 會跟 decision 一起進 event log，對應 Skills Hub 的 ES 架構。

**`Your decision will be recorded in domain_events` 的 footer hint** — 對應前面幾頁的慣例，把架構的透明度一路貫穿到 admin tool。對 reviewer 來說，這也是一種 accountability reminder：「你的判斷會被永久記錄」。

**右下角 approve 按鈕用 beam 邊框是故意的** — 一般 admin tool 的 approve 不應該是唯一的 primary action（因為 reject / request changes 同樣重要），但這裡 approve 代表「這個 skill 會立刻上架」，是影響面最大的動作。beam 邊框的視覺重量剛好平衡了「不做事（保持 pending）最安全」的 default 心理。

整個視圖扣合 PRD Backlog B6「人工審核流程 · 高風險 skill 指定審核者 approve/reject」的完整場景。雖然 MVP 把審核流程放進 backlog，但前面 State B 高風險發佈流程已經把 skill 送進這個佇列，所以整個 demo 在動線上是閉環的。

到這裡 Skills Hub 的核心 UI series 走得差不多完整了：發現（首頁 + 語意搜尋）→ 理解（詳情頁）→ 發佈（happy + fail + high-risk 三態）→ 管理（我的技能）→ 治理（analytics + review）。如果還想繼續延伸，幾個方向：
==============================
這是 Skills Hub 的 Landing Page，對應 PRD 裡「個人開發者」和「技能創作者」兩個 target user 第一次到訪的情境。Landing Page 的挑戰跟 app 內部頁不同 — 使用者還沒登入、對平台一無所知，整頁得在 30 秒內回答三個問題：這是什麼 / 為什麼我需要 / 我怎麼開始。

幾個設計決定：

**Hero 雙欄而不是傳統置中大字** — 左邊文案 + CTA，右邊四張傾斜的 skill card 預覽。這種 split hero 比純置中標題更能「展示產品樣子」— 第一眼看到 Skills Hub 長什麼樣，而不是讀廣告詞。四張卡片用了輕微的 tilt（-0.8°、+0.5°、-0.3°、0°）讓它感覺像「從真實 registry 隨手撈出來的 sample」而不是精心排版的 marketing graphic。中間那張 featured skill（`k8s-deployment`）套 beam 邊框，也讓整個 Landing Page 第一個動態元素就暗示「這裡面的東西是活的」。

**Headline 只有一個局部 gradient 字** — `actually trust` 用紫→藍→綠三色漸層，其他都是實色。這是整頁唯一一個 gradient 用法，因為 visualize design system 明確禁止 gradients。但作為行銷用的 Landing Page，headline 這一個字是允許的例外，並且跟 Skills Hub 的功能性顏色（紫=AI、藍=info、綠=success）呼應，不是任意的漸層。背景的 radial glow 也刻意壓到 opacity 0.06-0.08，讓它「存在但不搶戲」。

**Eyebrow 有個 live pulse dot + 動態數字** — `New · 247 skills across 12 teams` 這行小字加了脈動綠點，立刻告訴訪客「這不是個空殼平台，現在就有東西」。247 是我們在其他頁面一路用的數字，整個 UI series 數字一致。

**CTA 雙軌設計對齊 PRD 的兩個主要 target user** — 主按鈕「Browse the registry」（消費者，黑底 + beam 邊框）+ 次按鈕「Publish your first skill」（作者，白底 outline）。沒用傳統的 Sign up → Sign in 因為這是企業內部平台，使用者進來要做的是「找 skill」或「發 skill」而不是註冊。把動作層級定義成「我想用」vs「我想發」是 Landing Page 最重要的 IA 決策。

**Trust row 三點同時回答三個 objection** — 「Auto risk-scored」（會不會有安全疑慮？）、「SSO via company OAuth」（要不要另外建帳號？）、「Open standard · no lock-in」（會不會被綁定？）。這三條對應 PRD 的 D4（OAuth 整合）、D5（分級制度）、D2（agentskills.io 標準）三個關鍵決策。

**Stats strip 用 divider 而不是 card** — 4 個數字用細線分隔而不是各自包 card，這種 treatment 比 card grid 更輕、更有 editorial 感。數字選的是 Analytics 儀表板裡呈現過的同一組 metric（247 / 38,412 / 62 / Low），讓 Landing Page 說的話跟後台儀表板一致 — 不是 marketing fiction。`Avg risk score: Low` 用文字而不是百分比是刻意的，後面加一行 `68% pass auto-scan` 解釋 — 讓「安全」這個訴求既有 headline 也有 evidence。

**Dual paths 是 Landing Page 的核心 section** — 兩張等寬大 card，分別給 Consumer 和 Publisher。每張 card 有 icon、eyebrow（「I want to use skills」/ 「I want to share what I've built」用第一人稱）、standfirst、三步驟流程、專屬 CTA。三步驟刻意寫得很短（每句一個強調詞 + 一句話），目的是讓訪客快速掃描就能判斷「這流程適合我嗎」。Consumer 的 step 3 還嵌了一段 inline code `~/.claude/skills/` — 對工程使用者來說這是熟悉的信號，暗示「這是開發者工具，不是給產品經理用的花瓶」。

**How it works 用事件流 SVG 而不是抽象流程圖** — 5 個盒子 + 4 條箭頭，每條箭頭上標的是真實事件名稱（`SkillCreated`、`SkillRiskAssessed`、`SkillVersionPublished`），底部有虛線連到 `Analytics projection`。對企業平台團隊的買方（決策者）來說，這張圖回答「我能不能做完整審計」、「資料流清不清楚」— 這是技術可信度的核心訊號。下方的 caption `domain_events → projections → read models` 直接暴露架構語彙，繼續貫穿整個 UI series 的 technical transparency 原則。顏色用 purple（upload）→ amber（scan）→ teal（publish）→ blue（live）不是隨機 — 每個顏色對應該階段的 UI 主色系。

**Value props 三欄對齊 PRD 的 D5 / P5 / P6** — Risk-tiered、Semantic search、Full-lifecycle analytics。Skills Hub 不做「功能列表 20 條」那種疲勞文案，只挑三個最關鍵的差異化訴求，每個配 72 字以內的說明。

**Compatibility strip 列出 5 個主要 agent + 「+ 25 more」** — 對應 PRD 的 D2 決策（「agentskills.io 標準·30+ 工具採用」）。不用 logo（因為沒有授權）而用文字 + 極淺灰 dot — 既有 brand 語彙又不會因為缺 logo 看起來很陽春。

**最終 CTA 區塊 + footer 收尾** — 標題換成更感性的「Start sharing skills like libraries.」— `像共享 library 那樣共享 skills` 這個類比直接對應工程師的 mental model。CTA 重複 hero 的兩個按鈕，是 Landing Page 慣例 — 讀完整頁還想行動的人需要一個「就在這裡」的入口。Footer 只放最精簡的 4 個連結（Docs、API、Changelog、Status），配上一句產品定位 tagline。

這讓整個 UI series 真正完整了 — 從 Landing（未登入）→ 首頁（登入後 browse）→ 詳情 → 搜尋（含語意）→ 發佈（3 態）→ 我的技能 → Admin 審核 → Analytics。一個使用者進 Skills Hub 會走過的所有關鍵 touchpoint 都已經有了具體的視覺語彙，而且共用同一套 design system（beam border 代表 primary action、purple 代表 AI、amber 代表 pending、red 代表 block、green 代表 pass）。
======================================
這是 4 步驟 onboarding 的第 2 步 —「Your workflow」。我選這一步展示而不是第 1 步，因為這步最能體現 onboarding 的核心價值：**系統收集訊號來做個人化**。第 1 步只是 welcome、第 3 步純設定、第 4 步純輸出，第 2 步才是「收訊號 + 即時反饋」都發生的地方。

幾個設計決定：

**Onboarding 刻意拿掉全功能 chrome** — 頂部沒有 Browse / My skills / Analytics 那排 nav links，只留 logo + `Setup` badge（紫色，標示 onboarding context）+ 右邊的 `Step 2 of 4` + Exit。這是 onboarding 慣例：焦點在完成流程，不讓使用者在半途被側邊 nav 帶跑。但又不是完全不能逃 — 右上的 Exit 按鈕永遠在，尊重使用者的 autonomy。「Exit setup →」用箭頭而不是 ×，暗示「離開不是關掉，是回主 app」。

**Progress stepper 的 4 步在定義 onboarding 的 information architecture** — Welcome（必要，第一印象）→ Your workflow（收個人化訊號）→ Connect tools（做一件實際的事）→ Starter pack（立刻兌現價值）。這個順序有心理學依據：先讓使用者感受「被歡迎」→ 收資訊 → 給使用者一件小任務 → 立刻給回饋，最後結束時每個使用者都有「我的 Skills Hub」的所有權感。Step 1 已完成（綠色 + checkmark），Step 2 active（黑底），Step 3/4 灰色等待 — 視覺進度跟心智進度一致。

**Eyebrow + H1 + sub 三層文案各司其職** — 「Hi Mike, let's personalize」前面的紫色 `✦` 符號貫穿整個 Skills Hub 的 AI/個人化視覺語彙（語意搜尋頁、landing page 也用了同一個 serif italic sparkle）。H1 `Tell us about your workflow` 使用動詞主導的口吻，不寫成「Personalization settings」那種後台式語言。sub 寫明「This takes about 30 seconds」和「change it anytime from settings」— 前者降低使用者的時間焦慮，後者消除「選錯會不會很麻煩」的顧慮。這兩個 objection 在 onboarding 流失率裡佔很大比重。

**三個 Q block 用 divider 分隔而不是獨立 card** — 這頁有 3 個問題（intent / tools / interests），如果每個都包 card 會顯得像表單集合而不是對話。改用細線分隔，整頁讀起來像「一段話分三個 section」，壓力小很多。每個 Q head 都有 `label + hint/count`：「Pick one · determines your home page」這種 inline hint 告訴使用者「這個選擇會帶來什麼後果」，比含糊的「Select your role」清楚得多。

**Q1 Role 用 3 張 card 而非 radio buttons** — 每張 card 獨立容納 icon + title + description，讓「選角色」這件事有足夠的視覺重量。選中的 card 有三重訊號：右上角深紫 check circle、整張淺紫底（#F7F6FE）、紫色 border（#AFA9EC）。未選的 card 保留一個空殼 check circle（0.5px 灰邊），這樣 visually 三張 card 的結構是一致的 — selection 不是「新增一個 check」而是「把空的變實心」。這種設計讓 click target 變大（整張卡都可點）、selection feedback 更明顯。

**Q2/Q3 用 chips 因為是 multi-select + 低決策負擔** — 角色是 one-of-many（大決定，用 card），工具和興趣是 many-of-many（小決定，用 chip）。chip 的選中狀態用同一套紫色系統 — 淺紫底 + 深紫 text + 小 check circle。每個 Q head 右邊有「2 of 6 selected」的 count，幫助使用者 calibrate「我選對了幾個」— 這個計數在 multi-select UI 裡意外地重要，沒有的話使用者容易覺得「我應該多選一點嗎？」

**Q3 我選了 DevOps / Testing / Security** — 刻意不是 3 個最熱門的類別，因為前面 trust row 有談「risk-scored」是賣點。讓這個 demo user 關心 Security 是為了暗示：Skills Hub 能吸引的正是關心安全的嚴肅開發者。`Documentation`、`Frontend`、`Data & ETL`、`AI & ML` 都沒選 — 代表這個使用者的 focus 偏基礎設施。

**Live preview 卡片是這個設計最重要的互動心理學細節** — 「Based on your picks · preview」+ 一句話解釋 + 3 個 sample skills 的 inline pill + `+ 4 more`。這個 preview 的作用不是展示結果，是**讓使用者感受到系統真的在聽他們**。每次使用者改選擇，這個卡片會即時更新「7 skills」的數字、換 sample skill。這個即時反饋是 onboarding 流失率的關鍵 — 沒有這個，使用者填到第 3 題時會懷疑「這問卷有用嗎」而放棄。預覽用的紫色系 + `✦` sparkle 呼應整個 AI 個人化的視覺語彙。

**三個 nav 按鈕層級：Back / Skip personalization / Continue** — Back 是低調的 ghost button（可以回去改），Continue 是主要 CTA（黑底 + beam 邊框，跟整個 UI series 一致）。中間的 `Skip personalization` 是最有意思的一個 — 刻意做成帶下劃線的文字連結而不是 button，因為不希望它搶戲但必須存在。對討厭 onboarding 的使用者來說，這個 escape hatch 是尊重；對會完成 onboarding 的使用者來說，它的存在讓「Continue」這個動作更自願。

**Continue 按鈕寫「Continue to tools」而不是「Next」** — 預告下一步是什麼，降低使用者的不確定感。每一步的 CTA 都應該寫清楚目的地（Continue to tools → Continue to starter pack → Enter Skills Hub），而不是機械的 Next。

**整個 wizard 在視覺上是「app 的小 detour」而不是「獨立 landing page」** — 沿用了所有核心元件：logo、色票、beam border、chip 語法、progress stepper 的寫法（跟發佈流程那步的 stepper 用同一個模板）。這讓使用者 onboarding 完進到主 app 時不會有視覺斷裂 — 他們已經認識了這套設計語言。
==================================
這四個空狀態故意用四種不同的情緒語調，因為空狀態不是「沒內容的頁面」— 它是一個**獨立的 UX 問題**，每一種「為什麼這頁是空的」需要不同的處理。同時四張圖共用相同的 design system（mini app chrome、card 結構、beam CTA、字級），讓 tone 的差異純粹由文案 + 視覺焦點承擔。

每個狀態的設計決定：

**#1 Fresh deployment — Seeding** 是整個產品生命週期最關鍵的空狀態。PRD 裡 target user 的「個人開發者」需要找到好用 skill 才會愛上 Skills Hub，但如果 registry 空的，第一批訪客就會流失。這張圖用了三個策略：（a）禁用灰階的搜尋框，暗示「搜尋現在沒意義」；（b）紫色 eyebrow pill `0 skills · 0 publishers` 把「空」變成事實陳述而不是失敗訊號；（c）右側的 4 個 dashed ghost cards 是最關鍵的設計 — 它們不是裝飾，是告訴使用者「這裡本來會有什麼」，用 placeholder 預演產品的成熟狀態。文案「Your registry is waiting to be seeded」用 seed 這個農業隱喻，對工程師來說也熟悉（database seeding）。CTA「Publish the first skill」刻意用「the first」而不是「a」— 暗示 agency 跟 reward（你會是第一個）。

**#2 New author — Invitational** 是個人層級的空狀態。跟 #1 不同的是：平台有 247 個 skills，但「我」沒發過。所以這頁不是勸使用者「seed the platform」，而是勸使用者「join the community」。視覺焦點是 4 步驟的 horizontal flow — dashed icon 串起 Zip → Auto-scan → Publish → Track。這個 flow 借用了 onboarding step 2 preview 裡的「預演未來」手法：讓使用者看到發佈後會長什麼樣、有幾步、每步做什麼。用 dashed icon 而不是 solid 是因為「這是你還沒走過的路」。文案「The whole round-trip takes under a minute if your SKILL.md is ready」同時降低時間焦慮 + 設定合理期待（需要你先有 SKILL.md）。次要 CTA「Get the starter template」是給「我想發但不知道怎麼開始」的使用者的逃生口。

**#3 No search results — Redirecting** 展示了語意搜尋有的特有挑戰 — 當純關鍵字搜尋 0 結果可以很制式，但**語意搜尋 0 結果**是認知失敗，系統必須承擔責任幫使用者找出路。這張圖最關鍵的設計：頂部保留使用者剛輸入的 query、下方 echo 再出現一次（「Query · "crypto wallet integration for embedded devices"」），讓使用者看到「我說的話系統聽到了」— 消除「我是不是按錯鍵」的疑惑。`reaches above a 0.32 similarity score` 暴露分數，對工程使用者來說這是「為什麼沒結果」的技術答案。右側 4 條 suggestion 按重要性排序：（a）近似 query 重試（最可能有用）、（b）fallback 到 browse（最保守）、（c）request（社群信號，轉化為 backlog）、（d）invite to publish（轉化使用者變 publisher）。每條有 hint 解釋「為什麼是這個」，不只是連結而是半張卡。「Request this from your team · 3 votes so far」的 `3 votes` 是社群驗證 — 暗示使用者不是唯一有這需求的人。

**#4 Review queue all clear — Celebratory** 是這整個集合裡最違反直覺的空狀態。前三個是「幫我解決」，這個是「沒事需要做，放心」。多數空狀態教學會說「empty state 要有 CTA」— 這裡刻意打破，沒有主要 CTA。因為 admin 的空佇列是好消息，硬推使用者去做什麼會傳達錯誤訊號。視覺焦點是一個大綠勾（`#E1F5EE` 底 + `#085041` 前景），外圍一圈淡綠 halo 增加「舒緩」感。下方 3 個 mini stat card 把「什麼都沒有」轉成「這週做了什麼」—「Approved 12 (↑ 3)、Rejected 2、Avg turnaround 1h 36m」。這讓 admin 讀完這頁的感受不是「閒閒沒事」而是「團隊運作得很好」。只保留一個低調連結「View full audit log」— 如果真的想看歷史還是有路。

整個集合的設計哲學：

- **空狀態的 tone 由產品語境決定，不是統一的 visual template**。Fresh deployment 要激勵、new author 要邀請、no results 要導流、all-clear 要安撫。硬套同一套「大插圖 + headline + CTA」到四種情境上只會讓三種情境失真。

- **每個空狀態都應該回答「使用者下一步去哪」**，但「去哪」的答案可以是「不用去任何地方」（#4）。不要為了填 CTA 而硬塞 CTA。

- **訊息設計的層級是 headline → sub → CTA**，但每一層的 voice 要一致。如果 headline 用「waiting to be seeded」這種農業隱喻，sub 就接「kick off the flywheel」延續 momentum 隱喻；如果 headline 是「all clear」，sub 就用「when something triggers findings, it'll land here」延續 passive 冷靜的語調。

- **共用的 design system 是隱形的 glue**。四張圖都用了 beam border 的 primary CTA（除了 #4 沒有 CTA）、同樣的 `.es-page-head`、同樣的紫色系作為「你的下一步」的視覺標籤、同樣的 `#E1F5EE` 綠作為 pass/清除的訊號。使用者從 #1 掃到 #4 不會覺得「這四頁是不同產品」。

這應該是整個 UI series 的一個很好的收尾 — 把產品從全空（#1）推到「一切都好」（#4），整個生命週期的 tone 在這裡閉環。
=========================
這是 Docs 體系的入口頁「Write your first skill」— 不是純規範參考，而是一個帶著作者走完一圈的 walkthrough。空狀態 #1 和 #2、Landing Page、Onboarding 都有連結指到這裡，所以這頁是「想發 skill 但不知道怎麼開始」的使用者的終點。

幾個設計決定：

**Docs 要感覺是 Skills Hub 一等公民而不是外掛文件網站** — 頂部保留了主 app 的 logo + 4 個 nav（Browse / My skills / Analytics / Docs），Docs 現在是 active。右上的 search docs 帶了 `⌘K` 快捷鍵 — 對工程使用者來說這是熟悉的信號（Algolia DocSearch、Linear、Vercel docs 都用 ⌘K），暗示這個 docs 有 proper 搜尋而不是花瓶。整個視覺系統（border-radius、color palette、beam CTA）跟其他頁面完全共用，讓「讀 docs」和「用 app」在視覺上連貫，不需要心智切換。

**左側 nav 的 IA 反映 docs 的使用者旅程** — 四組：Getting started（初學）/ Reference（查詢）/ Publishing（進階動作）/ API & webhooks（整合）。從上到下是「learning → doing → extending」的漸進式深度。`Your first skill` active，用了淡紫底（`#EEEDFE`）而不是左邊條，因為 docs 的 selection 要比 app 主頁面更軟。Group label 用極小字 + 字母大寫間距拉開，這是 Tailwind / Stripe docs 的經典 treatment。

**Meta row 放 3 個坐標而不是裝飾資訊** — `Updated 2 weeks ago · 5 min read · Based on agentskills.io v1.2`。每個都回答使用者讀 docs 前的一個問題：「這是不是過時的？」「要花多久？」「對應哪個版本的規範？」最後一個尤其重要 — 因為 Skills Hub 跟 agentskills.io 是 downstream 關係（D2 決策），明確標示版本是對使用者的誠實。右邊的「Edit on GitHub」鉛筆 icon 是 open-source docs 的標配訊號，讓使用者知道「這份 docs 歡迎 PR 修改」。

**Intro paragraph 用稍大的字級（14.5px）+ 較寬的行高（1.7）** — 比 body（14px/1.7）稍微站出來，是 docs 的「這段是定位，你可以從這裡判斷要不要繼續讀」的慣例。Intro 裡直接嵌入 `agentskills.io` 的超連結，強調「Skills Hub 不是鎖死的產品，是 open standard 的 implementation」— 呼應 Landing Page 的 `Open standard · no lock-in` 承諾。

**Minimum viable skill 的範例用真實的內容而不是佔位符** — `date-formatter` 是一個使用者馬上能理解的場景（日期轉換是所有開發者都懂的小工具），description 刻意寫得符合 PRD 規範（「Convert between common date formats. Use when the user pastes...」— 有明確 trigger condition）。代碼 block 裡高亮了 `date-formatter`（skill name）給視覺上的 focal point。底下的 info callout 明確指出「Only name and description are required」+ 解釋「this auto-publishes at low risk because there's no scripts/ folder」— 把 SBE 規範直接連到範例。

**Bundle tree 用 badge 標示每個 folder 的角色** — `required`（紫）/ `triggers scan`（琥珀）/ `optional`（灰）。這比純文字解釋更快 parse。`triggers scan` 這個 badge 特別重要：它是讓作者**知道為什麼 scripts/ 不是隨便加的 folder**，在發佈前就建立正確期待，避免「為什麼我的 skill 被標高風險」的困惑。

**Required fields 用「block-as-row」格式而不是傳統 table** — 每個欄位獨立一塊 card，欄位名等寬字體 + 淺灰底（像是 `<code>` 的感覺），右邊 inline 標示 required / type / 長度限制（用 · 分隔點串接）。描述寫在下方。這種佈局比 table 更容易閱讀，因為 680px 寬度塞不下 4 欄表格。`name` 和 `description` 各給一整塊 card 是因為它們是兩個 required field，其他 optional field 壓在一段灰字裡 — 不是每個欄位都值得一塊獨立 block。

**「Writing a description that works」是這整份 docs 的靈魂段** — 這段內容在 PRD 裡其實沒直接寫，但它是 P5 語意搜尋成立的前提：**作者寫什麼 description 直接決定他們的 skill 能不能被找到**。設計上用左綠右紅的 A/B 對比：
- 綠卡：真實可用的描述 + 解釋為什麼這種寫法有效（concrete verbs、domain nouns、trigger condition）
- 紅卡：marketing blurb 反例 + 解釋為什麼 embedding 匹配不到（adjectives without content）

用等寬字呈現描述內容本身，讓作者知道「這段話最後會變成 indexed string」— 這個細節在鼓勵工程師以「系統輸入」的角度來寫 description 而不是 marketing 文案。底下的 why 區塊用 sans 字體 + 分隔線，跟 compare body 的 mono 視覺對比，明確區分「這是範例」vs「這是原理」。

**Risk tiers 用「big pill + 段落」而不是表格** — 三個 tier（Low/Med/High）用跟其他頁面一致的 pill 配色。每段描述先黑字 bold 講結果（`Publishes immediately.` / `Publishes with warning.` / `Blocked until reviewer approves.`），再灰字講條件。最後 High 那段列出實際觸發的 pattern（`rm -rf`、`curl | bash`、`~/.ssh`、`~/.aws`）— 跟發佈流程 State B 和 Admin 審核頁的 finding 用詞完全一致，讓整個 UI series 的 risk detection vocabulary 統一。

**琥珀 callout「High-risk isn't a rejection」** — 這句話的意義超過它的字數。對還沒發過 skill 的作者，看到「High risk → blocked」會怕；這個 callout 立刻補上「blocked 不等於 rejected，可以 approved with note / asked to fix / rejected」三種走向。這種預先消除恐懼的寫法在 docs 裡很重要 — 因為讀者讀到這裡的瞬間可能會猶豫要不要繼續，而這個 callout 直接把遲疑化解。

**Final CTA block 是 docs 到 publish 流程的橋** — 用 `Ready to publish?` 當問句標題（對話感），sub 一句話重新確認「作者已經準備好了」（「You've got a bundle, the frontmatter is clean, and you know what the scanner checks」— 這是對前面三個 section 的 recap）。左邊次要連結 `See more examples` 給還想多看的人，右邊 beam border 的 `Upload your bundle →` 直接跳去發佈流程。這是 Marketing Funnel 裡「讀完 docs → 行動」的關鍵轉化點。

**底部 prev/next 頁面導覽** — `Overview ← → SKILL.md spec`。這是 docs 的 reading spine，讓使用者知道 docs 有結構、可以線性讀下去。Prev 是比 `Your first skill` 更基礎的 Overview，Next 是更深入的 SKILL.md spec（完整規範）— 符合「learn → reference」的 docs 閱讀曲線。

整個 docs 頁其實做了一件比純 reference 更重要的事：把 Skills Hub 產品的 3 個核心機制 — frontmatter validation、semantic search indexing、risk tier classification — 在一頁之內讓作者從「不知道」變成「有心智模型」。這對 PRD 的 P2-P5 的成功是 prerequisite：作者如果不懂這些，發出來的 skill 很可能失敗或被卡。

到這裡 Skills Hub UI series 應該是完整的了 — Landing → Onboarding → 首頁（browse）→ 空狀態集合 → 詳情 → 搜尋（semantic）→ 發佈（success/fail/high-risk 三態）→ 我的技能 → Admin 審核 → Analytics → Docs。完整的「發現 → 學習 → 行動 → 管理 → 治理」閉環都覆蓋了。