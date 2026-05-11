# S161: User Input Sanitization — Review / Flag / Request 文字欄位 XSS 防禦

> Spec: S161 | Size: S(6) → Phase 1 XS(3) | Status: 🚧 Phase 1 ship 2026-05-12（review.content + PlainTextDeserializer 基礎設施完成；其餘 3 DTO + markdown allowlist + V20 backfill 拆 S161b/c）
> Date: 2026-05-08
> Origin: deployment audit 2026-05-08（LAB）— 實測 `POST /api/v1/skills/{id}/reviews body={rating:5, content:'<script>alert("xss")</script>'}` 直接 201 created；`GET /reviews` 回的 `content` 仍是 raw payload。React frontend 預設 escape 擋住 inline render，但 backend 完全不過濾 — 任何下游改 markdown render / CLI / 外部 API consumer 立即被攻擊。

---

## 1. Goal

在 backend write side 對 user-submitted 文字欄位做 **safe-by-default sanitization**，把 HTML / `<script>` / event handlers 等危險 markup 在儲存前清理。

**為什麼重要：**
- React `{value}` escape 是「最後防線」不是 strategy。任何一處未來改用 `dangerouslySetInnerHTML` / `react-markdown` 帶 `rehypeRaw` / iframe sandbox bypass → XSS 立刻成立
- 不只 frontend：CLI（`skills-hub list reviews`）、Slack bot integration（vision）、email 通知 — 任一 consumer 不 escape 就被攻擊
- Stored XSS 是 OWASP Top 10 #3（Injection），平台核心 attack surface
- 即便存 raw 安全（純 React），DB 內躺著 attack payload 也是 audit / compliance smell

**非目標：**
- 不改 SKILL.md frontmatter / markdown 內容 sanitize（屬 risk-scanner-scope，已有獨立 spec）
- 不改 frontend render 邏輯（仍走 React escape；但 spec ship 後 stored 已乾淨，frontend 額外 dangerouslySetInnerHTML 不會炸）
- 不做 CSP nonce-based strict（屬 S160 Phase 2）

---

## 2. Approach

### 2.1 受影響欄位 audit

對 `git grep -E '@RequestBody|@JsonProperty.*String'` 列表，sweep 所有 user-submitted 文字欄位：

| Endpoint | DTO 欄位 | 預期內容 | 處理方式 |
|----------|----------|---------|----------|
| POST /api/v1/skills/{id}/reviews | `content: String` | 一般文字 | strip HTML（only plain text） |
| POST /api/v1/skills/{id}/flags | `detail: String` | 一般文字 | strip HTML |
| POST /api/v1/requests | `title: String` | 短標題 | strip HTML（plain text） |
| POST /api/v1/requests | `description: String` | 多行 markdown？ | **保留 markdown safe subset**（OWASP allowlist；無 inline JS / 事件 handler） |
| POST /api/v1/skills（publish） | `description: String`（從 SKILL.md frontmatter 解析） | YAML 字串 | trust source；SKILL.md scanner 已掃；不重複 |
| POST /api/v1/collections | `name`, `description`: String | 短文 | strip HTML |
| 其他 author / handle / etc | 各種短欄位 | 純文字 | strip HTML |

選擇：
- **預設 strip HTML**（plain text 欄位）
- **仅 request.description 走 markdown allowlist**（這是唯一預期 user-formatted text）

### 2.2 實作路徑

**Approach A**: 在 service layer write 前手動 sanitize 各欄位
**Approach B**: 在 DTO record 用 `@SanitizedHtml` / `@PlainText` annotation + `@Validated`
**Approach C**: Jackson custom Deserializer 全 String 自動 sanitize

**選 B**：
- 合 Spring Bean Validation（@Validated）已有的 `@NotBlank` 等 pattern
- 比 C 細粒度（不是全平台 String 都 sanitize；某些 field 需保留 markdown）
- 比 A 集中（不散落在每個 service）

實作：

