# 端對端資料流：安全掃描 Pipeline

> 來源：Cisco mcp-scanner、Snyk agent-scan、VS Code Marketplace、Skills Hub S005

---

## Flow 1：Skill 上傳到安全評估（Skills Hub 現有）

```
使用者                    Backend                         Storage         Security Module
  │                         │                               │                  │
  │  POST /api/v1/skills    │                               │                  │
  │  {name, version, zip}   │                               │                  │
  │ ────────────────────────▶│                               │                  │
  │                         │  validate SKILL.md             │                  │
  │                         │  frontmatter                   │                  │
  │                         │──────────────────────────────▶│                  │
  │                         │  StorageService.upload(zip)    │                  │
  │                         │◀──────────────────────────────│                  │
  │                         │                               │                  │
  │                         │  publish SkillVersionPublished │                  │
  │                         │  event                         │                  │
  │                         │ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ▶│
  │                         │                               │                  │
  │                         │                               │  ┌──────────────┐│
  │                         │                               │  │ @EventListener││
  │                         │                               │  │              ││
  │                         │                               │◀─│download(zip) ││
  │                         │                               │──│→ zipBytes    ││
  │                         │                               │  │              ││
  │                         │                               │  │extract       ││
  │                         │                               │  │ scripts/     ││
  │                         │                               │  │              ││
  │                         │                               │  │RiskScanner   ││
  │                         │                               │  │ .scan()      ││
  │                         │                               │  │  │           ││
  │                         │                               │  │  ├─ regex    ││
  │                         │                               │  │  │  patterns ││
  │                         │                               │  │  │           ││
  │                         │                               │  │  └─ result:  ││
  │                         │                               │  │    LOW/MED/  ││
  │                         │                               │  │    HIGH      ││
  │                         │                               │  │              ││
  │                         │                               │  │save event:   ││
  │                         │                               │  │SkillRisk     ││
  │                         │                               │  │ Assessed     ││
  │                         │                               │  └──────────────┘│
  │                         │                               │                  │
  │  ◀ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─│  Update SkillReadModel        │                  │
  │  riskLevel updated      │  riskLevel = HIGH/MED/LOW     │                  │
```

**限制：只掃描 scripts/ 目錄的 regex patterns，無法偵測 SKILL.md prompt injection、metadata 造假、binary malware。**

---

## Flow 2：業界最佳實踐 — 多引擎掃描 Pipeline

```
使用者                  API Gateway          Scan Orchestrator        Multiple Engines
  │                       │                       │                        │
  │ Upload Skill ZIP      │                       │                        │
  │──────────────────────▶│                       │                        │
  │                       │  parse + validate     │                        │
  │                       │──────────────────────▶│                        │
  │                       │                       │                        │
  │                       │                       │  ┌────────────────────┐│
  │                       │                       │  │ File Enumeration   ││
  │                       │                       │  │ • SKILL.md         ││
  │                       │                       │  │ • scripts/*        ││
  │                       │                       │  │ • references/*     ││
  │                       │                       │  │ • assets/*         ││
  │                       │                       │  │ • metadata extract ││
  │                       │                       │  └────────┬───────────┘│
  │                       │                       │           │            │
  │                       │                       │  Parallel dispatch     │
  │                       │                       │  ─────────┼──────────▶ │
  │                       │                       │           │            │
  │                       │                       │  ┌────────▼───────────┐│
  │                       │                       │  │                    ││
  │                       │                       │  │  Engine 1: YARA    ││
  │                       │                       │  │  35+ patterns      ││
  │                       │                       │  │  ~10ms             ││
  │                       │                       │  │                    ││
  │                       │                       │  │  Engine 2: LLM     ││
  │                       │                       │  │  Semantic analysis  ││
  │                       │                       │  │  ~2-5s             ││
  │                       │                       │  │                    ││
  │                       │                       │  │  Engine 3: AST     ││
  │                       │                       │  │  Behavioral check  ││
  │                       │                       │  │  ~1-3s             ││
  │                       │                       │  │                    ││
  │                       │                       │  │  Engine 4: Secret  ││
  │                       │                       │  │  API key detection ││
  │                       │                       │  │  ~50ms             ││
  │                       │                       │  │                    ││
  │                       │                       │  │  Engine 5: VirusT  ││
  │                       │                       │  │  Hash lookup       ││
  │                       │                       │  │  ~1-2s             ││
  │                       │                       │  │                    ││
  │                       │                       │  └────────┬───────────┘│
  │                       │                       │           │            │
  │                       │                       │  ┌────────▼───────────┐│
  │                       │                       │  │ Meta-Analyzer      ││
  │                       │                       │  │ • Cross-correlate  ││
  │                       │                       │  │ • False positive   ││
  │                       │                       │  │   filter           ││
  │                       │                       │  │ • Taxonomy enrich  ││
  │                       │                       │  │ • Final severity   ││
  │                       │                       │  └────────┬───────────┘│
  │                       │                       │           │            │
  │                       │                       │◀──────────┘            │
  │                       │                       │                        │
  │                       │  ScanResult           │                        │
  │                       │◀──────────────────────│                        │
  │                       │                       │                        │
  │  Scan Report          │                       │                        │
  │◀──────────────────────│                       │                        │
```

