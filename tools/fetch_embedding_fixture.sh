#!/bin/bash
# S157 — 用真實 Gemini API 算 5 個 ClawHub 真實 skill + 3 個 query 的 768-d 向量，
# 輸出 /tmp/fixture-output.json 供 embedding_fixture_to_sql.py 轉成 SQL + Java fixture。
#
# 半年或 model 升級時 refresh：
#   KEY=$(grep '^skillshub.genai.api-key=' backend/config/application-secrets.properties | cut -d= -f2-)
#   bash tools/fetch_embedding_fixture.sh "$KEY"
#   python3 tools/embedding_fixture_to_sql.py
set -euo pipefail
KEY="$1"
ENDPOINT="https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent?key=$KEY"
OUT="/tmp/fixture-output.json"

fetch() {
  local text="$1"
  curl -s -X POST "$ENDPOINT" \
    -H "Content-Type: application/json" \
    -d "$(jq -n --arg t "$text" '{"content":{"parts":[{"text":$t}]},"outputDimensionality":768}')" \
    | jq -c '.embedding.values'
}

echo '{"documents":[' > "$OUT"

# 5 真實 ClawHub / OpenClaw skills（來源：github.com/VoltAgent/awesome-openclaw-skills）
# 選 3 跟 query 對應 + 2 distractor（語意明顯不同領域）
declare -a IDS=(
  "11111111-1111-1111-1111-100000000001"
  "11111111-1111-1111-1111-100000000002"
  "11111111-1111-1111-1111-100000000003"
  "11111111-1111-1111-1111-100000000004"
  "11111111-1111-1111-1111-100000000005"
)
declare -a NAMES=(
  "agent-browser"
  "agentic-devops"
  "agent-skills-audit"
  "duckdb-en"
  "agent-memory-ultimate"
)
declare -a CATS=(
  "Automation"
  "DevOps"
  "Security"
  "Data"
  "Productivity"
)
declare -a TEXTS=(
  "A fast Rust-based headless browser automation CLI for agents — form filling, screenshot capture, DOM traversal, and web scraping workflows for automated testing and data extraction."
  "Production-grade agent DevOps toolkit covering Docker container management, process supervision, log analysis, and health monitoring for deploying and operating agent systems at scale."
  "Two-pass multidisciplinary code audit led by a tie-breaker lead, combining security vulnerability review, performance bottleneck detection, UX evaluation, and developer experience checks."
  "DuckDB CLI specialist for SQL analysis and data processing — complex queries, aggregations, joins, and analytics on local CSV / Parquet datasets without external dependencies."
  "Production-ready persistent memory system for agents — daily logs, sleep consolidation, SQLite plus FTS5 retrieval, and importers for WhatsApp, ChatGPT, and VCF contact data."
)

FIRST=1
for i in 0 1 2 3 4; do
  vec=$(fetch "${TEXTS[$i]}")
  if [ "$FIRST" -eq 0 ]; then echo ',' >> "$OUT"; fi
  FIRST=0
  jq -n --arg id "${IDS[$i]}" --arg name "${NAMES[$i]}" --arg cat "${CATS[$i]}" --arg text "${TEXTS[$i]}" --argjson vec "$vec" \
    '{id:$id, name:$name, category:$cat, text:$text, embedding:$vec}' >> "$OUT"
done

echo '],"queries":[' >> "$OUT"

declare -a QTEXTS=(
  "browser automation and web scraping"
  "container deployment and process management"
  "code security review"
)
declare -a QEXPECTED=(
  "11111111-1111-1111-1111-100000000001"
  "11111111-1111-1111-1111-100000000002"
  "11111111-1111-1111-1111-100000000003"
)

FIRST=1
for i in 0 1 2; do
  vec=$(fetch "${QTEXTS[$i]}")
  if [ "$FIRST" -eq 0 ]; then echo ',' >> "$OUT"; fi
  FIRST=0
  jq -n --arg text "${QTEXTS[$i]}" --arg expect "${QEXPECTED[$i]}" --argjson vec "$vec" \
    '{text:$text, expectId:$expect, embedding:$vec}' >> "$OUT"
done
echo ']}' >> "$OUT"

jq -e '[(.documents|length), (.queries|length)]' "$OUT"
echo "OK: wrote $OUT"