```java
// 自訂 annotation
@Target(FIELD)
@Retention(RUNTIME)
@Constraint(validatedBy = PlainTextValidator.class)
public @interface PlainText {
    String message() default "must not contain HTML or script markup";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

// Validator
public class PlainTextValidator implements ConstraintValidator<PlainText, String> {
    private static final PolicyFactory STRIP_ALL = new HtmlPolicyBuilder().toFactory();
    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        if (value == null) return true;  // 由 @NotNull 處理
        String stripped = STRIP_ALL.sanitize(value);
        return stripped.equals(value);  // 完全相等才視為 plain text
    }
}
```

**對 markdown 欄位（request.description）：** 用 OWASP `HtmlPolicyBuilder.allowMarkdown()` 風格 allowlist：

```java
public static final PolicyFactory MARKDOWN_SAFE = new HtmlPolicyBuilder()
    .allowElements("p", "br", "strong", "em", "ul", "ol", "li",
                   "code", "pre", "blockquote", "a", "h1", "h2", "h3")
    .allowAttributes("href").onElements("a")
    .allowStandardUrlProtocols()  // http/https/mailto
    .toFactory();
```

策略：
- 不 throw error；**寫前 sanitize**（`description = MARKDOWN_SAFE.sanitize(input)`）。
- 違規 markup 被 silently strip；不破 user 體驗（無人故意輸 `<script>`）
- 自動化 test 確保 `<script>` 等 strip 乾淨

DTO annotation:

```java
record CreateReviewBody(
    @Min(1) @Max(5) int rating,
    @NotBlank @Size(max = 2000) @PlainText String content
) {}

record CreateRequestBody(
    @NotBlank @Size(max = 200) @PlainText String title,
    @NotBlank @Size(max = 5000) @MarkdownSafe String description
) {}
```

`@PlainText` 走 reject pattern（含 HTML 直接 400）；`@MarkdownSafe` 走 sanitize pattern（service layer 在 publish 前 strip）。

選 reject vs sanitize：
- `@PlainText` reject：對純文字欄位，user 寫了 `<` 應該以原文存，所以 hint user「請去除 HTML」即可。實作上更簡單。
- `@MarkdownSafe` sanitize：對 markdown 欄位，silently strip 危險 tag 但保留正常 markdown，UX 不破。

但 @PlainText 嚴格也有副作用：user 寫 `<3` 心情符號被擋。考量：
- 平台對象是工程師 user，少用 emoji-as-html
- 寧嚴勿鬆；改成 sanitize-and-strip 也可，trade-off 留 implementer 決

**MVP 選 strip pattern（不 throw）**：簡單、無 user friction、攻擊者送進來也不會壞。

```java
@Target(FIELD)
@Retention(RUNTIME)
public @interface PlainText {}

// 配合自訂 Jackson StringDeserializer
public class PlainTextDeserializer extends JsonDeserializer<String> {
    private static final PolicyFactory STRIP = new HtmlPolicyBuilder().toFactory();
    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        return STRIP.sanitize(p.getValueAsString());
    }
}
```

實際選擇留 implementer。本 spec 重點是「ship sanitization；不再 stored XSS payload」。

### 2.3 Library 選擇

**Java**: `org.owasp.html:owasp-java-html-sanitizer`（OWASP 維護；Spring 生態驗證過）

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20240325.1")
}
```

GraalVM 相容性：OWASP sanitizer 是 pure Java + 反射輕量；可能需要加進 reflection metadata（per S148 同 pattern）。Implementer 跑 native build 驗證。

### 2.4 既有 stored data backfill

migration `V20__sanitize_existing_user_text.sql`（手動 SQL）：

```sql
-- review.content 與 flags.detail 用 PostgreSQL regex 移除明顯 dangerous tag
UPDATE reviews SET content = regexp_replace(content, '<[^>]+>', '', 'gi')
  WHERE content ~ '<[^>]+>';
UPDATE flags SET detail = regexp_replace(detail, '<[^>]+>', '', 'gi')
  WHERE detail ~ '<[^>]+>';
UPDATE requests SET title = regexp_replace(title, '<[^>]+>', '', 'gi')
  WHERE title ~ '<[^>]+>';
