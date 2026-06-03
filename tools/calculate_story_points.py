#!/usr/bin/env python3
"""Calculate Grimo spec records on the MVP complexity story-point deck."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter
from dataclasses import dataclass
from datetime import date
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[1]
ROADMAP = ROOT / "docs/grimo/specs/spec-roadmap.md"
SPEC_DIR = ROOT / "docs/grimo/specs"
ARCHIVE_DIR = SPEC_DIR / "archive"
DEFAULT_OUTPUT = ROOT / "docs/grimo/specs/story-points-2026-06-02.json"

MARKETING_CUTOFF_VERSION = (4, 86, 0)
MARKETING_CUTOFF_DATE = date(2026, 5, 19)
TOKEN_COST = Decimal("3362.38")
DAYS = Decimal("26")

SPEC_ID_RE = re.compile(r"\bS\d{3}(?:[a-z])?(?:-\d+)?(?:'{1,3})?\b")
VERSION_RE = re.compile(r"v(\d+)\.(\d+)\.(\d+)")
PATH_RE = re.compile(r"(?:backend|frontend|e2e|docs|scripts|tools)/[A-Za-z0-9_./'{}:@+-]+")
HEADER_TITLE_RE = re.compile(r"^#\s+(.+)$", re.MULTILINE)

ROLLED_UP_CHILDREN = {
    "S160b",
    "S160b'",
    "S160b''",
    "S160b'''",
    "S161b",
    "S161b'",
    "S161b''",
    "S161c",
    "S163b",
    "S163b'",
    "S164b",
}
PARENT_OR_ROLLUP_IDS = {"S014", "S147", "S160", "S161", "S163", "S164"}
FIBONACCI = (1, 2, 3, 5, 8, 13, 20)
NOT_EXECUTED_STATUSES = {"cancelled", "deferred", "other"}


@dataclass
class RoadmapRow:
    spec_id: str
    title: str
    points_raw: str
    status_raw: str
    section: str
    version: tuple[int, int, int] | None


@dataclass
class SpecDoc:
    spec_id: str
    path: str
    bytes_read: int
    lines_read: int
    sha256: str
    archive_date: str | None
    title: str
    header_status: str
    text: str


def version_tuple(text: str) -> tuple[int, int, int] | None:
    match = VERSION_RE.search(text)
    if not match:
        return None
    return tuple(int(part) for part in match.groups())


def version_text(version: tuple[int, int, int] | None) -> str | None:
    if version is None:
        return None
    return f"v{version[0]}.{version[1]}.{version[2]}"


def sort_key(spec_id: str) -> tuple[int, str]:
    match = re.search(r"\d+", spec_id)
    return (int(match.group(0)) if match else 9999, spec_id)


def text_has(text: str, patterns: Iterable[str]) -> bool:
    lower = text.lower()
    return any(pattern in lower for pattern in patterns)


def unique_paths(text: str) -> set[str]:
    return {path.rstrip(".,;)`|") for path in PATH_RE.findall(text)}


def dependency_count(text: str, current_id: str) -> int:
    ids = set(SPEC_ID_RE.findall(text))
    ids.discard(current_id)
    return len(ids)


def ac_count(text: str) -> int:
    return len(set(re.findall(r"\bAC[- ]?S?\d{0,3}[-.]?\d+\b", text)))


def task_count(text: str) -> int:
    return len(set(re.findall(r"\bT\d{2}\b", text)))


def parse_roadmap() -> dict[str, RoadmapRow]:
    rows: dict[str, RoadmapRow] = {}
    section = ""
    for line in ROADMAP.read_text(encoding="utf-8").splitlines():
        if line.startswith("## "):
            section = line.lstrip("# ").strip()
            continue
        if not line.startswith("| S"):
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) < 3 or not SPEC_ID_RE.fullmatch(cells[0]):
            continue
        if section.startswith("Milestones"):
            continue
        spec_id = cells[0]
        row = RoadmapRow(
            spec_id=spec_id,
            title=cells[1],
            points_raw=cells[2],
            status_raw=cells[4] if len(cells) >= 5 else cells[-1],
            section=section,
            version=version_tuple(" | ".join(cells)),
        )
        if spec_id not in rows or section.startswith("✅ Shipped"):
            rows[spec_id] = row
    return rows


def spec_id_from_path(path: Path) -> tuple[str | None, str | None]:
    archive_match = re.match(r"(\d{4}-\d{2}-\d{2})-(S[^-]+)-", path.name)
    if archive_match:
        archive_date, spec_id = archive_match.groups()
        return spec_id, archive_date
    any_match = re.search(r"(S\d{3}(?:[a-z])?(?:-\d+)?(?:'{1,3})?)", path.name)
    if any_match:
        return any_match.group(1), None
    return None, None


def read_all_spec_docs() -> tuple[dict[str, list[SpecDoc]], list[dict[str, object]]]:
    docs: dict[str, list[SpecDoc]] = {}
    file_records: list[dict[str, object]] = []
    paths = sorted(ARCHIVE_DIR.glob("*.md")) + sorted(
        path for path in SPEC_DIR.glob("*.md") if path.name != "spec-roadmap.md"
    )
    for path in paths:
        spec_id, archive_date = spec_id_from_path(path)
        if not spec_id:
            continue
        raw = path.read_bytes()
        text = raw.decode("utf-8")
        relative_path = str(path.relative_to(ROOT))
        title_match = HEADER_TITLE_RE.search(text)
        title = title_match.group(1).strip() if title_match else spec_id
        doc = SpecDoc(
            spec_id=spec_id,
            path=relative_path,
            bytes_read=len(raw),
            lines_read=text.count("\n") + 1,
            sha256=hashlib.sha256(raw).hexdigest(),
            archive_date=archive_date,
            title=title,
            header_status="\n".join(text.splitlines()[:12]),
            text=text,
        )
        docs.setdefault(spec_id, []).append(doc)
        file_records.append(
            {
                "spec_id": spec_id,
                "path": relative_path,
                "bytes": doc.bytes_read,
                "lines": doc.lines_read,
                "sha256": doc.sha256,
                "archive_date": archive_date,
            }
        )
    return docs, file_records


def terminal_status(row: RoadmapRow | None, docs: list[SpecDoc]) -> str:
    if row:
        row_source = f"{row.section} {row.status_raw}".lower()
        if "superseded" in row_source or "取代" in row_source:
            return "superseded"
        if "cancel" in row_source or "取消" in row_source:
            return "cancelled"
        if "deferred" in row_source or "⏸" in row.status_raw:
            return "deferred"
        if row.section.startswith("✅ Shipped") or "✅" in row.status_raw or "shipped" in row_source:
            return "shipped"
    source = "\n".join(doc.header_status for doc in docs)
    lowered = source.lower()
    if "superseded" in lowered or "取代" in lowered:
        return "superseded"
    if "cancel" in lowered or "取消" in lowered:
        return "cancelled"
    if "deferred" in lowered or "⏸" in source:
        return "deferred"
    if "✅" in source or "shipped" in lowered or "done" in lowered:
        return "shipped"
    return "other"


def choose_display_doc(docs: list[SpecDoc]) -> SpecDoc | None:
    return max(docs, key=lambda doc: doc.bytes_read) if docs else None


def story_points_from_score(score: int, parent_or_rollup: bool) -> int:
    if score <= 4:
        return 1
    if score == 5:
        return 2
    if score == 6:
        return 3
    if score <= 9:
        return 5
    if score <= 11:
        return 8
    return 20 if parent_or_rollup else 13


def calculate_spec(spec_id: str, row: RoadmapRow | None, docs: list[SpecDoc]) -> dict[str, object]:
    display_doc = choose_display_doc(docs)
    title = row.title if row else (display_doc.title if display_doc else spec_id)
    label_text = " ".join(part for part in [title, row.status_raw if row else "", row.points_raw if row else ""] if part)
    full_text = "\n\n".join(doc.text for doc in docs)
    text = " ".join(part for part in [label_text, full_text] if part)
    lower = text.lower()
    label_lower = label_text.lower()
    paths = unique_paths(text)
    prod_paths = {
        path
        for path in paths
        if path.startswith(("backend/src/main", "frontend/src", "e2e/", "scripts/", "tools/"))
    }
    docs_paths = {path for path in paths if path.startswith("docs/")}
    flags: list[str] = []

    def flag(name: str, enabled: bool) -> bool:
        if enabled:
            flags.append(name)
        return enabled

    is_docs_only = flag(
        "docs-only",
        text_has(label_lower, ["doc", "docs", "prd", "adr", "architecture", "development-standards", "glossary", "changelog"])
        and bool(docs_paths)
        and not prod_paths,
    )
    is_config_only = flag("config-only", text_has(label_lower, ["config", "設定", ".gcloudignore", "compression", "cors", "dev db persistence"]))
    is_research_only = flag("research/audit", text_has(label_lower, ["research", "audit", "analysis", "poc", "scanner architecture", "meta"]))
    is_test_only = flag("test-only", text_has(label_lower, ["test debt", "e2e critical path backfill", "verification baseline", "jacoco", "verify"]))
    is_copy = flag("copy/message-only", text_has(label_lower, ["copy", "文案", "message", "placeholder", "label", "i18n", "polish", "修正", "tuning"]))
    has_frontend = flag("frontend", "frontend/src/" in lower or "vitest" in lower or "page" in label_lower or "ui" in label_lower or "frontend" in label_lower)
    has_backend = flag("backend", "backend/src/main" in lower or "controller" in lower or "service" in lower or "repository" in lower or "api" in label_lower or "backend" in label_lower)
    has_db = flag("database/schema", text_has(lower, ["flyway", "migration", "postgresql", "pgvector", "jsonb", "schema", "table ", "column"]))
    has_security = flag("security/auth", text_has(lower, ["security", "oauth", "acl", "rbac", "csrf", "permission", "visibility", "xss", "sanitize"]))
    has_external = flag("external-env", text_has(lower, ["cloud run", "cloud build", "gcp", "docker", "native image", "graalvm", "testcontainers", "google oauth", "gemini", "spring ai"]))
    has_e2e = flag("e2e/playwright", text_has(lower, ["playwright", " e2e", "browser", "critical path", "fixture runner"]))
    has_prod = flag("production", text_has(lower, ["production", "prod ", "deploy", "cloud run", "native image", "cloud build"]))
    flag("pivot/debug", text_has((label_lower + " " + row.status_raw.lower()) if row else label_lower, ["pivot", "root cause", "hotfix", "round 2", "production bug", "503", "failures"]))
    has_api_contract = flag("api-contract", text_has(lower, ["api contract", "response", "request", "endpoint", "controller", "contract"]))
    parent_or_rollup = flag(
        "parent-or-rollup",
        bool(row and ("五段" in row.points_raw or "三段" in row.points_raw or "兩段" in row.points_raw or "absorbed" in title.lower()))
        or spec_id in PARENT_OR_ROLLUP_IDS,
    )

    deps = dependency_count(text, spec_id)
    acs = ac_count(text)
    tasks = task_count(text)

    implementation_surface = 1
    if parent_or_rollup or (has_frontend and has_backend and has_db) or len(prod_paths) >= 9 or acs >= 8 or tasks >= 5:
        implementation_surface = 3
    elif (has_frontend and has_backend) or has_db or len(prod_paths) >= 4 or acs >= 4 or tasks >= 2:
        implementation_surface = 2

    state_and_contract = 1
    if (has_db and has_api_contract) or has_security or text_has(lower, ["aggregate", "outbox", "projection", "vector", "permission", "migration"]):
        state_and_contract = 3
    elif has_db or has_api_contract or has_backend:
        state_and_contract = 2

    integration_surface = 1
    if has_prod or text_has(lower, ["cloud run", "cloud build", "native image", "graalvm", "google oauth", "gemini", "spring ai"]):
        integration_surface = 3
    elif has_external or has_e2e or deps >= 3:
        integration_surface = 2

    verification_effort = 1
    if has_e2e or has_prod or "native image" in lower or tasks >= 5:
        verification_effort = 3
    elif has_backend or has_frontend or is_test_only or "testcontainers" in lower or acs >= 4:
        verification_effort = 2

    if is_docs_only:
        implementation_surface = min(implementation_surface, 2 if len(docs_paths) >= 4 else 1)
        state_and_contract = min(state_and_contract, 1)
        integration_surface = min(integration_surface, 1)
        verification_effort = min(verification_effort, 1)
    if is_config_only and not (has_prod or has_external):
        implementation_surface = min(implementation_surface, 1)
        state_and_contract = min(state_and_contract, 1)
        integration_surface = min(integration_surface, 1)
    if is_test_only and not has_e2e:
        state_and_contract = min(state_and_contract, 1)
        integration_surface = min(integration_surface, 1)
    if is_research_only and not (has_backend or has_frontend or has_db or has_prod):
        implementation_surface = min(implementation_surface, 2)
        state_and_contract = min(state_and_contract, 1)
        integration_surface = min(integration_surface, 1)
        verification_effort = min(verification_effort, 1)

    dimensions = {
        "implementation_surface": implementation_surface,
        "state_and_contract": state_and_contract,
        "integration_surface": integration_surface,
        "verification_effort": verification_effort,
    }
    complexity_score = sum(dimensions.values())
    story_points = story_points_from_score(complexity_score, parent_or_rollup)
    if story_points == 20 and not parent_or_rollup:
        story_points = 13
    if is_docs_only or (is_research_only and not (has_backend or has_frontend or has_db or has_prod)):
        story_points = min(story_points, 3)
    if is_config_only and not (has_prod or has_external):
        story_points = min(story_points, 3)
    if is_copy and len(prod_paths) == 0 and tasks == 0:
        story_points = 1

    status = terminal_status(row, docs)
    roadmap_points_raw = row.points_raw if row else None
    exclusion_reason = None
    if "META" in (roadmap_points_raw or "") or "META" in title:
        exclusion_reason = "META"
    elif status != "shipped":
        exclusion_reason = status
    elif spec_id in ROLLED_UP_CHILDREN:
        exclusion_reason = "rolled_up_child"

    included_in_shipped_total = exclusion_reason is None
    included_in_outcome_total = status not in NOT_EXECUTED_STATUSES
    archive_dates = [doc.archive_date for doc in docs if doc.archive_date]
    archive_date = min(archive_dates) if archive_dates else None
    marketing_period = False
    if row and row.version:
        marketing_period = row.version <= MARKETING_CUTOFF_VERSION
    elif archive_date:
        marketing_period = date.fromisoformat(archive_date) <= MARKETING_CUTOFF_DATE

    return {
        "spec_id": spec_id,
        "title": title,
        "story_points": story_points,
        "all_spec_story_points": story_points,
        "accounted_story_points": story_points if included_in_outcome_total else 0,
        "shipped_total_story_points": story_points if included_in_shipped_total else 0,
        "marketing_period_story_points": story_points if marketing_period else 0,
        "marketing_accounted_story_points": story_points if included_in_outcome_total and marketing_period else 0,
        "marketing_shipped_story_points": story_points if included_in_shipped_total and marketing_period else 0,
        "complexity_score": complexity_score,
        "dimensions": dimensions,
        "evidence_flags": flags,
        "counts": {
            "prod_paths": len(prod_paths),
            "doc_paths": len(docs_paths),
            "ac_markers": acs,
            "task_markers": tasks,
            "referenced_specs": deps,
            "source_files_read": len(docs),
            "bytes_read": sum(doc.bytes_read for doc in docs),
            "lines_read": sum(doc.lines_read for doc in docs),
            "parent_or_rollup": parent_or_rollup,
        },
        "terminal_status": status,
        "included_in_outcome_total": included_in_outcome_total,
        "included_in_shipped_total": included_in_shipped_total,
        "marketing_period": marketing_period,
        "exclusion_reason": exclusion_reason,
        "outcome_exclusion_reason": status if not included_in_outcome_total else None,
        "roadmap_section": row.section if row else None,
        "roadmap_points_raw": roadmap_points_raw,
        "version": version_text(row.version if row else None),
        "archive_date": archive_date,
        "source_paths": [doc.path for doc in docs],
        "source_sha256s": {doc.path: doc.sha256 for doc in docs},
        "rationale": f"complexity_score={complexity_score}; dimensions={dimensions}; story_points={story_points}",
    }


def summarize(records: list[dict[str, object]], file_records: list[dict[str, object]]) -> dict[str, object]:
    accounted = [record for record in records if record["included_in_outcome_total"]]
    not_executed = [record for record in records if not record["included_in_outcome_total"]]
    shipped = [record for record in records if record["included_in_shipped_total"]]
    marketing_period = [record for record in records if record["marketing_period"]]
    marketing_accounted = [record for record in records if record["included_in_outcome_total"] and record["marketing_period"]]
    marketing_shipped = [record for record in records if record["included_in_shipped_total"] and record["marketing_period"]]
    all_spec_points = sum(int(record["story_points"]) for record in records)
    accounted_points = sum(int(record["story_points"]) for record in accounted)
    marketing_period_points = sum(int(record["story_points"]) for record in marketing_period)
    return {
        "generated_by": "tools/calculate_story_points.py",
        "model": "MVP complexity-only Fibonacci story points",
        "source_read_policy": "Every spec archive file is read in full via read_bytes(); all spec records receive story_points, while outcome totals exclude cancelled/deferred/other records that were not executed.",
        "deck": list(FIBONACCI),
        "source_files_read": len(file_records),
        "source_bytes_read": sum(int(record["bytes"]) for record in file_records),
        "source_lines_read": sum(int(record["lines"]) for record in file_records),
        "total_spec_records": len(records),
        "spec_records_with_source_files": sum(1 for record in records if record["source_paths"]),
        "spec_records_without_source_files": sum(1 for record in records if not record["source_paths"]),
        "totals": {
            "all_spec_records": len(records),
            "all_spec_story_points": all_spec_points,
            "accounted_specs": len(accounted),
            "accounted_story_points": accounted_points,
            "not_executed_specs": len(not_executed),
            "not_executed_story_points": all_spec_points - accounted_points,
            "shipped_specs": len(shipped),
            "shipped_story_points": sum(int(record["story_points"]) for record in shipped),
            "marketing_period_specs": len(marketing_period),
            "marketing_period_story_points": marketing_period_points,
            "marketing_accounted_specs": len(marketing_accounted),
            "marketing_accounted_story_points": sum(int(record["story_points"]) for record in marketing_accounted),
            "marketing_shipped_specs": len(marketing_shipped),
            "marketing_shipped_story_points": sum(int(record["story_points"]) for record in marketing_shipped),
            "excluded_specs": len(records) - len(shipped),
            "excluded_story_points": all_spec_points - sum(int(record["story_points"]) for record in shipped),
        },
        "distribution": {
            "all_spec_points": dict(sorted(Counter(str(record["story_points"]) for record in records).items(), key=lambda item: int(item[0]))),
            "accounted_points": dict(sorted(Counter(str(record["story_points"]) for record in accounted).items(), key=lambda item: int(item[0]))),
            "shipped_points": dict(sorted(Counter(str(record["story_points"]) for record in shipped).items(), key=lambda item: int(item[0]))),
            "complexity_scores": dict(sorted(Counter(str(record["complexity_score"]) for record in records).items(), key=lambda item: int(item[0]))),
        },
        "marketing_outcome": {
            "story_points": accounted_points,
            "weekly_points": float((Decimal(accounted_points) / DAYS * Decimal("7")).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)),
            "cost_per_point": float((TOKEN_COST / Decimal(accounted_points)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)),
        },
    }


def build_payload() -> dict[str, object]:
    rows = parse_roadmap()
    docs_by_spec, file_records = read_all_spec_docs()
    spec_ids = sorted(set(rows) | set(docs_by_spec), key=sort_key)
    records = [calculate_spec(spec_id, rows.get(spec_id), docs_by_spec.get(spec_id, [])) for spec_id in spec_ids]
    return {
        "summary": summarize(records, file_records),
        "source_file_records": file_records,
        "records": records,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    payload = build_payload()
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(payload["summary"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
