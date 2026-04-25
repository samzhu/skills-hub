---
name: springboot-project-architect
description: >
  Spring Boot project setup, configuration design, and optimization.
  Applies dual-layer profile design (infrastructure x environment behavior),
  unified property naming, secrets management, and Spring AI Manual
  Configuration patterns. Use when the user says "set up Spring Boot",
  "optimize config", "organize profiles", "review Spring Boot config",
  "新建 Spring Boot 專案", "優化設定檔", "整理 profile", "設定檔太亂",
  "config 分層", "環境配置", "GCP 部署設定", "Spring Boot 最佳實務",
  or asks about Spring Boot application.yaml design, profile strategy,
  secrets management, or GCP deployment configuration. Also use when
  reviewing an existing Spring Boot project's configuration health.
  Do NOT use for general Java coding, non-Spring frameworks, or
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
  version: 1.0.0
  category: workflow-automation
  pattern: sequential-orchestration
---

# Spring Boot Project Architect

## Role: Spring Boot Infrastructure Specialist

Systematic, convention-driven, evidence-based. Every configuration decision
cites official documentation. Build on framework defaults, customize only
what's necessary.

## Contract

```
Input:  Existing Spring Boot project OR new project requirements
Output: Optimized configuration files following dual-layer profile design
Valid:  Application starts correctly with all profile combinations;
        all existing tests pass; no secrets in version control
Next:   (terminal — user decides next action)
```

---

## Process

### Mode Detection

Determine the mode based on context:

- **Mode A: Optimize Existing Project** — `src/main/resources/application.yaml` exists
- **Mode B: New Project Setup** — no Spring Boot project found, or user explicitly requests new setup

---

## Mode A: Optimize Existing Project

### Step 1: Scan & Inventory

1. Find all configuration files:
   ```
   Glob: **/application*.yaml
   Glob: **/application*.yml
   Glob: **/application*.properties
   Glob: **/compose.yaml
   Glob: **/compose.yml
   Glob: **/.env
   ```

2. Read each file and record:
   - File path and which profile it belongs to
   - All property keys and their values
   - Placeholder patterns (`${...}`) and their naming style
   - Secrets (passwords, API keys, tokens) — note if hardcoded
   - Duplicate properties across files

3. Read build config (`build.gradle.kts` or `pom.xml`) to identify:
   - Spring Boot version
   - Spring Cloud GCP version (if any)
   - Spring AI version (if any)
   - Other relevant starters

4. Scan Java code for property references:
   ```
   Grep: @Value\("\\$\{
   Grep: @ConditionalOnProperty
   Grep: @ConfigurationProperties
   ```

### Step 2: Analyze Against Design Principles

Evaluate each file against the principles in `references/config-design-principles.md`:

| Check | Pass Criteria |
|-------|--------------|
| Dual-layer separation | Infrastructure profiles in `src/main/resources/`, behavior profiles in `config/` |
| `application.yaml` near-production | Defaults are production-safe (logging=INFO, springdoc=disabled, etc.) |
| Unified property naming | All placeholders use `${app-xxx:default}` format |
| No secrets in VCS | `.gitignore` excludes `config/application-secrets.properties` |
| No duplicate config | model/dimensions/options not repeated across profile files |
| Profile default set | `spring.profiles.default` enables zero-config dev experience |

### Step 3: Report & Confirm

Present findings to user:
1. Current file structure (tree diagram)
2. Issues found (table: issue, severity, affected files)
3. Proposed target structure
4. Property naming migration table (old → new)

**Wait for user confirmation before proceeding.**

### Step 4: Execute Changes

Apply changes following the order in `references/config-templates.md`:
1. Update `application.yaml` (shared base, near-production defaults)
2. Update infrastructure profiles (`application-local.yaml`, `application-gcp.yaml`)
3. Update behavior profiles (`config/application-dev.yaml`, etc.)
4. Create missing profiles as needed
5. Update secrets files and `.gitignore`
6. Update Java `@Bean` configuration if applicable (e.g., Manual Configuration for Spring AI)

### Step 5: Verify

1. Check all files match the target structure
2. Run `./gradlew test` (or `./mvnw test`) — all tests must pass
3. Run `./gradlew bootRun` (or `./mvnw spring-boot:run`) — app must start
4. Confirm active profiles in startup log
5. Produce completion report (template in `references/config-templates.md`)

---

## Mode B: New Project Setup

### Step 1: Gather Requirements

Ask the user:
1. Application name (for unified property prefix, e.g., `myapp`)
2. Cloud platform(s) — local, GCP, AWS, Azure
3. Database — PostgreSQL, MongoDB, Firestore, etc.
4. Additional services — AI/embedding, messaging, storage
5. Environment profiles needed — dev, lab, sit, uat, prod

### Step 2: Generate Configuration

Use templates from `references/config-templates.md` to create:
1. `src/main/resources/application.yaml`
2. `src/main/resources/application-local.yaml`
3. `src/main/resources/application-{cloud}.yaml` (if cloud platform specified)
4. `config/application-dev.yaml`
5. `config/application-secrets.properties.example`
6. `.gitignore` additions

### Step 3: Verify & Handoff

Same as Mode A Step 5.

---

## Deep Dives

- `references/config-design-principles.md` — Dual-layer profile design, property naming, secrets management, Spring AI Manual Configuration
- `references/config-templates.md` — Ready-to-use YAML templates for all profile files, completion report template
- `references/spring-reference-links.md` — How to find official Spring Boot / Spring Cloud GCP / Spring AI documentation, key reference URLs

## Key Design Principles

### Starter vs Manual Configuration

```
本地開發能用 Docker Compose / Testcontainers 跑？
  是 → Starter（auto-config）     例：JPA, MongoDB, Redis
  否 → 先讀官方文件，依決策階梯處理（見下方）
```

**Starter 能用就用** — auto-config 省配置省程式碼。有 Docker Compose 支援的依賴（JPA、MongoDB、Redis 等），直接用 Starter 整合開發即可。

### dev 跑不了時的決策階梯

當依賴在 dev 無法本地模擬時，**先徹底閱讀官方文件**，依序評估：

```
Step 1: 官方有 enable/disable 開關？ → 用它
Step 2: 開關不好用？ → autoconfigure.exclude 排除
Step 3: 仍不滿足（需 NoOp fallback）？ → Manual Configuration（@Bean）
Step 4: 以上都不行？ → 自己實作（最後手段）
```

**不要跳過前面的步驟。** 框架已經想過的問題，不要自己重新發明。

詳見 `references/config-design-principles.md` §5。

## Anti-Patterns

- Do NOT skip reading official docs — always check if the framework already provides an enable/disable mechanism before inventing workarounds
- Do NOT put behavior config (log levels, actuator scope) in `src/main/resources/` — these belong in `config/`
- Do NOT use SCREAMING_SNAKE_CASE for YAML placeholders — use `${app-xxx:default}` kebab-case
- Do NOT jump to Manual Configuration when the framework has a built-in disable switch — follow the decision ladder (official switch → exclude → Manual Config → custom)
- Do NOT put `springdoc.*.enabled=false` in `config/application-prod.yaml` — it won't exist on the server; put defaults in `application.yaml`
- Do NOT set `spring.profiles.active` inside a profile-specific file — Spring Boot will throw an error
