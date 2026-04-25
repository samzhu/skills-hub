---
name: springboot-project-architect
description: >
  Spring Boot project setup, configuration design, and optimization.
  Applies dual-layer profile design (infrastructure × behavior),
  @ConfigurationProperties with app name as prefix for type-safe property
  binding, unified secrets management, and Spring AI Manual Configuration
  patterns. Use when the user says "set up Spring Boot", "optimize config",
  "organize profiles", "review Spring Boot config", "@ConfigurationProperties",
  "新建 Spring Boot 專案", "優化設定檔", "整理 profile", "設定檔太亂",
  "config 分層", "環境配置", "GCP 部署設定", "Spring Boot 最佳實務",
  or asks about application.yaml design, profile strategy, type-safe
  properties binding, secrets management, or cloud deployment configuration.
  Also triggers when reviewing an existing Spring Boot project's configuration
  health. Do NOT use for general Java coding, non-Spring frameworks, or
  business logic implementation.
allowed-tools:
  - Read
  - Glob
  - Grep
  - Bash
  - Write
  - Edit
  - WebFetch
  - WebSearch
metadata:
  author: samzhu
  version: 1.1.0
  category: workflow-automation
  pattern: sequential-orchestration
---

# Spring Boot Project Architect

## Role

Systematic, convention-driven, evidence-based. Every configuration decision
cites official documentation. Build on framework defaults, customize only
what's necessary.

## Contract

```
Input:  Existing Spring Boot project OR new project requirements
Output: Optimized configuration files + {App}Properties record following
        dual-layer profile design and @ConfigurationProperties best practices
Valid:  Application starts correctly with all profile combinations;
        all existing tests pass; no secrets in version control
Next:   (terminal — user decides next action)
```

---

## Process

### Mode Detection

- **Mode A: Optimize Existing Project** — `src/main/resources/application.yaml` exists
- **Mode B: New Project Setup** — no Spring Boot project found, or user explicitly requests new setup

---

## Mode A: Optimize Existing Project

### Step 1: Scan & Inventory

Find all configuration files and Java property references:

```
Glob: **/application*.yaml
Glob: **/application*.yml
Glob: **/application*.properties
Glob: **/compose.yaml
Glob: **/.env

Grep: @Value\("\$\{          ← find @Value usage
Grep: @ConfigurationProperties
Grep: @ConditionalOnProperty
```

Read build config (`build.gradle.kts` / `pom.xml`) for Spring Boot, Spring Cloud GCP, Spring AI versions.

Record per file: profile it belongs to, all property keys + values, placeholder patterns, hardcoded secrets, duplicates across files.

**MANDATORY: Property Path Verification Gate**

After detecting framework versions, **must** verify all framework property paths (non-`{app}.*`) against the official Application Properties index for the detected version:

```
https://docs.spring.io/spring-boot/appendix/application-properties/index.html
```

Framework property paths **change across major versions** — rename、deprecate、搬移 namespace 都有可能。模板和記憶中的路徑可能已過期。寫任何 YAML 之前，**必須**查證偵測到的版本對應的官方 Application Properties 索引，確認每條 `spring.*` / `management.*` / `springdoc.*` 屬性路徑仍然有效。

### Step 2: Design Health Check

Evaluate against principles in `references/config-design-principles.md`:

| Check | Pass Criteria |
|-------|--------------|
| Dual-layer separation | Infrastructure profiles in `src/main/resources/`; behavior profiles in `config/` |
| `application.yaml` near-production | `logging=INFO`, `springdoc disabled`, minimal actuator by default |
| `{App}Properties` present | Record with `@ConfigurationProperties(prefix = "{app}")` exists; `@ConfigurationPropertiesScan` on main class |
| No `@Value` for app properties | No `@Value("${app.*}")` in service constructors — use `{App}Properties` injection |
| YAML uses pure values | `{app}.*` section has plain values, not `${APP_VAR:default}` placeholder indirection |
| Secrets use dot-notation | `application-secrets.properties.example` uses `{app}.section.key=value` |
| No secrets in VCS | `.gitignore` excludes `config/application-secrets.properties` |
| No duplicate config | Options (model, dimensions, etc.) not repeated across profile files |
| Profile default set | `spring.profiles.default` enables zero-config dev start |

