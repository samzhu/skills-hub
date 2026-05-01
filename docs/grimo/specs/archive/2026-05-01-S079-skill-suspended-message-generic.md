# S079 — `SkillSuspendedException` message 改為 operation-agnostic

> **Status**: in-flight
> **Type**: polish (tech debt cleanup)
> **Estimate**: XS / 1 pt

## §1 Problem

`SkillSuspendedException` constructor 寫死「Skill is suspended and cannot be downloaded」，源於 S029 設計時只用於 `/download` 路徑。S074 ship 後同 exception 也用於 `/files` 路徑（list / read），但訊息仍說「downloaded」 — 對 file-browser 使用者誤導（API debug log / response message 不準確）。

範例（已實際出現於 tick 62 R23.4 SUSPENDED skill /files 測試 response）：
```json
{
  "error": "SKILL_SUSPENDED",
  "message": "Skill is suspended and cannot be downloaded: 0636a030-...",
  ...
}
```

## §2 Scope

- 影響：API debug message / error log / Java exception message
- **不影響**：FE i18n message（FE 用 error code `SKILL_SUSPENDED` 對應 localized string，不依賴 backend `message`）
- 不影響：HTTP status (403) / error code (`SKILL_SUSPENDED`)

## §3 Change

`SkillSuspendedException.java`：
```java
// before
super("Skill is suspended and cannot be downloaded: " + skillId);

// after
super("Skill is suspended and not accessible: " + skillId);
```

Javadoc 同步更新為 operation-agnostic 描述。

## §4 Verification

- 既有 backend 全套 test PASS（無 test assert 此字串）
- Smoke：對 SUSPENDED skill 打 `/download` 與 `/files` 兩 path → 兩 path 回 403 + 新 message ✓

## §5 Result

待 ship 後填。

**Result（填於 ship 後）**：
- 299 backend tests / 0 fail（無 test 釘住此字串）
- Smoke：3 paths 全 return 新 message ✓
  - `/skills/{susp}/download` → 403「Skill is suspended and not accessible: ...」
  - `/skills/{susp}/files` → 403「Skill is suspended and not accessible: ...」
  - `/skills/{susp}/files/SKILL.md` → 403「Skill is suspended and not accessible: ...」
- error code `SKILL_SUSPENDED` 不變；FE i18n 不需調整
- ship v2.56.1 (M75)