---

## Flow 3：VS Code Marketplace 五層防禦

```
Extension 作者             Marketplace              安全系統              使用者
      │                       │                       │                    │
      │ publish extension     │                       │                    │
      │──────────────────────▶│                       │                    │
      │                       │                       │                    │
      │                       │  ┌────────────────────┤                    │
      │                       │  │ Layer 1: Initial   │                    │
      │                       │  │ Scan (Defender +   │                    │
      │                       │  │ multi-AV engines)  │                    │
      │                       │  ├────────────────────┤                    │
      │                       │  │ ❌ Blocked →        │                    │
      │  rejected             │  │    reject publish  │                    │
      │◀──────────────────────│  │ ✅ Passed →         │                    │
      │                       │  │    publish          │                    │
      │                       │  └────────────────────┤                    │
      │                       │                       │                    │
      │                       │  published            │                    │
      │                       │──────────────────────▶│                    │
      │                       │                       │                    │
      │                       │  ┌────────────────────┤                    │
      │                       │  │ Layer 2: Post-pub  │                    │
      │                       │  │ rescan             │                    │
      │                       │  └��───────────────────┤                    │
      │                       │                       │                    │
      │                       │  ┌────────────────────┤                    │
      │                       │  │ Layer 3: Dynamic   │                    │
      │                       │  │ sandbox (VM)       │                    │
      │                       │  │ runtime behavior   │                    │
      │                       │  └────────────────────┤                    │
      │                       │                       │                    │
      │                       │  available            │                    │
      │                       │───────────────────────┼───────────────────▶│
      │                       │                       │                    │
      │                       │  ┌────────────────────┤                    │
      │                       │  │ Layer 4: Periodic  │  install           │
      │                       │  │ full-marketplace   │◀───────────────────│
      │                       │  │ rescan             │                    │
      │                       │  └────────────────────┤                    │
      │                       │                       │                    │
      │                       │  ┌────────────────────┤  report concern    │
      │                       │  │ Layer 5: Community │◀───────────────────│
      │                       │  │ report (1-day SLA) │                    │
      │                       │  └────────────────────┤                    │
      │                       │                       │                    │
      │                       │  malware detected!    │                    │
      │  extension removed    │◀──────────────────────│                    │
      │◀──────────────────────│                       │  auto-uninstall    │
      │                       │                       │──────────────────▶│
      │                       │  add to block list    │                    │
      │                       │──────────────────────▶│                    │
```

---

## Flow 4：Snyk agent-scan 自動發現 + 掃描

```
Security Admin              agent-scan CLI            Local Machine       Snyk Cloud
      │                          │                        │                  │
      │ uvx snyk-agent-scan      │                        │                  │
      │ @latest --skills         │                        │                  │
      │─────────────────────────▶│                        │                  │
      │                          │                        │                  │
      │                          │  Auto-discover agents  │                  │
      │                          │───────────────────────▶│                  │
      │                          │  well_known_clients.py │                  │
      │                          │                        │                  │
      │                          │  ┌────────────────────┐│                  │
      │                          │  │ Claude Code config ││                  │
      │                          │  │ Cursor config      ││                  │
      │                          │  │ VS Code config     ││                  │
      │                          │  │ Windsurf config    ││                  │
      │                          │  │ Gemini CLI config  ││                  │
      │                          │  └────────┬───────────┘│                  │
      │                          │           │            │                  │
      │                          │  Connect to MCP servers│                  │
      │                          │───────────────────────▶│                  │
      │                          │  Retrieve tool descs   │                  │
      │                          │◀──────────────────────│                  │
      │                          │                        │                  │
      │                          │  Discover skill dirs   │                  │
      │                          │───────────────────────▶│                  │
      │                          │  .claude/skills/*      │                  │
      │                          │◀──────────────────────│                  │
      │                          │                        │                  │
      │                          │  Local validation      │                  │
      │                          │  checks                │                  │
      │                          │                        │                  │
      │                          │  Redact sensitive data  │                  │
      │                          │  (file paths, env vars) │                  │
      │                          │                        │                  │
      │                          │  Submit to API         │                  │
      │                          │────────────────────────┼────────────────▶│
      │                          │  {tool_names, descs,   │                  │
      │                          │   skill_content}       │                  │
      │                          │                        │                  │
      │                          │  Threat analysis result│                  │
      │                          │◀───────────────────────┼─────────────────│
      │                          │                        │                  │
      │  Security Report         │                        │                  │
      │  E001: Prompt Injection  │                        │                  │
      │  E002: Tool Poisoning    │                        │                  │
      │  W007: Malware Payload   │                        │                  │
      │◀─────────────────────────│                        │                  │
```

