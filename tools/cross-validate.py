#!/usr/bin/env python3
"""S099c — Cross-Marketplace Risk Validation Script.

Clone public skill repos, upload each SKILL.md to local dev instance,
poll for risk_level, and produce docs/grimo/cross-validation-report.md.

Usage:
    python3 tools/cross-validate.py [--url URL] [--token TOKEN] [--dry-run]

Options:
    --url URL       Base URL of Skills Hub API (default: http://localhost:8080)
    --token TOKEN   Bearer token for authentication (not needed in LAB/bootTestRun mode)
    --dry-run       Clone and list SKILL.md files only; no API calls
    --sources FILE  Optional JSON file with custom source list
"""

import argparse
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).parent.parent
REPORT_PATH = REPO_ROOT / "docs" / "grimo" / "cross-validation-report.md"

SOURCES = [
    {
        "name": "anthropics/skills",
        "url": "https://github.com/anthropics/skills.git",
        "skills_subdir": "skills",
    },
    {
        "name": "huggingface/skills",
        "url": "https://github.com/huggingface/skills.git",
        "skills_subdir": "skills",
    },
    {
        "name": "agentregistry-dev/skills",
        "url": "https://github.com/agentregistry-dev/skills.git",
        "skills_subdir": "skills",
    },
]

POLL_INTERVAL_S = 2
POLL_TIMEOUT_S = 60
DEFAULT_AUTHOR = "cross-validate"
DEFAULT_CATEGORY = "Other"
DEFAULT_VERSION = "1.0.0"


# ─── HTTP helpers ─────────────────────────────────────────────────────────────

def _build_headers(token: str | None) -> dict:
    h = {"Accept": "application/json"}
    if token:
        h["Authorization"] = f"Bearer {token}"
    return h


def _get(url: str, token: str | None) -> dict | None:
    req = urllib.request.Request(url, headers=_build_headers(token))
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def _search_by_name(base_url: str, token: str | None, name: str) -> str | None:
    """Return existing skill ID by keyword search, or None."""
    params = urllib.parse.urlencode({"keyword": name, "size": 5})
    data = _get(f"{base_url}/api/v1/skills?{params}", token)
    if not data or not data.get("content"):
        return None
    for skill in data["content"]:
        if skill.get("name") == name:
            return skill["id"]
    return None


def _upload(base_url: str, token: str | None, zip_bytes: bytes,
            skill_name: str, version: str) -> str:
    """Upload zip, return skill ID. Raises on HTTP error."""
    boundary = "----SkillsHubBoundary7MA4YWxkTrZu0gW"
    parts = []
    for field, value in [("version", version), ("author", DEFAULT_AUTHOR),
                          ("category", DEFAULT_CATEGORY), ("visibility", "PUBLIC")]:
        parts.append(
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{field}"\r\n\r\n'
            f"{value}\r\n"
        )
    file_part = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{skill_name}.zip"\r\n'
        f"Content-Type: application/zip\r\n\r\n"
    )
    body = (
        "".join(parts).encode() +
        file_part.encode() +
        zip_bytes +
        f"\r\n--{boundary}--\r\n".encode()
    )
    headers = _build_headers(token)
    headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
    req = urllib.request.Request(
        f"{base_url}/api/v1/skills/upload",
        data=body,
        headers=headers,
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode())["id"]


def _poll_risk_level(base_url: str, token: str | None, skill_id: str) -> str:
    """Poll until riskLevel is non-null or timeout. Returns risk level or 'TIMEOUT'."""
    deadline = time.monotonic() + POLL_TIMEOUT_S
    while time.monotonic() < deadline:
        data = _get(f"{base_url}/api/v1/skills/{skill_id}", token)
        if data and data.get("riskLevel") is not None:
            return data["riskLevel"]
        time.sleep(POLL_INTERVAL_S)
    return "TIMEOUT"


# ─── Clone & extract ──────────────────────────────────────────────────────────

def _clone_repo(source: dict, work_dir: Path) -> Path | None:
    dest = work_dir / source["name"].replace("/", "_")
    if dest.exists():
        print(f"  [cache] {source['name']} already cloned")
        return dest
    print(f"  [clone] {source['name']} ...", end=" ", flush=True)
    result = subprocess.run(
        ["git", "clone", "--depth=1", "--quiet", source["url"], str(dest)],
        capture_output=True, text=True, timeout=120,
    )
    if result.returncode != 0:
        print(f"FAILED: {result.stderr.strip()}")
        return None
    print("OK")
    return dest


def _find_skill_dirs(repo_path: Path, skills_subdir: str) -> list[Path]:
    skills_root = repo_path / skills_subdir
    if not skills_root.exists():
        # Some repos put SKILL.md at root
        skills_root = repo_path
    dirs = []
    for entry in sorted(skills_root.iterdir()):
        if entry.is_dir() and (entry / "SKILL.md").exists():
            dirs.append(entry)
    # Also check repo root SKILL.md
    if (repo_path / "SKILL.md").exists() and not dirs:
        dirs.append(repo_path)
    return dirs


def _extract_name(skill_dir: Path) -> str:
    skill_md = skill_dir / "SKILL.md"
    for line in skill_md.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if line.startswith("name:"):
            return line.split(":", 1)[1].strip().strip('"').strip("'")
    return skill_dir.name.lower().replace("_", "-").replace(" ", "-")


def _make_zip(skill_dir: Path) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        skill_md = skill_dir / "SKILL.md"
        zf.write(skill_md, "SKILL.md")
    return buf.getvalue()


