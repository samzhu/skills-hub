#!/bin/bash
set -euo pipefail
KEY="$1"
ENDPOINT="https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent?key=$KEY"

fetch() {
  local text="$1"
  curl -s -X POST "$ENDPOINT" \
    -H "Content-Type: application/json" \
    -d "$(jq -n --arg t "$text" '{"content":{"parts":[{"text":$t}]},"outputDimensionality":768}')" \
    | jq -c '.embedding.values'
}

> fixture-output.json
echo '{"documents":[' > fixture-output.json

# Doc corpus
declare -a IDS=(
  "11111111-1111-1111-1111-100000000001"
  "11111111-1111-1111-1111-100000000002"
  "11111111-1111-1111-1111-100000000003"
  "11111111-1111-1111-1111-100000000004"
  "11111111-1111-1111-1111-100000000005"
)
declare -a NAMES=(
  "docker-compose-helper"
  "terraform-security-audit"
  "react-component-scaffold"
  "csv-data-cleaner"
  "langchain-agent-builder"
)
declare -a CATS=(
  "DevOps"
  "Security"
  "Frontend"
  "DataOps"
  "AI"
)
declare -a TEXTS=(
  "Helper skill for orchestrating multi-container Docker Compose dev stacks with service health checks and auto-reload."
  "Static analysis tool that audits Terraform infrastructure-as-code for AWS / GCP security misconfigurations and IAM least-privilege violations."
  "Frontend code generator that scaffolds React component boilerplate with TypeScript props, Tailwind styles, and vitest test files."
  "Tabular data cleanup tool: deduplicates rows, fixes encoding, normalizes date formats, and exports cleaned CSV / Parquet."
  "Framework helper for composing LangChain agents with tool calls, retry policies, and conversation memory."
)

FIRST=1
for i in 0 1 2 3 4; do
  vec=$(fetch "${TEXTS[$i]}")
  if [ "$FIRST" -eq 0 ]; then echo ',' >> fixture-output.json; fi
  FIRST=0
  jq -n --arg id "${IDS[$i]}" --arg name "${NAMES[$i]}" --arg cat "${CATS[$i]}" --arg text "${TEXTS[$i]}" --argjson vec "$vec" \
    '{id:$id, name:$name, category:$cat, text:$text, embedding:$vec}' >> fixture-output.json
done

echo '],"queries":[' >> fixture-output.json

declare -a QTEXTS=(
  "container orchestration"
  "infrastructure security audit"
  "frontend component generator"
)
declare -a QEXPECTED=(
  "11111111-1111-1111-1111-100000000001"
  "11111111-1111-1111-1111-100000000002"
  "11111111-1111-1111-1111-100000000003"
)

FIRST=1
for i in 0 1 2; do
  vec=$(fetch "${QTEXTS[$i]}")
  if [ "$FIRST" -eq 0 ]; then echo ',' >> fixture-output.json; fi
  FIRST=0
  jq -n --arg text "${QTEXTS[$i]}" --arg expect "${QEXPECTED[$i]}" --argjson vec "$vec" \
    '{text:$text, expectId:$expect, embedding:$vec}' >> fixture-output.json
done
echo ']}' >> fixture-output.json

# Validate output JSON
jq -e '.documents|length, .queries|length' fixture-output.json
echo "OK"