-- description 走 application-level rerun（複雜 markdown sanitize 不適合 SQL regex）
```

`requests.description` 因為要保留 markdown，純 SQL regex 會破合法 `<` 字符（如 markdown `<a href`）。對既存資料採「app-level rerun」：跑 boot-time job 取出全部 description，過 MARKDOWN_SAFE policy 寫回。MVP 範圍內若 description 都已 plain（demo data），可跳過此步。

---

## 3. Acceptance Criteria

```
AC-1: Review content 含 <script> 被 strip
  Given POST /api/v1/skills/{id}/reviews body={rating:5, content:'<script>alert(1)</script>hello'}
  When backend 寫入
  Then DB 存的 content = 'hello'（純文字）
  And GET /reviews 回的 content 不含 <script>

AC-2: Review content 含 <img onerror> 被 strip
  Given content='<img src=x onerror=alert(1)>'
  When 寫入
  Then DB 存空字串或 'src=x'（依 sanitizer 細節）
  And response 不含 onerror

AC-3: Flag detail 同 sanitization
  Given POST flags body={type:'..', detail:'<script>x</script>plain'}
  When 寫入
  Then DB 存 'plain'

AC-4: Request title plain text 處理
  Given POST requests body={title:'<b>bold</b>標題', description:'...'}
  When 寫入
  Then DB title = 'bold標題'（HTML strip）

AC-5: Request description 保留 markdown safe subset
  Given description='<p>hello</p><script>x</script>**bold**\n[link](https://example.com)'
  When 寫入
  Then DB description 含 <p>hello</p>（保留）+ **bold** + [link]
  And 不含 <script>

AC-6: javascript: URL 被擋
  Given description 含 [click](javascript:alert(1))
  When 寫入
  Then sanitizer strip 該 link（或保留文字無 href）

AC-7: 既存 stored XSS payload 被 backfill 清乾淨
  Given V20 migration 跑完
  When SELECT content FROM reviews WHERE content LIKE '%<script%'
  Then 0 rows

AC-8: GraalVM native image 支援 OWASP sanitizer
  Given native build 後 deploy
  When 觸發 sanitize 路徑（任一 review create）
  Then 不出 NoSuchMethodException / ReflectionError
  And 行為與 JVM 模式一致
```

驗證指令：
- `cd backend && ./gradlew test`（per qa-strategy.md；新增 `InputSanitizationTest`）
- 手動 LAB：`curl -X POST .../reviews -d '{"rating":5,"content":"<script>x</script>plain"}'` → GET 該 review 回 plain only

---

## 4. Files to Change

| 檔案 | 變動 |
|------|------|
| `backend/build.gradle.kts` | 加 `owasp-java-html-sanitizer` 依賴 |
| `backend/src/main/java/.../shared/api/sanitize/PlainText.java` | 新增 annotation + validator/deserializer |
| `backend/src/main/java/.../shared/api/sanitize/MarkdownSafe.java` | 新增 — markdown allowlist |
| `backend/src/main/java/.../shared/api/sanitize/SanitizerPolicies.java` | 新增 — 集中 PolicyFactory beans |
| `backend/src/main/java/.../skill/api/CreateReviewBody.java` | 加 `@PlainText` |
| `backend/src/main/java/.../skill/api/CreateFlagBody.java` | 加 `@PlainText` |
| `backend/src/main/java/.../request/api/CreateRequestBody.java` | title `@PlainText`，description `@MarkdownSafe` |
| `backend/src/main/java/.../collection/api/CreateCollectionBody.java` | name `@PlainText`, description `@MarkdownSafe` |
| `backend/src/main/resources/db/migration/V20__sanitize_existing_user_text.sql` | 新增 — backfill regex strip |
| `backend/src/main/resources/META-INF/native-image/reflect-config.json`（or AOT hint）| OWASP sanitizer reflection metadata |
| **Tests** | `InputSanitizationTest`、各 endpoint test 新增 XSS payload case |

---

## 5. Test Plan

### 5.1 自動化（gradlew test）

```java
@Test @DisplayName("AC-1: <script> stripped from review.content")
void reviewScriptStripped() throws Exception {
    var json = """
        {"rating":5,"content":"<script>alert(1)</script>hello"}
        """;
    mvc.perform(post("/api/v1/skills/{id}/reviews", skillId)
            .contentType(APPLICATION_JSON).content(json))
        .andExpect(status().isCreated());
    
    var stored = reviewRepo.findFirstBySkillId(skillId).getContent();
    assertThat(stored).isEqualTo("hello");
    assertThat(stored).doesNotContain("<script");
}