# ─── Main ─────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description="S099c Cross-Marketplace Risk Validation")
    parser.add_argument("--url", default="http://localhost:8080", help="Skills Hub base URL")
    parser.add_argument("--token", default=None, help="Bearer token (not needed in LAB mode)")
    parser.add_argument("--dry-run", action="store_true", help="Clone + list only; no API calls")
    args = parser.parse_args()

    work_dir = REPO_ROOT / ".cross-validate-cache"
    work_dir.mkdir(exist_ok=True)

    print(f"=== S099c Cross-Marketplace Risk Validation ===")
    print(f"Target: {args.url}")
    print(f"Dry-run: {args.dry_run}")
    print()

    # ── Step 1: clone + extract ───────────────────────────────────────────────
    records: list[dict] = []

    for source in SOURCES:
        print(f"[source] {source['name']}")
        repo_path = _clone_repo(source, work_dir)
        if repo_path is None:
            print(f"  Skipping {source['name']} (clone failed)")
            continue

        skill_dirs = _find_skill_dirs(repo_path, source.get("skills_subdir", "skills"))
        if not skill_dirs:
            print(f"  No skills found in {source['name']}")
            continue

        print(f"  Found {len(skill_dirs)} skill(s)")
        for d in skill_dirs:
            skill_name = _extract_name(d)
            records.append({
                "source": source["name"],
                "skill_dir": d,
                "name": skill_name,
                "skill_id": None,
                "status": "pending",
                "risk_level": None,
                "notes": "",
            })

    if args.dry_run:
        print(f"\n[dry-run] Would upload {len(records)} skills:")
        for r in records:
            print(f"  {r['source']:30s} {r['name']}")
        print("\n[dry-run] Exiting. No API calls made.")
        return

    print(f"\n[upload] Processing {len(records)} skills...\n")

    # ── Step 2: upload ────────────────────────────────────────────────────────
    for r in records:
        name = r["name"]
        print(f"  {r['source']:30s} {name:40s}", end=" ", flush=True)

        # Check if already exists
        try:
            existing_id = _search_by_name(args.url, args.token, name)
        except Exception as e:
            print(f"SEARCH-ERROR: {e}")
            r["status"] = "error"
            r["notes"] = f"search error: {e}"
            continue

        if existing_id:
            print(f"skipped (exists: {existing_id[:8]}…)")
            r["skill_id"] = existing_id
            r["status"] = "skipped"
            continue

        # Upload
        try:
            zip_bytes = _make_zip(r["skill_dir"])
            skill_id = _upload(args.url, args.token, zip_bytes, name, DEFAULT_VERSION)
            print(f"uploaded: {skill_id[:8]}…")
            r["skill_id"] = skill_id
            r["status"] = "uploaded"
        except urllib.error.HTTPError as e:
            body = e.read().decode(errors="replace")[:120]
            print(f"UPLOAD-FAILED ({e.code}): {body}")
            r["status"] = "error"
            r["notes"] = f"HTTP {e.code}"
        except Exception as e:
            print(f"UPLOAD-ERROR: {e}")
            r["status"] = "error"
            r["notes"] = str(e)

    # ── Step 3: poll ──────────────────────────────────────────────────────────
    to_poll = [r for r in records if r["skill_id"] and r["risk_level"] is None]
    if to_poll:
        print(f"\n[poll] Waiting for risk assessment on {len(to_poll)} skills...")
        for r in to_poll:
            name = r["name"]
            print(f"  {name:50s}", end=" ", flush=True)
            try:
                level = _poll_risk_level(args.url, args.token, r["skill_id"])
                r["risk_level"] = level
                print(level)
            except Exception as e:
                print(f"POLL-ERROR: {e}")
                r["risk_level"] = "ERROR"
                r["notes"] += f" poll-error: {e}"

    # ── Step 4: generate report ───────────────────────────────────────────────
    _write_report(records, args.url)

    # Summary
    total = len(records)
    uploaded = sum(1 for r in records if r["status"] == "uploaded")
    skipped = sum(1 for r in records if r["status"] == "skipped")
    errors = sum(1 for r in records if r["status"] == "error")
    timeout = sum(1 for r in records if r["risk_level"] == "TIMEOUT")

    print(f"\n=== Done ===")
    print(f"Total: {total} | Uploaded: {uploaded} | Skipped: {skipped} | Errors: {errors} | Timeout: {timeout}")
    print(f"Report: {REPORT_PATH}")


def _write_report(records: list[dict], base_url: str) -> None:
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    total = len(records)
    timeout_count = sum(1 for r in records if r["risk_level"] == "TIMEOUT")
    skipped_count = sum(1 for r in records if r["status"] == "skipped")

    rows = []
    for i, r in enumerate(records, 1):
        risk = r["risk_level"] or "—"
        notes = r["notes"] or ("exists" if r["status"] == "skipped" else "")
        rows.append(
            f"| {i} | {r['name']} | {r['source']} | {risk} | {notes} |"
        )

    table = "\n".join(rows)
    content = f"""\
# Cross-Marketplace Risk Validation Report

Generated: {now}
Instance: {base_url}
Skills processed: {total} | Timeout: {timeout_count} | Skipped (existed): {skipped_count}

> **Note**: Risk Level reflects Skills Hub automated assessment.
> "TIMEOUT" = risk_level not assigned within 60s.
> "—" = upload failed. "skipped" notes = skill already existed in DB.

| # | Skill Name | Source | Risk Level | Notes |
|---|-----------|--------|-----------|-------|
{table}

---
*Generated by `tools/cross-validate.py` — S099c Cross-Marketplace Risk Validation*
"""
    REPORT_PATH.write_text(content, encoding="utf-8")
    print(f"\n[report] Written to {REPORT_PATH}")


if __name__ == "__main__":
    main()
