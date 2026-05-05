# Skill Testing Protocol

Run all three layers before shipping. The frontmatter alone determines triggering — test it in isolation first.

## Layer 1: Discovery

Goal: confirm the agent loads the skill on real user phrasings, and does NOT load it on adjacent prompts.

Paste the frontmatter into a fresh LLM context with this prompt:

> An agent decides whether to load a skill based entirely on the YAML below. Based strictly on this metadata:
> 1. Generate 3 realistic user prompts you are 100% confident SHOULD trigger this skill.
> 2. Generate 3 prompts that sound similar but should NOT trigger.
> 3. Critique the description: too broad? too narrow? Suggest a rewrite.
>
> ```
> [paste frontmatter here]
> ```

Interpretation:
- LLM's "should NOT trigger" set overlaps with intended use cases → description too broad. Add domain qualifiers and stronger negative triggers.
- LLM's "should trigger" set misses real user phrasings → description too narrow. Add the missing paraphrased triggers.
- LLM's rewrite suggests a fundamentally different scope → revisit Step A1 intent capture.

## Layer 2: Logic / Functional

Goal: confirm the procedures are deterministic and the agent does not have to guess.

Feed full SKILL.md plus the directory tree to a fresh LLM:

> Act as an autonomous agent that just triggered this skill on the request: "[realistic user prompt]". Walk step by step through the procedures. For each step, state: (1) what you do, (2) which file or script you read or run, (3) any point where the instructions force you to guess or hallucinate. Do not fix anything — only report.
>
> Directory tree:
> ```
> [paste tree]
> ```
>
> SKILL.md:
> ```
> [paste full SKILL.md]
> ```

Every "guess" the LLM flags is an instruction defect. Fix each before shipping by adding explicit values, reference files, or scripted determinism.

## Layer 3: Edge / Adversarial

Goal: surface unsupported configurations, missing fallbacks, and silent failure modes.

> Switch roles. Act as a ruthless QA tester. Ask 3-5 highly specific, challenging questions about edge cases, failure states, or missing fallbacks in this SKILL.md. Focus on environmental assumptions, partial-state recovery, and ambiguous decision points. Do not fix — only ask.

Answer each question. If the answer requires new content, add it to `references/` or to the Error Handling section. If existing content already covers the answer, clarify SKILL.md so the agent does not have to ask.

## Iteration Signals

| Symptom | Cause | Canonical fix |
|---|---|---|
| Skill never auto-loads on real requests | Description too generic or missing user phrasings | Add concrete paraphrased trigger phrases users actually say |
| Skill loads on unrelated requests | Missing or weak negative triggers | Add explicit `Don't use for ...` listing the false-positive domains |
| Inconsistent output across runs | Procedures use prose instead of numbered steps | Convert to numbered steps + explicit decision trees |
| Agent hallucinates schemas / configs / commands | Bulk content stuffed into SKILL.md or omitted | Move to `references/`, reference explicitly via JiT read |
| Repeated parsing or validation errors | Logic prose-described, not scripted | Extract to a `scripts/` CLI with stdout/stderr discipline |
| Skill conflicts with another skill on same triggers | Composability failure — overlapping scope | Tighten negative triggers; document differentiation in description |

## Performance Comparison (Optional)

For high-frequency skills, run the same task with and without the skill enabled. Track:
- Tokens consumed end-to-end.
- API call failures.
- Number of agent turns to completion.
- User corrections required.

### Aspirational Baselines

These are upper-bound targets quoted by Anthropic's own guidance. Treat them as quality bars to aim for, not as pass/fail gates.

| Metric | Aspirational target |
|---|---|
| Trigger rate on relevant queries | ≥ 90% |
| API call failures per workflow | 0 |
| User correction prompts | 0 (agent self-corrects via stderr + Error Handling) |
| Skill adds turns vs baseline | 0 net additional turns |

A skill that adds turns or corrections compared to baseline is harming the agent — revisit scope and procedures before chasing the trigger-rate target.