### Step 3: Report & Confirm

Present to user:
1. Current file structure (tree diagram)
2. Issues found (table: issue, severity, affected files)
3. Proposed target structure
4. `@Value` → `{App}Properties` migration table

**Wait for user confirmation before making any changes.**

### Step 4: Execute Changes

Apply in this order (templates in `references/config-templates.md`):

1. Create `{App}Properties.java` — `@ConfigurationProperties(prefix = "{app}")` record with nested records for each property group
2. Add `@ConfigurationPropertiesScan` to `{App}Application.java`
3. Update `application.yaml` — near-production defaults; `{app}.*` section uses pure values
4. Update infrastructure profiles (`application-local.yaml`, `application-gcp.yaml`)
5. Migrate `@Value("${app.*}")` in service classes → inject `{App}Properties`
6. Update behavior profiles (`config/application-dev.yaml`, `config/application-lab.yaml`, etc.)
7. Create missing profiles as needed
8. Update secrets example file to dot-notation; update `.gitignore`
9. Update Java `@Bean` configuration if needed (e.g., Manual Configuration for external APIs)

### Step 5: Verify

```bash
./gradlew test          # (or ./mvnw test) — all tests pass
./gradlew bootRun       # app starts; confirm active profiles in log
```

Produce completion report using template in `references/config-templates.md`.

---

## Mode B: New Project Setup

### Step 1: Gather Requirements

Ask the user:
1. Application name — becomes `@ConfigurationProperties` prefix and profile file prefix
2. Cloud platform(s) — local, GCP, AWS, Azure
3. Database — PostgreSQL, MongoDB, Firestore, etc.
4. Additional services — AI/embedding, messaging, storage
5. Behavior profiles needed — dev, lab, sit, uat, prod

### Step 2: Generate Configuration

**MANDATORY: Property Path Verification** — Before generating any YAML, verify all framework property paths against the official Application Properties index for the target Spring Boot version. Templates contain `{app}.*` paths (safe — controlled by us) and framework paths (unsafe — may be deprecated in the target version). See Step 1 verification gate in Mode A.

Use templates from `references/config-templates.md` to create:

1. `{App}Properties.java` — `@ConfigurationProperties(prefix = "{app}")` record
2. `{App}Application.java` — main class with `@ConfigurationPropertiesScan`
3. `src/main/resources/application.yaml` — shared base, near-production defaults
4. `src/main/resources/application-local.yaml` — local infrastructure
5. `src/main/resources/application-{cloud}.yaml` — cloud infrastructure (if applicable)
6. `config/application-dev.yaml` — dev behavior
7. `config/application-secrets.properties.example` — dot-notation template
8. `.gitignore` additions

### Step 3: Verify & Handoff

Same as Mode A Step 5.

---

## References (Deep Dives)

Read these as needed during execution:

- `references/config-design-principles.md` — Dual-layer profile design (§1–2), `@ConfigurationProperties` strategy (§3), secrets management (§4), Starter vs Manual Configuration decision ladder (§5), config load order (§6)
- `references/config-templates.md` — Ready-to-use templates: `{App}Properties.java`, all profile YAML files, Manual Configuration `@Bean` example, completion report
- `references/spring-reference-links.md` — Official Spring Boot / Spring Cloud / Spring AI docs, how to look up properties and versions
- `references/cloud-gcp-secrets.md` — GCP Secret Manager integration patterns（Spring Cloud GCP `sm://` 語法、Cloud Run env var mount）+ 官方文件連結

---

## Key Principles (Quick Reference)

Full details in `references/config-design-principles.md`. One-line summaries:

1. **`@ConfigurationProperties` over `@Value`** — App custom props in `{App}Properties` record; prefix = app name (kebab-case). YAML uses pure values; relaxed binding handles env var override. → §3
2. **Dual-layer profile design** — Infrastructure (`local`/`gcp`) × behavior (`dev`/`lab`/`prod`). N+M files instead of N×M. → §1
3. **`application.yaml` near-production + shared fixed values** — Defaults safe for prod; shared constants (model names, feature toggles) here. Dev features open in `config/application-dev.yaml`. → §2
4. **Secrets dot-notation + cloud env var** — Local: `{app}.section.key=value` in `config/application-secrets.properties` (dev/lab import). Cloud: env var `{APP}_SECTION_KEY` (relaxed binding) or cloud-native secret manager. → §4
5. **Dependency choice = intent declaration** — Artifact 含 `starter` → 讓框架管配置（查官方文件配 YAML 屬性）。不含 `starter`（純 library）→ 自己控制配置（方式自決，不限 `@Bean`）。讀 build file 先於讀 YAML；切換意圖時換 artifact，不要混用。→ §5
6. **Starter vs Manual Config decision ladder** — Runs locally with Docker/Testcontainers? → Starter. Cannot? → Official switch → exclude → Manual Config → custom. → §5

---

## Anti-Patterns

- **Do NOT** use `@Value("${app.xxx}")` for app custom properties — create `{App}Properties` record
- **Do NOT** write `${APP_VAR:default}` placeholder for `{app}.*` in YAML — use plain values; relaxed binding handles `APP_VAR` env var automatically
- **Do NOT** use flat kebab key in `@ConditionalOnProperty` (`"{app}-genai-api-key"`) — use dot-notation matching `@ConfigurationProperties` path (`"{app}.genai.api-key"`)
- **Do NOT** skip reading official docs — always check if the framework has an enable/disable switch before inventing workarounds
- **Do NOT** put behavior config (log levels, actuator scope) in `src/main/resources/` — belongs in `config/`
- **Do NOT** put `springdoc.*.enabled=false` only in `config/application-prod.yaml` — it won't exist on the server; set defaults in `application.yaml` instead
- **Do NOT** set `spring.profiles.active` inside a profile-specific file — Spring Boot will throw an error
- **Do NOT** ignore the artifact name — 選了 `*-starter-*` 就查官方文件用 YAML 屬性配置，不要自己建 bean（衝突風險）；選了純 library 就自己控制，不要期待 auto-config 生效。混用 = 衝突。**描述 artifact 變體的配置策略前，必須先讀官方文件確認該變體的設計意圖 — 不可從單一專案的實作反推**
- **Do NOT** jump to Manual Configuration when the framework has a built-in disable switch — follow the decision ladder
- **Do NOT** duplicate fixed values (model names, dimensions) across profile files — put once in `application.yaml` or `{App}Properties @DefaultValue`; Manual Config 的 `@Bean` 從 properties 讀取
- **Do NOT** hardcode cloud-specific secret mechanisms (Secret Manager URIs, vault paths) in `application.yaml` — 用 env var 注入; cloud-native 整合放在雲端特定 profile 或部署設定，詳見 `references/cloud-*` 參考文件
- **Do NOT** trust framework property paths from templates or memory — 框架主版本升級會 rename / deprecate / 搬移屬性路徑。執行時**必須**查證目標版本的官方 Application Properties 索引。`{app}.*` 由 `@ConfigurationProperties` 控制不受影響，但所有框架 namespace（`spring.*`、`management.*` 等）都可能變動
- **Do NOT** treat "tests pass" as proof that config values are correct — deprecated 屬性在新版仍可運作（向下相容），測試無法捕捉 deprecation。查證手段：讀 JAR 內 `META-INF/spring-configuration-metadata.json` 的 `deprecation` 欄位，或查官方 Application Properties 索引。**設定值一定要查證過再動手，不是測試過就好**
