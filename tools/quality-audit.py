#!/usr/bin/env python3
"""S099d — LLM Description Quality Audit.

Fetch all skills from local Skills Hub, evaluate each description with Claude
claude-haiku-4-5-20251001 on 5 rubric dimensions (each 0-20, total 0-100),
and produce docs/grimo/quality-audit-report.md.

Usage:
    python3 tools/quality-audit.py [--url URL] [--dry-run] [--top N]

Options:
    --url URL     Base URL of Skills Hub API (default: http://localhost:8080)
    --dry-run     Fetch skills list only; no Claude API calls, no report written
    --top N       Show top N lowest-scoring skills in rewrite section (default: 10)

Requirements:
    pip install anthropic
    export ANTHROPIC_API_KEY=sk-ant-...
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).parent.parent
REPORT_PATH = REPO_ROOT / "docs" / "grimo" / "quality-audit-report.md"

MODEL = "claude-haiku-4-5-20251001"
QUALITY_THRESHOLD = 60
RATE_LIMIT_SLEEP = 0.5
RETRY_SLEEP = 5.0

SYSTEM_PROMPT = """\
You are a technical writing quality evaluator for AI agent skill descriptions.
Score the following skill description on 5 dimensions, each 0-20.
Return ONLY valid JSON — no markdown, no explanation outside the JSON object.

Schema:
{
  "action_clarity": <int 0-20>,
  "domain_specificity": <int 0-20>,
  "non_marketing": <int 0-20>,
  "length_fit": <int 0-20>,
  "language_clarity": <int 0-20>,
  "rationale": "<one sentence about the lowest-scoring dimension>"
}

Rubric:
- action_clarity (0-20): Starts with a concrete action verb (Generates, Analyzes, Converts).
  Penalize vague openers: "helps", "allows", "enables", "provides", "offers".
- domain_specificity (0-20): Uses domain-specific nouns, not tech jargon salad.
  Penalize: "powerful solution", "leverage AI", "state-of-the-art", "next-gen".
- non_marketing (0-20): Zero marketing language.
  Penalize each: "robust", "seamlessly", "world-class", "cutting-edge", "revolutionize",
  "effortlessly", "streamline", "transformative", "unlock", "empower".
- length_fit (0-20): 50-200 words is ideal.
  Score 20 if in range. Score 0 if < 10 words. Score 10 if < 20 or > 300 words. Score 15 if 20-49 or 201-300 words.
- language_clarity (0-20): Single language (zh-TW or en), no code-switching confusion,
  complete sentences. Deduct for unexplained jargon, incomplete phrases, or random language mixing.\