@Test @DisplayName("AC-5: markdown safe subset preserved")
void requestDescriptionMarkdownPreserved() throws Exception {
    var json = """
        {"title":"x","description":"<p>hi</p><script>x</script>**bold**"}
        """;
    var id = postRequest(json);
    var stored = requestRepo.findById(id).getDescription();
    assertThat(stored).contains("<p>hi</p>");
    assertThat(stored).contains("**bold**");
    assertThat(stored).doesNotContain("<script");
}
```

### 5.2 Native build 驗證（GraalVM）

deploy 後跑 1 筆 review create，check log 無 reflection error。

### 5.3 手動 LAB

deploy 後：
- [ ] curl XSS payload 進 review → DB 清乾淨
- [ ] curl javascript: URL → strip
- [ ] 既存 review 仍可讀
- [ ] markdown render（若 frontend 已支援）顯示正確 bold / link

---

## 6. 風險與緩解

| 風險 | 緩解 |
|------|------|
| OWASP sanitizer 在 GraalVM native image fail | 預先補 reflection-config；LAB 部署後跑 sanity check |
| 過度 strip 破 user 內容（如 `<3`） | 用 OWASP HtmlPolicyBuilder 預設 — 它識別「真 tag」vs「字符 `<` 不是 tag」；會保留 `<3`、`a < b` 等非 tag 用法 |
| Markdown allowlist 誤 strip 合法 markdown | unit test 涵蓋 `# heading`、`[link]`、`**bold**`、`<inline>code</inline>`、code block 等 — 確保不破 |
| Backfill SQL regex 對 description（含 markdown）危險 | 拆兩 path：plain text 欄位（review/flag/title）走 SQL regex；markdown 欄位（description）走 app-level Java 跑 |
| 既有 stored XSS payload（前 audit 寫的）未被掃 | V20 migration backfill 時順手；spec implementer 跑前 query 看數量 |

---

## 6.6 Phase 2 結果（2026-05-12）— S161b 部分套用

### Ship 範圍 — 3 個 DTO 對齊 PlainTextDeserializer

| AC | DTO 欄位 | 狀態 |
|---|---|---|
| AC-3 | `FlagController.CreateFlagRequest.description` | ✅ PASS — `<script>` strip + 繁中保留 |
| AC-4 | `CollectionCommandController.CreateCollectionBody.name + description` | ✅ PASS — `<b>` / `<script>` strip |
| AC-4 | `CollectionCommandController.UpdateCollectionBody.name + description` | ✅ PASS — `<img onerror>` / `<style>` 連內容 strip |

### Defer 至 S161b'（Request 模組 + markdown allowlist）

- Request DTO（title plain-text + description markdown safe subset）— 需 OWASP `HtmlPolicyBuilder.allowElements(...)` 拉 dep；獨立 design tick
- V20 backfill SQL → S161c

### 改動檔案

| File | 變動 |
|---|---|
| `backend/.../security/FlagController.java` | `CreateFlagRequest.description` 加 `@JsonDeserialize(using=PlainTextDeserializer.class)` |
| `backend/.../community/CollectionCommandController.java` | `CreateCollectionBody.name+description` + `UpdateCollectionBody.name+description` 同樣加 annotation |
| `backend/.../shared/api/PlainTextDeserializerIntegrationTest.java`（**新檔**）| reflection-based 走 ObjectMapper roundtrip 驗 3 DTOs sanitization wired；setAccessible(true) 對 package-private inner record 取值 |
| `backend/.../security/RiskAssessmentIntegrationTest.java`（**附帶 fix**）| S157 LlmJudge 改 always-on 後 runs 從 7 變 8（多 llm-judge engine emit「disabled」notice）— 對應 assertion 從 7 改 8；非 S161b 本身但 S157 commit 漏 catch 此 integration test |

### 驗證指令

```bash
./gradlew test --tests "*PlainTextDeserializerIntegrationTest"            # 4/4 PASS
./gradlew test --tests "*RiskAssessmentIntegrationTest"                    # S157 regression fix PASS
./gradlew test --tests "io.github.samzhu.skillshub.security.*" --tests "io.github.samzhu.skillshub.community.*"  # 全包 PASS
```

