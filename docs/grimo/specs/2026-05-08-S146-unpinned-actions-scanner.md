# S146: 掃描器補強 — GitHub Actions Unpinned Dependency 偵測

> Spec: S146 | Size: XS(3) | Status: 📋 planned
> Date: 2026-05-08
> Origin: site audit 2026-05-08 — 上傳 `auditing-terraform-infrastructure-for-security` skill（tessl.io Advisory W012），我們的掃描器回傳「無風險」；Snyk 已標記 `uses: bridgecrewio/checkov-action@master` 為 medium severity「Unverifiable external dependency detected」。掃描器盲點確認。

---

## 1. Goal

在 `PatternScanner` 的靜態規則集中加入「GitHub Actions unpinned dependency」偵測：SKILL.md 內嵌的 CI/CD workflow YAML 若使用 `uses: org/repo@master`、`@main`、`@HEAD` 等 floating tag，即標記為 MEDIUM 風險。

**非目標：**
- 不處理已 pinned 的 SHA（`@abc1234`）— 這些已是最佳實踐
- 不解析 GitHub API 驗證 action 是否存在
- 不偵測 `@v1`、`@v2` 等 semver tag（雖然 semver tag 也可被移動，但屬 lower-risk，留給未來規則）

---

## 2. Root Cause（掃描器盲點分析）

| | Snyk W012 | 我們的 PatternScanner |
|---|---|---|
| 偵測對象 | GitHub Actions `uses:` runtime fetch | 只偵測 shell/path/secret patterns |
| 命中規則 | Unverifiable external dependency (runtime URL controls agent) | **無對應規則** |
| 結果 | Advisory | 無風險 ← 誤判 |

`PatternScanner.RULES` 現有 8 條規則（rm -rf / chmod 777 / pipe-to-shell×2 / sensitive-path×4），全部針對 OS 層危險指令，未覆蓋 CI/CD workflow 內的供應鏈風險。

---

## 2. Approach

`PatternScanner.RULES` 新增 3 條規則（依 floating tag 類型拆開，便於 SARIF ruleId 獨立追蹤）：

```java
// GitHub Actions: uses: org/repo@master — floating branch tag，可被 maintainer 靜默更新
new Rule("ACTIONS_UNPINNED_MASTER",
        Pattern.compile("uses:\\s+\\S+@master"),
        Severity.MEDIUM,
        "Unpinned GitHub Action: @master tag can be silently updated"),

// GitHub Actions: uses: org/repo@main — 同上，main 分支
new Rule("ACTIONS_UNPINNED_MAIN",
        Pattern.compile("uses:\\s+\\S+@main"),
        Severity.MEDIUM,
        "Unpinned GitHub Action: @main tag can be silently updated"),

// GitHub Actions: uses: org/repo@HEAD — 直接指向最新 commit，最不穩定
new Rule("ACTIONS_UNPINNED_HEAD",
        Pattern.compile("uses:\\s+\\S+@HEAD"),
        Severity.MEDIUM,
        "Unpinned GitHub Action: @HEAD resolves to latest commit"),
```

**Severity 選 MEDIUM 的理由：**
- Snyk W012 也是 medium severity
- 不是所有 `@master` 都是惡意的（合法工具也常用），但確實是供應鏈風險向量
- HIGH 門檻留給明確破壞性命令（rm -rf、pipe-to-shell）

**OWASP AST tag：**
- `AST08: Uncontrolled Third-Party Dependency`（非既有的 AST06）
- PatternScanner 目前所有規則用 hardcoded `OWASP_AST06`；此處需允許規則自帶 owaspAst 或用 `AST08`

---

## 3. Acceptance Criteria

```
Scenario: SKILL.md 含 @master GitHub Action → 偵測為 MEDIUM
  Given SKILL.md 包含 `uses: bridgecrewio/checkov-action@master`
  When 風險掃描執行
  Then SecurityFinding ruleId = "ACTIONS_UNPINNED_MASTER"
  And  severity = MEDIUM
  And  filePath = "SKILL.md"
  And  整體風險等級 ≥ MEDIUM（不再是「無風險」）

Scenario: SKILL.md 含 @main GitHub Action → 偵測為 MEDIUM
  Given SKILL.md 包含 `uses: actions/checkout@main`
  When 風險掃描執行
  Then SecurityFinding ruleId = "ACTIONS_UNPINNED_MAIN"
  And  severity = MEDIUM

Scenario: 已 pinned SHA 不觸發
  Given SKILL.md 包含 `uses: actions/checkout@v4` 或 `uses: actions/checkout@abc1234`
  When 風險掃描執行
  Then 無 ACTIONS_UNPINNED_* finding

Scenario: 無 uses: 的 SKILL.md 不誤觸
  Given SKILL.md 不含任何 GitHub Actions workflow
  When 風險掃描執行
  Then 無 ACTIONS_UNPINNED_* finding
```

---

## 4. Files to Change

| 檔案 | 變動 |
|------|------|
| `backend/src/main/java/.../security/scan/engines/PatternScanner.java` | `RULES` 新增 3 條 ACTIONS_UNPINNED_* |
| `backend/src/test/java/.../security/scan/engines/PatternScannerTest.java` | 補 4 個 AC 對應測試 |

**owaspAst 問題：**
- 若要正確標 AST08（而非 AST06），需讓 `Rule` record 多帶一個 `owaspAst` 欄位，`SecurityFinding` 建構時傳入
- 影響範圍：`Rule` record 定義 + `scanFile()` 裡的 finding 建構一處
- 簡化選項：暫時沿用 AST06（掃描結果功能不受影響），AST tag 正確性留技術債；優先讓 AC 通過

---

## 5. Test Plan

- [ ] `ACTIONS_UNPINNED_MASTER` — `uses: bridgecrewio/checkov-action@master` → 1 finding MEDIUM
- [ ] `ACTIONS_UNPINNED_MAIN` — `uses: actions/checkout@main` → 1 finding MEDIUM
- [ ] `ACTIONS_UNPINNED_HEAD` — `uses: org/repo@HEAD` → 1 finding MEDIUM
- [ ] pinned `@v4` — 無 finding
- [ ] pinned SHA `@abc1234` — 無 finding
- [ ] 無 `uses:` 的 SKILL.md — 無 finding（regression）