---

## Flow 5：建議 Skills Hub 升級後的安全掃描流程

```
上傳者          SkillCommand         Storage        SecurityService       External
  │               │                    │                │                    │
  │ upload zip    │                    │                │                    │
  │──────────────▶│                    │                │                    │
  │               │ validate SKILL.md  │                │                    │
  │               │ frontmatter        │                │                    │
  │               │                    │                │                    │
  │               │ upload zip         │                │                    │
  │               │───────────────────▶│                │                    │
  │               │                    │                │                    │
  │               │ publish            │                │                    │
  │               │ SkillVersionPublished               │                    │
  │               │ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ▶│                    │
  │               │                    │                │                    │
  │               │                    │   download zip │                    │
  │               │                    │◀───────────────│                    │
  │               │                    │───────────────▶│                    │
  │               │                    │                │                    │
  │               │                    │    ┌───────────┴──────────────┐     │
  │               │                    │    │ Multi-Engine Pipeline     │     │
  │               │                    │    │                          │     │
  │               │                    │    │ 1. Pattern Scanner       │     │
  │               │                    │    │    (YARA-equivalent)     │     │
  │               │                    │    │    ALL files (not just   │     │
  │               │                    │    │    scripts/)             │     │
  │               │                    │    │                          │     │
  │               │                    │    │ 2. Metadata Validator    │     │
  │               │                    │    │    frontmatter checks    │     │
  │               │                    │    │    typosquatting detect  │     │
  │               │                    │    │                          │     │
  │               │                    │    │ 3. Secret Scanner        │     │
  │               │                    │    │    API keys, tokens      │     │
  │               │                    │    │                          │     │
  │               │                    │    │ 4. LLM Semantic Analysis │     │
  │               │                    │    │    (Spring AI + Gemini)  │     │
  │               │                    │    │    prompt injection check│──▶  │
  │               │                    │    │                          │  │  │
  │               │                    │    │ 5. Trust Score Calculator│  │  │
  │               │                    │    │    author history        │  │  │
  │               │                    │    │    version frequency     │  │  │
  │               │                    │    │                          │  │  │
  │               │                    │    │ Meta-Analyzer            │  │  │
  │               │                    │    │ → cross-correlate        │◀─┘  │
  │               │                    │    │ → false positive filter  │     │
  │               │                    │    │ → final severity         │     │
  │               │                    │    └───────────┬──────────────┘     │
  │               │                    │                │                    │
  │               │                    │    save SkillRiskAssessed          │
  │               │                    │    (with detailed findings)         │
  │               │                    │                │                    │
  │               │    update read     │                │                    │
  │               │    model           │◀───────────────│                    │
  │               │◀───────────────────│                │                    │
  │               │                    │                │                    │
  │  risk report  │                    │                │                    │
  │◀──────────────│                    │                │                    │
```

### 關鍵改進點

| 改進 | S005 現狀 | 建議升級 |
|------|----------|---------|
| 掃描範圍 | scripts/ only | ALL files (SKILL.md, references/, assets/) |
| 掃描引擎 | 1 (regex) | 5+ (pattern + metadata + secret + LLM + trust) |
| Prompt injection | ❌ 未檢測 | ✅ LLM semantic analysis |
| Metadata 驗證 | ❌ 只看 frontmatter 格式 | ✅ Typosquatting + completeness |
| Secret scanning | ❌ | ��� API key, token patterns |
| Trust scoring | ❌ | ✅ 多維度信號評分 |
| False positive | ❌ 無處理 | ✅ Meta-analyzer cross-correlate |