---

## 6.5 Phase 1 結果（2026-05-12）

### Ship 範圍 — review.content 一個 DTO 作為 PoC

| AC | 內容 | 狀態 |
|---|---|---|
| AC-1 | `<script>alert(1)</script>hello` → `hello`（script 連內容一起 strip）| ✅ PASS |
| AC-2 | `<img src=x onerror=alert(1)>` → `""`（self-closing tag + onerror 全 strip）| ✅ PASS |
| 加碼 | `<style>...</style>` 連內容 strip | ✅ PASS |
| 加碼 | inline tag (`<b>bold</b>`) → 留文字 `bold` | ✅ PASS |
| 加碼 | 繁中 / 全形標點 (`！`) / `a < b` 字符不 encode | ✅ PASS |
| 加碼 | null → null（不轉空字串）| ✅ PASS |

### Defer 至 S161b/c

| AC | 內容 | 為何 defer |
|---|---|---|
| AC-3 | flag.detail 同 sanitize | 套用其餘 3 DTO（flag/request/collection）屬同 pattern 機械式套用 — S161b 範疇 |
| AC-4 | request.title plain text | 同上 |
| AC-5 | request.description 保留 markdown safe subset | 需 OWASP markdown allowlist policy；獨立 design — S161b |
| AC-6 | javascript: URL 被擋 | 屬 markdown allowlist 範疇 — S161b |
| AC-7 | 既存 stored XSS payload backfill | V20 Flyway migration — S161c（LAB demo data 無 XSS payload 急迫性低） |
| AC-8 | GraalVM native image 支援 | 純 regex 實作無反射依賴，AOT 預期 OK；下次 deploy 自然驗 |

### 設計轉折 — OWASP library 棄用換 regex

Spec §2.3 原選 `owasp-java-html-sanitizer`，但實測：
- 把 `！` (U+FF01 全形驚嘆號) encode 成 `&#xff01;` → 破繁中 user 內容
- 把 `a < b` 中的 `<` encode 成 `&lt;` → 用 escape-for-safe-render 邏輯處理 strip-for-safe-store 場景

改成簡單 regex 兩 pass：
1. `SCRIPT_OR_STYLE` pattern 先 match 整段 `<script>...</script>` / `<style>...</style>`（含內容）strip
2. `HTML_TAG` pattern match 任何 `<...>` strip

不 encode 字符；繁中 / emoji / 非 tag 用法（如 `a < b`）完全保留。OWASP dep 暫**不**加入 build；待 S161b markdown allowlist（需 OWASP `HtmlPolicyBuilder.allowElements(...)`）時再加。

### 改動檔案

| File | 變動 |
|---|---|
| `backend/.../shared/api/PlainTextDeserializer.java`（**新檔**）| Jackson custom deserializer；regex 兩 pass strip；放 `shared.api` 因 review module 已 allowed 此 NamedInterface |
| `backend/.../review/ReviewController.java` | `CreateReviewRequest.content` 加 `@JsonDeserialize(using=PlainTextDeserializer.class)` |
| `backend/.../shared/api/PlainTextDeserializerTest.java`（**新檔**）| 8 cases — XSS strip / 純文字保留 / 字符 `<` 保留 / null 處理 / script+style 連內容 strip |

### 驗證指令

```bash
./gradlew test --tests "*PlainTextDeserializerTest"                     # 8/8 PASS
./gradlew test --tests "*PlainTextDeserializerTest" --tests "*review.*"  # 18/18 PASS（無 regression）
```

---

## 7. 與其他 spec 關係

- **S160（security headers）** Phase 2 CSP enforce 後，即使 stored XSS 也被 CSP 擋；本 spec 是縱深防禦的另一層（input sanitize）
- **S148（GraalVM AOT reflection）** ship 後，OWASP sanitizer 可 inherit AOT hint pattern；不衝突
- **SKILL.md scanner**（risk-scanner-scope）處理 SKILL.md 內容；本 spec 處理 user 直接 submit 的文字欄位 — 兩者互補
