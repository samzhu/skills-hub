# Code Readability Checklist

Best practices for documentation, logging, and inline comments.
Applied during the **Code Readability Gate** phase of implementing-task.

Sources: Google Java Style Guide §7, Oracle Javadoc Guide, PEP 257,
OWASP Logging Cheat Sheet, SLF4J FAQ, 12-Factor App §11,
Clean Code (Robert Martin) Ch. 4.

---

## 1. Class-Level Documentation (Javadoc / Docstring)

### DO

- Write for every public type: state the **purpose** (one sentence)
  and the **invariant** it enforces.
- Include `@param` for record/DTO fields — say where the value
  originates (which event, which API, which config).
- Use `@see` / `{@link}` to cross-reference related types
  (e.g., the event a projection consumes, the service a controller
  delegates to).
- In Python, use imperative mood first line:
  `"""Parse a manifest and return its metadata."""`

### DON'T

- Restate the signature: `/** Returns the name. */ String getName()`
  adds zero value (Google §7.3.1).
- Add docs to trivial getters, setters, single-expression lambdas.
- Leave `@param` stubs empty — `@param id` with no description is
  worse than no `@param` at all.
- Document test classes or package-info files.

---

## 2. Logging

### Level Guidelines

| Level | When | Example |
|-------|------|---------|
| INFO | Business milestone completed | Skill published, version uploaded, download recorded |
| WARN | Recoverable issue, validation failure | SKILL.md missing, version duplicate rejected |
| ERROR | Unrecoverable, operator action needed | External service down, data integrity violation |
| DEBUG | Developer detail, query results | Search returned 42 results, aggregation summary |

### Structured Key-Value Context

Use the logging framework's structured API — never string concatenation.

```
// Java (SLF4J fluent API)
log.atInfo()
    .addKeyValue("skillId", id)
    .addKeyValue("version", ver)
    .log("Version published");

// Python (structlog or extra dict)
logger.info("version_published", skill_id=id, version=ver)

// Go (slog)
slog.Info("version published", "skillId", id, "version", ver)
```

### Logger Declaration

```
// Java — copy-paste safe across classes
private static final Logger log =
    LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
```

### Which Classes Need a Logger

| Class type | Logger required? |
|-----------|-----------------|
| @Service, @Component (listeners, projections) | YES |
| @RestControllerAdvice (exception handlers) | YES |
| @RestController | Only if doing non-trivial logic beyond delegation |
| Record / DTO / enum | NO |
| Repository interface | NO |
| Pure utility (stateless, no I/O) | Only if it handles errors |

### NEVER Log

- Passwords, tokens, API keys, session IDs
- PII (email, phone, government IDs)
- Full request/response bodies that may contain the above
- Credit card or banking data
- Database connection strings with credentials

### Anti-Patterns

| Anti-pattern | Fix |
|---|---|
| `log.debug("val=" + obj)` — evaluates even when disabled | Use `{}` placeholders or fluent API |
| `catch (e) { log.error(...); throw e; }` — same error logged twice | Log once at the boundary where you handle it |
| `catch (e) { }` — swallowed silently | At minimum `log.warn(...)` |
| Logging per-row inside DB iteration | Accumulate, log summary outside loop |
| All loggers at INFO in production | Set root to WARN, whitelist app packages to INFO |

---

## 3. Inline Comments

### WHEN to Comment (the "Why" rule)

| Situation | Comment template |
|-----------|-----------------|
| Regex pattern | `// Matches X because Y (see spec AC-N)` |
| Business rule | `// Rule: versions are immutable — no overwrite allowed` |
| Aggregation pipeline | `// group by category → count → sort desc by count` |
| Framework workaround | `// Workaround: XYZ doesn't support ABC (see issue #123)` |
| Null/empty guard | `// Can be null when risk assessment hasn't run yet` |
| Intentional deviation | `// PERMIT ALL — MVP phase; security added in S-NNN` |
| Non-obvious algorithm | `// Using regex instead of text index — Firestore compat limitation` |

### WHEN NOT to Comment

| Situation | Why |
|-----------|-----|
| `i++; // increment i` | Restates what code obviously does |
| `// End of method` | IDE handles this |
| Commented-out dead code | Delete it; version control preserves history |
| Banner separators `// ======` | Restructure the code instead |
| Stale comment contradicting code | A wrong comment is worse than none |

---

## Exit Criteria (verification commands)

After completing the gate, verify with these checks (adapt commands
to the project's ecosystem):

```bash
# 1. Every production class has Javadoc (except package-info)
grep -rL '^\s*/\*\*' src/main/java/**/*.java | grep -v package-info

# 2. Every Service/Listener has a Logger
grep -rL 'LoggerFactory' src/main/java/**/*Service.java \
                          src/main/java/**/*Listener.java \
                          src/main/java/**/*Projection.java

# 3. Every regex has a comment
grep -n 'Pattern.compile' src/main/java/**/*.java | while read line; do
  # Check the line above for a comment
done
```

If any check returns results, the gate has not passed — fix before
updating the task file.
