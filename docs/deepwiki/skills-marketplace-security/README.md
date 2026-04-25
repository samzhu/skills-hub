# AI Agent 技能市集安全掃描設計與實作

> 研究日期：2026-04-25
> 研究目的：分析 Skills Hub 競品的安全檢測掃描機制，為平台安全升級提供設計參考

---

## 研究背景

AI Agent Skills 生態在 2025-2026 年爆發成長。MCP 伺服器從 2025 年 1 月的 714 個暴增至超過 17,000 個；agentskills.io 的 SKILL.md 標準被 30+ 產品採用。然而安全問題日益嚴峻：

- Snyk ToxicSkills 研究掃描 3,984 個 skills，**13.4% 含 Critical 級安全問題**
- AgentSeal 掃描 1,808 個 MCP servers，**66% 有安全發現**
- OWASP 發佈了兩個專門標準：Agentic Applications Top 10 (ASI) 與 Agentic Skills Top 10 (AST)

## 研究範圍

| 類別 | 研究對象 |
|------|----------|
| **MCP 安全掃描工具** | Snyk agent-scan、Cisco mcp-scanner、Cisco skill-scanner |
| **技能市集安全機制** | Smithery.ai、MCPSkills.io、SkillShield.io |
| **傳統 Marketplace** | VS Code Extension Marketplace、npm/PyPI 生態 |
| **治理框架** | Microsoft Agent Governance Toolkit |
| **開源掃描工具** | SkillFortify（22 frameworks SBOM） |
| **安全標準** | OWASP Agentic Apps Top 10、OWASP Agentic Skills Top 10 |

## 技術棧總覽

| 平台/工具 | 語言 | 掃描方法 | 授權 |
|-----------|------|---------|------|
| Snyk agent-scan | Python | Cloud API + Local checks | 商用（免費 CLI） |
| Cisco mcp-scanner | Python | YARA + LLM + Behavioral + API | Apache 2.0 |
| Cisco skill-scanner | Python (96.2%) + YARA (2.5%) | 7 engines multi-layer | Apache 2.0 |
| SkillFortify | Python | Formal verification + SBOM | MIT |
| MS Agent Governance Toolkit | Python/Rust/TS/Go/.NET | Policy engine + Ed25519 | MIT |
| VS Code Marketplace | — | Defender engines + Sandbox | Proprietary |

## 文件索引

| 文件 | 說明 |
|------|------|
| [scanning-architecture.md](./scanning-architecture.md) | 掃描架構設計模式：多引擎 pipeline、各引擎原理與比較 |
| [threat-taxonomy.md](./threat-taxonomy.md) | AI Agent 特有威脅分類：OWASP ASI/AST、攻擊手法、真實案例 |
| [marketplace-security-models.md](./marketplace-security-models.md) | 各市集平台安全機制比較：發佈審核、信任分級、社群回報 |
| [tools-comparison.md](./tools-comparison.md) | 開源掃描工具深度比較：功能、架構、整合方式 |
| [data-flow.md](./data-flow.md) | 端對端資料流：從 skill 上傳到安全評估完成的完整流程 |
| [design-decisions.md](./design-decisions.md) | 設計決策表、技術債分析、Skills Hub 借鑑建議 |

## Skills Hub 借鑑重點

Skills Hub 目前的 S005 風險評估引擎採用 regex pattern matching，僅掃描 `scripts/` 目錄。對比業界實踐，有以下升級方向：

| 優先級 | 升級項目 | 對應 OWASP | 借鑑來源 |
|--------|---------|-----------|----------|
| P0 | 多引擎掃描 Pipeline（YARA + LLM + Behavioral） | AST08 | Cisco mcp-scanner |
| P1 | SKILL.md metadata 完整性驗證 | AST04 | SkillFortify |
| P1 | 信任評分系統（多維度信號） | AST09 | MCPSkills.io 14 signals |
| P2 | 加密簽章與驗證（Ed25519） | AST02 | MS Agent Governance Toolkit |
| P2 | Agent Skill BOM (ASBOM) 生成 | AST09 | SkillFortify CycloneDX |
| P3 | 供應鏈監控與更新漂移偵測 | AST07 | Snyk agent-scan + SkillFortify |

---

## 主要參考來源

- [Snyk agent-scan](https://github.com/snyk/agent-scan) — AI agent security scanner
- [Cisco mcp-scanner](https://github.com/cisco-ai-defense/mcp-scanner) — Multi-engine MCP scanner
- [Cisco skill-scanner](https://github.com/cisco-ai-defense/skill-scanner) — Agent skills scanner
- [SkillFortify](https://github.com/qualixar/skillfortify) — Formal security scanner + SBOM
- [Microsoft Agent Governance Toolkit](https://github.com/microsoft/agent-governance-toolkit) — Runtime security governance
- [OWASP Agentic Apps Top 10](https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026/)
- [OWASP Agentic Skills Top 10](https://owasp.org/www-project-agentic-skills-top-10/)
- [Snyk ToxicSkills Study](https://snyk.io/blog/toxicskills-malicious-ai-agent-skills-clawhub/)
- [VS Code Marketplace Security](https://developer.microsoft.com/blog/security-and-trust-in-visual-studio-marketplace)
- [MCPSkills.io](https://mcpskills.io/) — Trust scoring platform
- [State of MCP Security 2025](https://astrix.security/learn/blog/state-of-mcp-server-security-2025/)
- [AgentSeal MCP Findings](https://agentseal.org/blog/mcp-server-security-findings)
