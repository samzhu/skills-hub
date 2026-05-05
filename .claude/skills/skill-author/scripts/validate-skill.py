#!/usr/bin/env python3
"""Validate a skill against the agentskills.io spec.

Modes:
    --name X --description Y     Validate raw metadata strings.
    --skill-dir PATH             Validate an existing skill folder end-to-end.

Exit code 0 on pass, 1 on any violation. All findings printed to stderr,
one per line, with a category prefix the agent can route on (NAME ERROR,
DESCRIPTION ERROR, STYLE ERROR, STRUCTURE ERROR, LENGTH ERROR,
COMPATIBILITY ERROR, FRONTMATTER ERROR, PATH ERROR).
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

NAME_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
NAME_MIN, NAME_MAX = 1, 64
DESC_MAX = 1024
COMPAT_MAX = 500
SKILL_MD_MAX_LINES = 500
ALLOWED_SUBDIRS = {"scripts", "references", "assets"}
FORBIDDEN_FILES = {
    "README.md", "README.markdown", "README.txt",
    "CHANGELOG.md", "CHANGELOG.txt",
    "INSTALLATION.md", "INSTALL.md", "INSTALL.txt",
    "CONTRIBUTING.md",
}
FORBIDDEN_PREFIXES = ("claude", "anthropic")
FIRST_SECOND_PERSON = {"i", "me", "my", "mine",
                       "we", "us", "our", "ours",
                       "you", "your", "yours"}
KEY_RE = re.compile(r"^([a-zA-Z][\w-]*):\s*(.*)$")


def validate_name(name: str, errors: list[str]) -> None:
    if not (NAME_MIN <= len(name) <= NAME_MAX):
        errors.append(
            f"NAME ERROR: '{name}' is {len(name)} chars; must be 1-64."
        )
    if not NAME_RE.match(name):
        errors.append(
            f"NAME ERROR: '{name}' must be kebab-case (lowercase, digits, "
            "single hyphens; no leading/trailing/consecutive hyphens)."
        )
    lower = name.lower()
    for prefix in FORBIDDEN_PREFIXES:
        if lower.startswith(prefix):
            errors.append(
                f"NAME ERROR: '{name}' must not start with '{prefix}'. "
                "Reserved by Anthropic."
            )


def validate_description(desc: str, errors: list[str]) -> None:
    if len(desc) == 0:
        errors.append("DESCRIPTION ERROR: empty description.")
        return
    if len(desc) > DESC_MAX:
        errors.append(
            f"DESCRIPTION ERROR: {len(desc)} chars; must be ≤ {DESC_MAX}."
        )
    if "<" in desc or ">" in desc:
        errors.append(
            "DESCRIPTION ERROR: contains '<' or '>'. Angle brackets are "
            "forbidden in frontmatter (prompt-injection vector — frontmatter "
            "is loaded into the system prompt)."
        )
    words = set(re.findall(r"[a-zA-Z']+", desc.lower()))
    bad = sorted(words & FIRST_SECOND_PERSON)
    if bad:
        errors.append(
            f"STYLE ERROR: description uses first/second-person words {bad}. "
            "Rewrite in third-person imperative."
        )
    if "use when" not in desc.lower():
        errors.append(
            "DESCRIPTION ERROR: missing positive trigger. Include "
            "'Use when ...' with paraphrased phrases users would say."
        )
    if not re.search(r"(don'?t use|do not use|avoid|skip when|do not trigger)",
                     desc.lower()):
        errors.append(
            "DESCRIPTION ERROR: missing negative trigger. Include "
            "'Don't use for ...' or equivalent to prevent over-triggering."
        )


def parse_frontmatter(text: str) -> tuple[dict[str, str], int]:
    """Return (frontmatter_dict, body_line_count).

    Supports plain inline values and folded/literal block scalars (`>`, `|`,
    `>-`, `|-`). Nested objects (e.g., metadata:) are read shallowly — only
    top-level keys are extracted. That's sufficient for the fields this
    validator inspects (name, description, compatibility).
    """
    if not text.startswith("---"):
        raise ValueError("SKILL.md missing leading '---'.")
    end = text.find("\n---", 3)
    if end < 0:
        raise ValueError("SKILL.md frontmatter not closed with '---'.")
    fm_text = text[4:end]
    body = text[end + 4:]

    fm: dict[str, str] = {}
    current_key: str | None = None
    folded: list[str] = []

    def flush():
        nonlocal current_key, folded
        if current_key is not None:
            fm[current_key] = " ".join(s.strip() for s in folded).strip()
        current_key = None
        folded = []

    for raw in fm_text.splitlines():
        is_indented = raw.startswith((" ", "\t"))
        if not is_indented and raw.strip():
            m = KEY_RE.match(raw)
            if m:
                flush()
                key, val = m.group(1), m.group(2).strip()
                if val in (">", ">-", "|", "|-"):
                    current_key = key
                    folded = []
                elif val == "":
                    fm[key] = ""
                else:
                    fm[key] = val.strip().strip('"').strip("'")
                continue
        if current_key is not None and (is_indented or raw == ""):
            folded.append(raw)
    flush()

    body_lines = body.count("\n")
    return fm, body_lines


def validate_skill_dir(path: Path, errors: list[str]) -> None:
    if not path.exists():
        errors.append(f"PATH ERROR: '{path}' does not exist.")
        return
    if not path.is_dir():
        errors.append(f"PATH ERROR: '{path}' is not a directory.")
        return

    skill_md = path / "SKILL.md"
    if not skill_md.is_file():
        errors.append(
            f"STRUCTURE ERROR: '{path}/SKILL.md' missing or wrong case "
            "(must be exactly 'SKILL.md')."
        )
        return

    text = skill_md.read_text(encoding="utf-8")
    try:
        fm, body_lines = parse_frontmatter(text)
    except ValueError as e:
        errors.append(f"FRONTMATTER ERROR: {e}")
        return

    name = fm.get("name", "").strip()
    desc = fm.get("description", "").strip()
    compat = fm.get("compatibility", "").strip()

    if not name:
        errors.append("FRONTMATTER ERROR: 'name' field missing or empty.")
    else:
        validate_name(name, errors)
        if name != path.name:
            errors.append(
                f"NAME ERROR: frontmatter name '{name}' does not match "
                f"folder name '{path.name}'."
            )

    if not desc:
        errors.append("FRONTMATTER ERROR: 'description' field missing or empty.")
    else:
        validate_description(desc, errors)

    if compat and len(compat) > COMPAT_MAX:
        errors.append(
            f"COMPATIBILITY ERROR: {len(compat)} chars; must be ≤ {COMPAT_MAX}."
        )

    if body_lines > SKILL_MD_MAX_LINES:
        errors.append(
            f"LENGTH ERROR: SKILL.md body has {body_lines} lines; soft limit "
            f"{SKILL_MD_MAX_LINES}. Move bulk content to references/."
        )

    for child in path.iterdir():
        if child.name == "SKILL.md":
            continue
        if child.is_dir() and child.name not in ALLOWED_SUBDIRS:
            errors.append(
                f"STRUCTURE ERROR: unexpected subdirectory '{child.name}/'. "
                f"Only {sorted(ALLOWED_SUBDIRS)} allowed."
            )
        if child.is_file() and child.name in FORBIDDEN_FILES:
            errors.append(
                f"STRUCTURE ERROR: '{child.name}' is human-targeted; remove "
                "from skill folder. Skills are for agents, not humans."
            )

    for sub in ALLOWED_SUBDIRS:
        sub_path = path / sub
        if not sub_path.is_dir():
            continue
        entries = list(sub_path.iterdir())
        if not entries:
            errors.append(
                f"STRUCTURE ERROR: '{sub}/' exists but is empty. "
                "Remove unused subdirectories."
            )
            continue
        for entry in sub_path.rglob("*"):
            if entry.is_dir() and entry.parent != sub_path:
                errors.append(
                    f"STRUCTURE ERROR: nested directory "
                    f"'{entry.relative_to(path)}'. Subdirectories must be "
                    "flat (one level deep)."
                )
            if entry.is_file():
                rel = entry.relative_to(sub_path)
                if len(rel.parts) > 1:
                    errors.append(
                        f"STRUCTURE ERROR: '{sub}/{rel}' is nested. "
                        "Files must be exactly one level deep."
                    )


def main() -> None:
    ap = argparse.ArgumentParser(
        description="Validate a skill against the agentskills.io spec."
    )
    ap.add_argument("--name", help="Skill name to validate.")
    ap.add_argument("--description", help="Skill description to validate.")
    ap.add_argument("--skill-dir", help="Path to a skill folder to validate.")
    args = ap.parse_args()

    errors: list[str] = []

    if args.skill_dir:
        validate_skill_dir(Path(args.skill_dir), errors)
    elif args.name is not None and args.description is not None:
        validate_name(args.name, errors)
        validate_description(args.description, errors)
    else:
        ap.error("Provide --skill-dir PATH, OR both --name and --description.")

    if errors:
        print("\n".join(errors), file=sys.stderr)
        sys.exit(1)
    print("SUCCESS: skill validates against agentskills.io spec.")
    sys.exit(0)


if __name__ == "__main__":
    main()
