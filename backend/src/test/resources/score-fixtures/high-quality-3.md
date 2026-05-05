---
name: sql-query-optimizer
description: Analyzes and optimizes slow PostgreSQL queries. Use when a query takes >1s or EXPLAIN shows Seq Scan on large tables. Adds indexes, rewrites subqueries as CTEs, and rewrites correlated subqueries. Not for schema design or data migration.
allowed-tools:
  - Bash(psql:*)
  - Read
  - Edit
---
# SQL Query Optimizer

Diagnoses and fixes slow PostgreSQL queries using EXPLAIN ANALYZE, index analysis, and query rewriting.

## When to use

Use when:
- A query is taking >1 second
- `EXPLAIN` output shows `Seq Scan` on a large table (>10k rows)
- The user says "my query is slow" and provides SQL

Do NOT use for: schema design, data migrations, or optimizing queries on tables <1000 rows.

## Steps

1. **Get the execution plan**:
   ```sql
   EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) <your query>;
   ```

2. **Identify bottlenecks** — look for:
   - `Seq Scan` on large tables → missing index
   - `Hash Join` with high rows → consider partial index or materialized CTE
   - `Nested Loop` with high iterations → N+1 query pattern

3. **Apply fix** based on bottleneck type:

   **Missing index**:
   ```sql
   CREATE INDEX CONCURRENTLY idx_orders_user_id ON orders(user_id);
   ```

   **Correlated subquery → join**:
   ```sql
   -- Before (correlated):
   SELECT * FROM orders WHERE user_id IN (SELECT id FROM users WHERE active = true);
   -- After (join):
   SELECT o.* FROM orders o JOIN users u ON o.user_id = u.id WHERE u.active = true;
   ```

   **N+1 → single query with lateral join**:
   ```sql
   SELECT u.*, latest.created_at
   FROM users u
   LEFT JOIN LATERAL (
     SELECT created_at FROM orders WHERE user_id = u.id ORDER BY created_at DESC LIMIT 1
   ) latest ON true;
   ```

4. **Verify improvement** — re-run EXPLAIN ANALYZE and compare actual rows and execution time.

## Output format

Report:
- Original execution time (from EXPLAIN ANALYZE)
- Bottleneck identified
- Fix applied (SQL statement)
- New execution time after fix
- Improvement factor (e.g., "10x faster: 2000ms → 200ms")
