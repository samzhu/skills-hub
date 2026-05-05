# Distribution Targets

A skill must be portable across hosts. Each host has its own loading path.

## Claude Code

- Project skills live in `.claude/skills/<name>/`.
- User-global skills live in `~/.claude/skills/<name>/`.
- The harness auto-discovers skills on session start. No upload step.

## claude.ai

- Zip the skill folder. The zip's top-level folder name must equal the skill `name`.
- Upload via Settings > Capabilities > Skills.
- The host enforces a total upload size limit. Verify against current host docs at distribution time — keep `assets/` lean and avoid bundling binaries that belong elsewhere.

## Anthropic API

- Skills are managed via the `/v1/skills` endpoint.
- Attach skills to a Messages API request via the `container.skills` parameter.
- Requires the Code Execution Tool beta.

## Organization-wide deploy

- Workspace admins can deploy skills across an org with centralized management and automatic updates.
- Skills deployed this way must pass the same audit checklist — admins inherit the failure modes of every skill they ship.

## Recommended Repository Layout

```
my-skill-repo/
├── README.md                      ← human-facing docs (OUTSIDE the skill)
├── examples/                      ← screenshots, sample prompts
├── LICENSE
└── <skill-name>/                  ← the skill itself
    ├── SKILL.md
    ├── scripts/
    ├── references/
    └── assets/
```

The repo's `README.md` (for humans) MUST live outside the skill folder. Inside the folder it is a STRUCTURE ERROR.

## Pre-distribution Checklist

- [ ] Folder zips successfully and unzips into a folder matching `name`.
- [ ] `python3 scripts/validate-skill.py --skill-dir <path>` passes clean.
- [ ] Repository `README.md` (outside the skill folder) explains: what the skill does, how to install in each target host, sample prompts, screenshots if relevant.
- [ ] `metadata.version` bumped on each release.
- [ ] `license` declared in frontmatter (if distributed publicly).
- [ ] `compatibility` lists every host requirement (Node version, MCP server, network access).
- [ ] Tested on at least one target host other than the host it was developed on (portability proof).
