# S063: Skill Aggregate `isNew()` JsonIgnore

> Spec: S063 | Size: XS(5) | Status: ✅ Done — target ship `v2.40.0`
> Trigger: 2026-05-01 /loop tick 36 — `GET /api/v1/skills/{id}` 與 list endpoint JSON 仍包含 `new` 欄位（Spring Data JDBC `Persistable.isNew()` artifact）。S062 只修了 SkillVersion，Skill aggregate 同問題未處理。

---

## 1. Goal

`Skill.isNew()` 加 `@JsonIgnore`。延伸 S062 修復至 `Skill` aggregate。

---

## 2. Approach

```java
@com.fasterxml.jackson.annotation.JsonIgnore
@Override
public boolean isNew() { ... }
```

---

## 3. SBE Acceptance Criteria

### AC-1: GET /skills/{id} JSON 不再含 `new`

```gherkin
When  GET /api/v1/skills/{id}
Then  response keys 不含 "new"
```

### AC-2: GET /skills list 同樣不含 `new`

### AC-3: backend 286 tests 不破

---

## 7. Implementation Results — ✅ Done

### 7.1 Verification

| 命令 | 結果 |
|------|------|
| `./gradlew test` | 286 / 0 fail ✓ |
| GET /skills/{id} keys | 不含 "new" ✓ AC-1 |
| GET /skills?size=1 first item keys | 不含 "new" ✓ AC-2 |

### 7.2 Files Changed

#### Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`：`isNew()` 加 `@JsonIgnore`

### 7.3 Key Findings

S062 只修了 SkillVersion 的同類洩漏；本次發現 Skill aggregate 也暴露 `new` artifact。Pattern：所有 `implements Persistable<>` 的 aggregate 應一併隱藏。

未來若有新 aggregate 採同模式，記得套用 `@JsonIgnore` on `isNew()`。