"""


# ─── HTTP helpers ─────────────────────────────────────────────────────────────

def _fetch_json(url: str) -> dict | None:
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise
    except Exception as e:
        print(f"  [warn] fetch failed: {e}")
        return None


def _fetch_all_skills(base_url: str) -> list[dict]:
    skills = []
    page = 0
    while True:
        params = urllib.parse.urlencode({"size": 50, "page": page})
        data = _fetch_json(f"{base_url}/api/v1/skills?{params}")
        if not data or not data.get("content"):
            break
        batch = data["content"]
        skills.extend(batch)
        if data.get("last", True):
            break
        page += 1
    return skills


# ─── Claude evaluation ────────────────────────────────────────────────────────

def _call_claude(client, name: str, description: str) -> dict:
    user_msg = f"Skill name: {name}\nDescription: {description}"
    attempt = 0
    while attempt < 2:
        try:
            msg = client.messages.create(
                model=MODEL,
                max_tokens=256,
                system=SYSTEM_PROMPT,
                messages=[{"role": "user", "content": user_msg}],
            )
            raw = msg.content[0].text.strip()
            # strip markdown code fences if LLM wrapped in ```json
            if raw.startswith("```"):
                raw = raw.split("```")[1]
                if raw.startswith("json"):
                    raw = raw[4:]
            return json.loads(raw.strip())
        except Exception as e:
            err = str(e)
            if "429" in err or "rate" in err.lower():
                print(f"  [retry] rate-limited, sleeping {RETRY_SLEEP}s...")
                time.sleep(RETRY_SLEEP)
                attempt += 1
            else:
                raise
    raise RuntimeError("Claude API failed after retry")


def _score(raw: dict) -> dict:
    dims = ["action_clarity", "domain_specificity", "non_marketing",
            "length_fit", "language_clarity"]
    scores = {d: max(0, min(20, int(raw.get(d, 0)))) for d in dims}
    scores["total"] = max(0, min(100, sum(scores.values())))
    scores["rationale"] = str(raw.get("rationale", ""))[:200]
    return scores


def _evaluate(client, skill: dict) -> dict:
    name = skill.get("name", "unknown")
    description = skill.get("description", "")
    result = {
        "id": skill.get("id", ""),
        "name": name,
        "author": skill.get("author", ""),
        "description_preview": description[:80].replace("\n", " "),
        "status": "ok",
        "notes": "",
    }
    if not description:
        result.update({"action_clarity": 0, "domain_specificity": 0,
                        "non_marketing": 0, "length_fit": 0, "language_clarity": 0,
                        "total": 0, "rationale": "empty description", "status": "skip"})
        return result
    try:
        raw = _call_claude(client, name, description)
        result.update(_score(raw))
    except Exception as e:
        result.update({"action_clarity": 0, "domain_specificity": 0,
                        "non_marketing": 0, "length_fit": 0, "language_clarity": 0,
                        "total": 0, "rationale": "", "status": "error",
                        "notes": f"parse-error: {str(e)[:80]}"})
    return result


# ─── Report generation ────────────────────────────────────────────────────────

def _write_report(results: list[dict], base_url: str, top_n: int) -> None:
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    evaluated = [r for r in results if r["status"] == "ok"]
    errors = [r for r in results if r["status"] == "error"]
    skipped = [r for r in results if r["status"] == "skip"]

    sorted_results = sorted(evaluated, key=lambda r: r["total"])
    needs_rewrite = [r for r in sorted_results if r["total"] < QUALITY_THRESHOLD]

    def dim_cell(r: dict, d: str) -> str:
        return str(r.get(d, "—"))

    rows = []
    for r in sorted_results:
        rows.append(
            f"| {r['name']} | {r['author']} | {r['total']} "
            f"| {dim_cell(r,'action_clarity')} | {dim_cell(r,'domain_specificity')} "
            f"| {dim_cell(r,'non_marketing')} | {dim_cell(r,'length_fit')} "
            f"| {dim_cell(r,'language_clarity')} | {r.get('rationale','')[:60]} |"
        )

    rewrite_rows = []
    for r in needs_rewrite[:top_n]:
        preview = r["description_preview"]
        rewrite_rows.append(f"| {r['name']} | {r['author']} | {r['total']} | {preview}… |")

    rewrite_section = ""
    if rewrite_rows:
        rewrite_section = f"""
## 需要改寫（score < {QUALITY_THRESHOLD}）

| Skill | Author | Score | Description Preview |
|-------|--------|-------|---------------------|
""" + "\n".join(rewrite_rows)
    else:
        rewrite_section = f"\n## 需要改寫（score < {QUALITY_THRESHOLD}）\n\n（全部 skills 品質達標 ✅）\n"

    error_section = ""
    if errors:
        error_lines = "\n".join(f"- {r['name']}: {r['notes']}" for r in errors)
        error_section = f"\n## 評估錯誤（{len(errors)} 筆）\n\n{error_lines}\n"

    content = f"""\
# Description Quality Audit Report

Generated: {now}
Instance: {base_url}
Model: {MODEL}
Evaluated: {len(evaluated)} | Skipped (no description): {len(skipped)} | Errors: {len(errors)}
Quality threshold: {QUALITY_THRESHOLD}/100

## Score Table（按分數升序 — 最差優先）

