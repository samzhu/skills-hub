#!/usr/bin/env python3
"""Calculate shipped Grimo specs on the Fibonacci story-point deck.

The script uses spec and roadmap evidence instead of converting old labels
such as ``XS(7)`` directly. Old numeric values are kept only as legacy
metadata for comparison.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from dataclasses import asdict, dataclass
from datetime import date
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parents[1]
ROADMAP = ROOT / "docs/grimo/specs/spec-roadmap.md"
ARCHIVE = ROOT / "docs/grimo/specs/archive"
DEFAULT_OUTPUT = ROOT / "docs/grimo/specs/story-points-2026-06-02.json"

MARKETING_CUTOFF_VERSION = (4, 86, 0)
MARKETING_CUTOFF_DATE = date(2026, 5, 19)

SPEC_ID_RE = re.compile(r"\bS\d{3}(?:[a-z])?(?:-\d+)?(?:'{1,3})?\b")
VERSION_RE = re.compile(r"v(\d+)\.(\d+)\.(\d+)")
LEGACY_POINT_RE = re.compile(r"(?:XS|S-M|S|M-L|M|L|XL)\((\d+(?:\.\d+)?)(?:-(\d+(?:\.\d+)?))?\)")
LEGACY_LABEL_RE = re.compile(r"\b(XS|S-M|M-L|XL|S|M|L)\(")
PATH_RE = re.compile(r"(?:backend|frontend|e2e|docs|scripts|tools)/[A-Za-z0-9_./'{}:@+-]+")

FIBONACCI = (1, 2, 3, 5, 8, 13, 20)

# These rows were historical split children under a parent shipped package.
# They are listed for visibility but not counted in outcome totals.
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


@dataclass
class RoadmapRow:
    spec_id: str
    title: str
    points_raw: str
    status_raw: str
    section: str
    version: tuple[int, int, int] | None


@dataclass
class ArchiveDoc:
    spec_id: str
    path: str
    source_paths: list[str]
    archive_file_records: list[dict[str, object]]
    bytes_read: int
    archive_date: str | None
    title: str
    header_status: str
    text: str


@dataclass
class StoryPointRecord:
    spec_id: str
    title: str
    source_path: str | None
    archive_source_paths: list[str]
    archive_file_count: int
    archive_bytes_read: int
    archive_sha256s: dict[str, str]
    roadmap_section: str | None
    legacy_points_raw: str | None
    legacy_numeric_points: float | None
    version: str | None
    archive_date: str | None
    terminal_status: str
    counted: bool
    marketing_counted: bool
    exclusion_reason: str | None
    story_points: int
    diagnostic_score: int
    dimensions: dict[str, int]
    evidence_flags: list[str]
    rationale: str


def version_tuple(text: str) -> tuple[int, int, int] | None:
    match = VERSION_RE.search(text)
    if not match:
        return None
    return tuple(int(part) for part in match.groups())


def version_text(version: tuple[int, int, int] | None) -> str | None:
    if version is None:
        return None
    return f"v{version[0]}.{version[1]}.{version[2]}"


def legacy_numeric(points_raw: str | None) -> float | None:
    if not points_raw or "META" in points_raw or points_raw.strip() in {"—", "-", "TBD"}:
        return None
    arrow_part = points_raw.split("->")[-1].split("→")[-1]
    match = LEGACY_POINT_RE.search(arrow_part)
    if match:
        start = float(match.group(1))
        end = float(match.group(2)) if match.group(2) else start
        return (start + end) / 2
    plain = re.search(r"\b(\d+(?:\.\d+)?)\b", arrow_part)
    return float(plain.group(1)) if plain else None


def legacy_label(points_raw: str | None) -> str | None:
    if not points_raw:
        return None
    arrow_part = points_raw.split("->")[-1].split("→")[-1]
    match = LEGACY_LABEL_RE.search(arrow_part)
    return match.group(1) if match else None


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
        spec_id = cells[0]
        if section.startswith("Milestones"):
            continue
        status_raw = cells[4] if len(cells) >= 5 else cells[-1]
        version = version_tuple(" | ".join(cells))
        row = RoadmapRow(
            spec_id=spec_id,
            title=cells[1],
            points_raw=cells[2],
            status_raw=status_raw,
            section=section,
            version=version,
        )
        # Prefer explicit shipped rows over older active/backlog duplicates.
        if spec_id not in rows or section.startswith("✅ Shipped"):
            rows[spec_id] = row
    return rows


def parse_archives() -> dict[str, ArchiveDoc]:
    docs: dict[str, ArchiveDoc] = {}
    for path in sorted(ARCHIVE.glob("*.md")):
        match = re.match(r"(\d{4}-\d{2}-\d{2})-(S[^-]+)-", path.name)
        if not match:
            continue
        archive_date, spec_id = match.groups()
        raw = path.read_bytes()
        text = raw.decode("utf-8")
        relative_path = str(path.relative_to(ROOT))
        archive_file_record = {
            "path": relative_path,
            "bytes": len(raw),
            "lines": text.count("\n") + 1,
            "sha256": hashlib.sha256(raw).hexdigest(),
        }
        title_match = re.search(r"^#\s+(.+)$", text, flags=re.MULTILINE)
        title = title_match.group(1).strip() if title_match else spec_id
        header = "\n".join(text.splitlines()[:12])
        doc = ArchiveDoc(
            spec_id=spec_id,
            path=relative_path,
            source_paths=[relative_path],
            archive_file_records=[archive_file_record],
            bytes_read=len(raw),
            archive_date=archive_date,
            title=title,
            header_status=header,
            text=text,
        )
        if spec_id not in docs:
            docs[spec_id] = doc
            continue

        existing = docs[spec_id]
        existing.source_paths.append(relative_path)
        existing.archive_file_records.append(archive_file_record)
        existing.bytes_read += len(raw)
        existing.text += f"\n\n--- Archive source: {relative_path} ---\n\n{text}"
        # Keep the largest file as the display title/header source, but retain
        # every duplicate archive body in `text` and `archive_file_records`.
        if len(raw) > max(item["bytes"] for item in existing.archive_file_records if item["path"] != relative_path):
            existing.path = relative_path
            existing.archive_date = archive_date
            existing.title = title
            existing.header_status = header
    return docs


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


def terminal_status(row: RoadmapRow | None, doc: ArchiveDoc | None) -> str:
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
    source = " ".join(part for part in [row.status_raw if row else "", doc.header_status if doc else ""] if part)
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


def diagnostic_score_to_points(score: int, parent_or_rollup: bool) -> int:
    if score <= 6:
        return 1
    if score <= 8:
        return 2
    if score <= 10:
        return 3
    if score <= 12:
        return 5
    if score <= 14:
        return 8
    if score <= 16:
        return 13
    return 20 if parent_or_rollup else 13


def step(points: int, delta: int) -> int:
    index = FIBONACCI.index(points)
    index = max(0, min(len(FIBONACCI) - 1, index + delta))
    return FIBONACCI[index]


def estimate_points(spec_id: str, title: str, row: RoadmapRow | None, doc: ArchiveDoc | None) -> tuple[int, int, dict[str, int], list[str], str]:
    label_text = " ".join(part for part in [title, row.status_raw if row else "", row.points_raw if row else ""] if part)
    doc_text = doc.text if doc else ""
    text = " ".join(part for part in [label_text, doc_text] if part)
    lower = text.lower()
    label_lower = label_text.lower()
    paths = unique_paths(text)
    prod_paths = {
        path
        for path in paths
        if (
            path.startswith("backend/src/main")
            or path.startswith("frontend/src")
            or path.startswith("e2e/")
            or path.startswith("scripts/")
            or path.startswith("tools/")
        )
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
        and not prod_paths
    )
    is_config_only = flag("config-only", text_has(label_lower, ["config", "設定", ".gcloudignore", "compression", "cors", "dev db persistence"]))
    is_test_only = flag("test-only", text_has(label_lower, ["test debt", "e2e critical path backfill", "verification baseline", "jacoco", "verify"]))
    has_frontend = flag("frontend", "frontend/src/" in lower or "vitest" in lower or "page" in label_lower or "ui" in label_lower or "frontend" in label_lower)
    has_backend = flag("backend", "backend/src/main" in lower or "controller" in lower or "service" in lower or "repository" in lower or "api" in label_lower or "backend" in label_lower)
    has_db = flag("database/schema", text_has(lower, ["flyway", "migration", "postgresql", "pgvector", "jsonb", "schema", "table ", "column"]))
    has_security = flag("security/auth", text_has(lower, ["security", "oauth", "acl", "rbac", "csrf", "permission", "visibility", "xss", "sanitize"]))
    has_external = flag("external-env", text_has(lower, ["cloud run", "cloud build", "gcp", "docker", "native image", "graalvm", "testcontainers", "google oauth", "gemini", "spring ai"]))
    has_e2e = flag("e2e/playwright", text_has(lower, ["playwright", " e2e", "browser", "critical path", "fixture runner"]))
    has_prod = flag("production", text_has(lower, ["production", "prod ", "deploy", "cloud run", "native image", "cloud build"]))
    has_pivot = flag("pivot/debug", text_has(label_lower + " " + row.status_raw.lower() if row else label_lower, ["pivot", "root cause", "hotfix", "round 2", "production bug", "503", "failures"]))
    has_research = flag("research/audit", text_has(label_lower, ["research", "audit", "poc", "scanner architecture", "meta"]))
    has_api_contract = flag("api-contract", text_has(lower, ["api contract", "response", "request", "endpoint", "controller", "contract"]))
    parent_or_rollup = flag(
        "parent-or-rollup",
        bool(
            row
            and (
                "五段" in row.points_raw
                or "三段" in row.points_raw
                or "兩段" in row.points_raw
                or "absorbed" in title.lower()
            )
        )
        or spec_id in {"S014", "S147", "S160", "S161", "S163", "S164"},
    )

    deps = dependency_count(text, spec_id)
    acs = ac_count(text)
    tasks = task_count(text)

    tech_risk = 1
    if has_external or text_has(lower, ["spring modulith", "spring ai", "graalvm", "native", "vector", "outbox"]):
        tech_risk = 3
    elif has_db or has_security or has_backend:
        tech_risk = 2

    uncertainty = 1
    if has_pivot or has_research or text_has(lower, ["production upload", "not functional", "audit polish"]):
        uncertainty = 3
    elif "rework" in lower or "v2" in lower or "compatibility" in lower:
        uncertainty = 2

    dependencies = 1
    if deps >= 3 or (has_external and deps >= 1):
        dependencies = 3
    elif deps >= 1 or has_external:
        dependencies = 2

    scope = 1
    if parent_or_rollup or (has_frontend and has_backend and has_db) or len(prod_paths) >= 9 or acs >= 8 or tasks >= 5:
        scope = 3
    elif (has_frontend and has_backend) or has_db or len(prod_paths) >= 4 or acs >= 4 or tasks >= 2:
        scope = 2

    testing = 1
    if has_e2e or has_prod or "testcontainers" in lower or "native image" in lower:
        testing = 3
    elif has_backend or has_frontend or is_test_only:
        testing = 2

    reversibility = 1
    if has_db or has_security or has_prod or text_has(lower, ["aggregate", "outbox", "permission", "migration"]):
        reversibility = 3
    elif has_api_contract or (has_frontend and has_backend):
        reversibility = 2

    if is_docs_only:
        tech_risk = min(tech_risk, 1)
        testing = min(testing, 1)
        reversibility = min(reversibility, 1)
        scope = min(scope, 2 if len(docs_paths) >= 4 else 1)
    if is_config_only and not (has_prod or has_external):
        scope = min(scope, 1)
        reversibility = min(reversibility, 2)
    if is_test_only and not has_e2e:
        tech_risk = min(tech_risk, 2)
        reversibility = min(reversibility, 1)

    dimensions = {
        "tech_risk": tech_risk,
        "uncertainty": uncertainty,
        "dependencies": dependencies,
        "scope": scope,
        "testing": testing,
        "reversibility": reversibility,
    }
    diagnostic = sum(dimensions.values())
    label = legacy_label(row.points_raw if row else None)
    label_points = {
        "XS": 2,
        "S": 3,
        "S-M": 5,
        "M": 8,
        "M-L": 8,
        "L": 13,
        "XL": 20,
    }.get(label or "")
    if label:
        flags.append(f"legacy-roadmap-label:{label}")
    points = label_points or diagnostic_score_to_points(diagnostic, parent_or_rollup)

    upward_reasons = 0
    if parent_or_rollup or len(prod_paths) >= 9 or tasks >= 5:
        upward_reasons += 1
    if has_frontend and has_backend and has_db:
        upward_reasons += 1
    if has_e2e or has_prod or has_external:
        upward_reasons += 1
    if has_pivot:
        upward_reasons += 1
    if diagnostic >= 17:
        upward_reasons += 1

    downward_reasons = 0
    if is_docs_only or (is_config_only and not has_prod):
        downward_reasons += 1
    if text_has(label_lower, ["copy", "文案", "message", "placeholder", "label", "i18n", "polish", "修正", "tuning"]):
        downward_reasons += 1
    if len(prod_paths) <= 1 and acs <= 3 and tasks == 0 and not (has_db or has_external):
        downward_reasons += 1
    if is_test_only and not has_e2e:
        downward_reasons += 1

    if upward_reasons >= 3:
        points = step(points, 2)
    elif upward_reasons >= 1:
        points = step(points, 1)

    if downward_reasons >= 2:
        points = step(points, -2)
    elif downward_reasons == 1:
        points = step(points, -1)

    if is_docs_only:
        points = min(points, 3)
    if is_config_only and not has_prod:
        points = min(points, 3)
    if "message polish" in label_lower or "i18n" in label_lower or "placeholder" in label_lower or "文案" in label_lower:
        points = min(points, 2 if not (has_backend and has_frontend) else 3)
    if points == 20 and not parent_or_rollup:
        points = 13
    if parent_or_rollup and (diagnostic >= 17 or (label == "XL")):
        points = 20

    evidence = [
        f"{len(prod_paths)} production/tool paths",
        f"{acs} AC markers",
        f"{tasks} task markers",
        f"{deps} referenced specs",
    ]
    evidence.extend(flags)
    rationale = (
        f"story_points 依 full spec evidence 計算：{', '.join(evidence[:4])}；"
        f"診斷分 {diagnostic}，上調訊號 {upward_reasons}，下調訊號 {downward_reasons}，最後 {points} 點。"
    )
    return points, diagnostic, dimensions, evidence, rationale


def should_marketing_count(row: RoadmapRow | None, doc: ArchiveDoc | None) -> bool:
    if row and row.version:
        return row.version <= MARKETING_CUTOFF_VERSION
    if doc and doc.archive_date:
        try:
            doc_date = date.fromisoformat(doc.archive_date)
            return doc_date <= MARKETING_CUTOFF_DATE
        except ValueError:
            return False
    return False


def build_story_point_records() -> list[StoryPointRecord]:
    rows = parse_roadmap()
    docs = parse_archives()
    spec_ids = sorted(set(rows) | set(docs), key=sort_key)
    results: list[StoryPointRecord] = []

    for spec_id in spec_ids:
        row = rows.get(spec_id)
        doc = docs.get(spec_id)
        title = row.title if row else (doc.title if doc else spec_id)
        status = terminal_status(row, doc)
        points_raw = row.points_raw if row else None
        legacy = legacy_numeric(points_raw)
        points, diagnostic, dimensions, evidence, rationale = estimate_points(spec_id, title, row, doc)

        exclusion_reason = None
        if "META" in (points_raw or "") or "META" in title:
            exclusion_reason = "META row records grouping/research management, not direct delivery."
        elif status != "shipped":
            exclusion_reason = f"Terminal status is {status}."
        elif spec_id in ROLLED_UP_CHILDREN:
            exclusion_reason = "Counted inside the shipped parent split package to avoid double counting."

        counted = exclusion_reason is None
        marketing_counted = counted and should_marketing_count(row, doc)

        results.append(
            StoryPointRecord(
                spec_id=spec_id,
                title=title,
                source_path=doc.path if doc else None,
                archive_source_paths=doc.source_paths if doc else [],
                archive_file_count=len(doc.archive_file_records) if doc else 0,
                archive_bytes_read=doc.bytes_read if doc else 0,
                archive_sha256s={str(item["path"]): str(item["sha256"]) for item in doc.archive_file_records} if doc else {},
                roadmap_section=row.section if row else None,
                legacy_points_raw=points_raw,
                legacy_numeric_points=legacy,
                version=version_text(row.version if row else None),
                archive_date=doc.archive_date if doc else None,
                terminal_status=status,
                counted=counted,
                marketing_counted=marketing_counted,
                exclusion_reason=exclusion_reason,
                story_points=points,
                diagnostic_score=diagnostic,
                dimensions=dimensions,
                evidence_flags=evidence,
                rationale=rationale,
            )
        )
    return results


def sort_key(spec_id: str) -> tuple[int, str]:
    num = int(re.search(r"\d+", spec_id).group(0))
    return num, spec_id


def summarize(records: list[StoryPointRecord]) -> dict[str, object]:
    counted = [record for record in records if record.counted]
    marketing = [record for record in records if record.marketing_counted]
    excluded = [record for record in records if not record.counted]
    by_points: dict[str, int] = {}
    for record in counted:
        by_points[str(record.story_points)] = by_points.get(str(record.story_points), 0) + 1
    archive_files_read = sum(record.archive_file_count for record in records)
    archive_bytes_read = sum(record.archive_bytes_read for record in records)
    return {
        "generated_by": "tools/calculate_story_points.py",
        "model": "Fibonacci story points with optional six-factor diagnostic",
        "source_read_policy": "Every archive spec file is read in full via read_bytes(); duplicate archive files for the same SpecID are merged into that SpecID record.",
        "deck": list(FIBONACCI),
        "marketing_cutoff": {
            "version": "v4.86.0",
            "date": MARKETING_CUTOFF_DATE.isoformat(),
        },
        "totals": {
            "all_counted_specs": len(counted),
            "all_counted_story_points": sum(record.story_points for record in counted),
            "marketing_counted_specs": len(marketing),
            "marketing_story_points": sum(record.story_points for record in marketing),
            "excluded_specs": len(excluded),
            "archive_files_read": archive_files_read,
            "archive_bytes_read": archive_bytes_read,
        },
        "distribution": by_points,
        "exclusion_notes": {
            "META": "Grouping/research management rows are not delivery points.",
            "cancelled/superseded/deferred": "Terminal non-delivery states count as 0.",
            "rolled_up_children": "Historical split children are listed but counted inside their shipped parent package.",
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    records = build_story_point_records()
    payload = {
        "summary": summarize(records),
        "records": [asdict(record) for record in records],
    }
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(payload["summary"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
