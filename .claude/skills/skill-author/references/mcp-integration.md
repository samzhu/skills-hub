# MCP Integration

Read this when the skill's category is `mcp-enhancement` or when the workflow depends on an MCP server.

## MCP vs Skill — Role Boundary

| Aspect | MCP | Skill |
|---|---|---|
| Purpose | Connects the agent to a service | Teaches the agent effective usage |
| Function | Data access + tool invocation | Captures workflows + best practices |
| Scope | What the agent can do | How the agent should do it |

A skill does not implement an MCP server — it wraps domain expertise around one. If the request is "build a Slack MCP server", that is out of scope for `skill-author`.

## Declaring the Dependency

```yaml
metadata:
  category: mcp-enhancement
  mcp-server: <server-name>
compatibility: >
  Requires the <server-name> MCP server, authenticated via <method>.
  Network access required.
```

State the MCP dependency in the `description` so the agent does not auto-load the skill on hosts where the MCP is unavailable.

## Required MCP Failure Modes

Every `mcp-enhancement` skill MUST cover these in its `## Error Handling`:

### Connection failure
**Cause**: MCP server not registered, server crashed, or network blocked.
**Recovery**: Instruct the agent to verify the MCP server is enabled in host settings. Do not retry silently. If persistent, escalate to user.

### Authentication failure (401 / 403)
**Cause**: API key missing, expired, or insufficient scope.
**Recovery**: Surface the auth error verbatim. Tell the user which credential needs refresh. Do not retry.

### Tool name not found
**Cause**: Tool name case-sensitivity mismatch (`SendMessage` vs `sendMessage`) or MCP server version drift.
**Recovery**: Fail loudly. Do not guess alternate tool names — tool discovery belongs in the skill's procedures, not at error time.

### Rate limit / quota
**Cause**: Burst exceeded the provider's rate limit.
**Recovery**: Honor `Retry-After` headers when present. When absent, surface the error to the user rather than backing off blindly.

### Schema drift
**Cause**: MCP server upgraded; tool input or output schema changed.
**Recovery**: Validate response shape via a script in `scripts/`. On mismatch, report which field changed and stop.

## Multi-MCP Coordination

For the `multi-mcp` pattern, additionally:

- Map each phase to exactly one MCP server. State the mapping at the top of `## Procedures`.
- Document the data hand-off between phases (which fields cross MCP boundaries).
- Place validation between phases — do not let malformed data from MCP-1 reach MCP-2 silently.
- Centralize error recovery: one `## Error Handling` section addresses failures from every MCP in scope.

## Anti-patterns

| Anti-pattern | Why it fails |
|---|---|
| Silent retry on auth failure | Hides credential issues; user never sees the actionable error |
| Guessing tool names on `tool not found` | Masks version drift; produces wrong-tool side effects |
| Looping on rate-limit without honoring headers | Amplifies quota burn |
| Skipping schema validation between MCPs | Bad data propagates; root cause traces to the wrong phase |
