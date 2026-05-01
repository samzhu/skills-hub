# S073 — `allowed-tools` YAML list interop

> **Status**: shipped — v2.51.0 (M69)
> **Bug ledger**: AH (loop e2e tick 57 Round 15.3)
> **Estimate**: XS / 3 pts

## §1 Problem

`SkillValidator.validateFieldConstraints` 對 `allowed-tools` 只支援 **space-separated string** 形狀；但 **canonical agentskills.io spec + Anthropic 自家 SKILL.md（含 `.claude/skills/handover` / `planning-project` / `deep-research` 等）皆使用 YAML list 形狀**：

```yaml
allowed-tools:
  - Read
  - Glob
  - Bash
```

當 SnakeYAML 解出 `parsed.get("allowed-tools")` 為 `java.util.ArrayList`，現行程式直接 `allowedTools.toString()` → `"[Read, Glob, Bash]"`（含中括號與逗號），再 `split("\\s+")` 切出來的 token 為 `[Read,` / `Glob,` / `Bash]` —— 全部不通過 `ALLOWED_TOOL_TOKEN_REGEX`（regex 開頭要 `[A-Z]`，`[` 不通過）。

**結果**：所有使用 list 形狀（即 canonical 形狀）的 SKILL.md 上傳 → 400 VALIDATION_ERROR。

## §2 Detection

Round 15.3 hand-craft 測試：

```yaml
allowed-tools:
  - Read
  - Edit
  - Bash(git:*)
  - Bash(npm:test)
```

→ 400 「contains invalid token: [Read]」。降到單一條目 `- Read` 也失敗 → 確認非 token 內容問題，是 list `toString()` 整段被當 token 餵 regex。

對照 `.claude/skills/*/SKILL.md` 9 個 Anthropic-style skill，全部用 list 形狀 — 任何使用者複製這些 SKILL.md 上傳都會被拒。Tick 52/55 「9 個 anthropic skills 全 201」之所以過，是因為那 batch（docx / xlsx / pdf / claude-api 等）frontmatter 沒有 `allowed-tools`。

## §3 Acceptance Criteria

| AC | Frontmatter shape | Expected |
|----|-------------------|----------|
| AC-1 | YAML block sequence (`- Read\n- Bash`) | 201 / valid |
| AC-2 | YAML flow sequence (`[Read, Bash]`) | 201 / valid |
| AC-3 | Legacy space-separated string (`"Read Bash"`) | 201 / valid（向後相容） |
| AC-4 | List 含 injection token（`"; rm -rf /"`） | 400 / invalid token: `;` |
| AC-5 | Field 不存在 | 201 / valid |

## §4 Fix

`SkillValidator.validateFieldConstraints` line 124-133：把 token 抽取拆成「形狀分流」：

```java
var allowedTools = parsed.get("allowed-tools");
if (allowedTools != null) {
    List<String> tokens;
    if (allowedTools instanceof List<?> list) {
        // YAML block / flow sequence — SnakeYAML 解出 ArrayList<Object>
        tokens = list.stream().map(String::valueOf).toList();
    } else {
        // Legacy: space-separated string
        var s = allowedTools.toString().trim();
        tokens = s.isBlank() ? List.of() : List.of(s.split("\\s+"));
    }
    for (var token : tokens) {
        if (token.isBlank()) continue;
        if (!ALLOWED_TOOL_TOKEN_REGEX.matcher(token).matches()) {
            errors.add("Field 'allowed-tools' contains invalid token: " + token);
            break;  // 一個違規足以拒收
        }
    }
}
```

**設計意圖**：以 Java type pattern matching 偵測 SnakeYAML 解析結果型別（list vs scalar），不依賴 `toString()`。Token 抽取後沿用既有 `ALLOWED_TOOL_TOKEN_REGEX` 白名單，shell injection 防禦不變。

## §5 Test plan

`SkillValidatorTest.java` 增 3 測試：

1. **AC-1 list block sequence valid** — `allowed-tools:\n  - Read\n  - Bash(git:*)` → valid
2. **AC-2 list flow sequence valid** — `allowed-tools: [Read, Bash]` → valid
3. **AC-4 list with injection rejected** — `allowed-tools:\n  - "Bash(; rm -rf /)"` → invalid

既有 string-style test（AC-13 / AC-14 / AC-15）保持不變，覆蓋 AC-3 backward compat。

## §6 Verification

- `./gradlew test --tests SkillValidatorTest` PASS
- Smoke：重 POST R15.3 zip → 201 + outbox drain + risk badge
- Smoke：嘗試上傳 `.claude/skills/handover/SKILL.md` 包成 zip → 201

## §7 Result

填於 §6 verification 完成後。

**Result（填於 ship 後）**：
- backend tests 291 / 0 fail（288 → 291，新增 3 個 S073 test：block / flow / list-injection）
- 重啟 backend → 真實 curl R15.3 zip → 201 ✓
- AC-1 list block → valid ✓
- AC-2 list flow → valid ✓
- AC-3 legacy string → valid（向後相容）✓
- AC-4 list 含 injection → 400 並指向違規 token ✓
- AC-5 不存在欄位 → valid ✓
- ship v2.51.0 (M69)