| Skill | Author | Total | Action | Domain | Non-Mktg | Length | Language | Rationale |
|-------|--------|-------|--------|--------|----------|--------|----------|-----------|
{"" if not rows else chr(10).join(rows)}
{rewrite_section}
{error_section}
---
*Generated by `tools/quality-audit.py` — S099d LLM Description Quality Audit*
"""
    REPORT_PATH.write_text(content, encoding="utf-8")
    print(f"\n[report] Written to {REPORT_PATH}")


# ─── Main ─────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description="S099d LLM Description Quality Audit")
    parser.add_argument("--url", default="http://localhost:8080", help="Skills Hub base URL")
    parser.add_argument("--dry-run", action="store_true",
                        help="Fetch skills list only; no Claude calls, no report")
    parser.add_argument("--top", type=int, default=10,
                        help="Top N lowest-scoring skills in rewrite section")
    args = parser.parse_args()

    api_key = os.environ.get("ANTHROPIC_API_KEY")

    print(f"=== S099d Description Quality Audit ===")
    print(f"Target:  {args.url}")
    print(f"Model:   {MODEL}")
    print(f"Dry-run: {args.dry_run}")
    if not api_key and not args.dry_run:
        print("\n[error] ANTHROPIC_API_KEY is not set.")
        print("  Set it with: export ANTHROPIC_API_KEY=sk-ant-...")
        print("  Or use --dry-run to list skills without evaluation.")
        sys.exit(1)
    print()

    # ── Step 1: fetch skills ──────────────────────────────────────────────────
    print("[fetch] Loading skills from API...")
    try:
        skills = _fetch_all_skills(args.url)
    except Exception as e:
        print(f"[error] Cannot reach {args.url}: {e}")
        print("  Is the Skills Hub server running? Try: cd backend && ./gradlew bootTestRun")
        sys.exit(1)

    if not skills:
        print("[warn] No skills found. DB may be empty.")
        sys.exit(0)

    print(f"  Found {len(skills)} skill(s)")

    if args.dry_run:
        print(f"\n[dry-run] Skills list (name + description[:80]):\n")
        for s in skills:
            name = s.get("name", "(no name)")
            desc = (s.get("description") or "")[:80].replace("\n", " ")
            print(f"  {name:40s} {desc}")
        if not api_key:
            print(f"\n[!] ANTHROPIC_API_KEY not set — set it to run full evaluation.")
        print("\n[dry-run] Done. No Claude API calls made.")
        return

    # ── Step 2: evaluate ──────────────────────────────────────────────────────
    try:
        import anthropic
    except ImportError:
        print("[error] anthropic package not installed. Run: pip install anthropic")
        sys.exit(1)

    client = anthropic.Anthropic(api_key=api_key)
    results = []
    print(f"\n[evaluate] Scoring {len(skills)} skills with Claude {MODEL}...\n")

    for i, skill in enumerate(skills, 1):
        name = skill.get("name", f"skill-{i}")
        print(f"  [{i:3d}/{len(skills)}] {name:50s}", end=" ", flush=True)
        result = _evaluate(client, skill)
        status_tag = {
            "ok": f"score={result.get('total', '?'):3d}",
            "error": f"ERROR: {result.get('notes', '')[:40]}",
            "skip": "skip (no description)",
        }.get(result["status"], result["status"])
        print(status_tag)
        results.append(result)
        if i < len(skills):
            time.sleep(RATE_LIMIT_SLEEP)

    # ── Step 3: report ────────────────────────────────────────────────────────
    _write_report(results, args.url, args.top)

    evaluated = [r for r in results if r["status"] == "ok"]
    needs_rewrite = [r for r in evaluated if r["total"] < QUALITY_THRESHOLD]
    avg = (sum(r["total"] for r in evaluated) / len(evaluated)) if evaluated else 0

    print(f"\n=== Summary ===")
    print(f"Evaluated: {len(evaluated)} | Needs rewrite: {len(needs_rewrite)} | Avg score: {avg:.1f}")
    print(f"Report: {REPORT_PATH}")


if __name__ == "__main__":
    main()
