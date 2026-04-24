# Output Template — Deep Research

## README.md

```markdown
# {Product Name} 深度分析

> **定位：** {one-line positioning}
> **GitHub：** [{owner}/{repo}]({github-url})
> **授權：** {license} · **語言：** {language} {percentage} · **平台：** {platform}
> **版本：** {latest version} ({date}) · {commits} commits · {releases} releases

---

## 一句話總結

{2-3 sentences: what it is, what problem it solves, what makes it notable}

---

## 文件索引

| 文件 | 內容 |
|------|------|
| [architecture.md](./architecture.md) | {brief description} |
| [{protocol}.md](./{protocol}.md) | {brief description} |
| [{subsystem-1}.md](./{subsystem-1}.md) | {brief description} |
| [{subsystem-2}.md](./{subsystem-2}.md) | {brief description} |
| [data-flow.md](./data-flow.md) | {brief description} |
| [design-decisions.md](./design-decisions.md) | {brief description} |

---

## 技術棧一覽

| 層面 | 技術選擇 | 備註 |
|------|---------|------|
| 語言 | ... | ... |
| 框架 | ... | ... |
| ... | ... | ... |

---

## 與 grimoAPP 的關聯

{How this project relates to grimoAPP. Reference specific specs from
spec-roadmap.md. List the top 3 borrowable design patterns.}
```

---

## architecture.md

```markdown
# {Product Name} 核心架構

## 頂層目錄結構

{directory tree with annotations}

---

## 分層架構

{Describe the layering pattern. Include a diagram if multi-layered.}

### {Layer/Module 1}
{Responsibility, key types, code snippet}

### {Layer/Module 2}
{Responsibility, key types, code snippet}

---

## 核心設計模式

{For each pattern: name it, show the code, explain why it was chosen}

---

## 持久化模型

{Entity table with columns: Entity | Key Fields | Relationships}

---

## {Other architecture-specific section}

{Varies by project: concurrency model, plugin system, build pipeline, etc.}
```

---

## data-flow.md

```markdown
# 關鍵資料流程圖

## 1. {Scenario Name}

{Brief description of the scenario}

{ASCII flow diagram with:
 - Class/function names at each step
 - Cross-boundary annotations (process, network, persistence)
 - Return paths where relevant}

---

## 2. {Scenario Name}

{Same structure}

---

## N. {UI/Agent Terminal distinction or similar comparison}

{Side-by-side comparison diagram if two paths exist for similar functions}
```

---

## design-decisions.md

```markdown
# 設計決策與借鑑分析

## 關鍵設計決策

| # | 決策 | 理由 | 被否決的替代方案 |
|---|------|------|-----------------|
| 1 | ... | ... | ... |

---

## 已知挑戰與技術債

### 1. {Challenge Title}
**問題：** ...
**計畫中的解法：** ...

---

## 對 grimoAPP 的借鑑分析

### 直接可借鑑的設計模式

#### 1. {Pattern Name} ({spec reference})
{Description, how to adapt, code example if applicable}

### 值得注意但不一定適用的設計

#### 1. {Pattern Name}
{Why it's interesting, why it may not apply directly}

### 不適用的設計

| 設計 | 不適用原因 |
|------|-----------|
| ... | ... |

---

## 總結

{Top 3 takeaways, each tied to a specific grimoAPP spec or design concern}
```
