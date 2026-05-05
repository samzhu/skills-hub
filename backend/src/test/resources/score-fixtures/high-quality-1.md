---
name: docker-compose-orchestrator
description: Orchestrates multi-service Docker Compose environments for local development. Use when starting, stopping, or debugging docker-compose.yml services. Handles port conflicts, volume mounts, and health checks. Not for Kubernetes or single-container Docker commands.
allowed-tools:
  - Bash(docker:*)
  - Read
  - Edit
---
# Docker Compose Orchestrator

Manages multi-service Docker Compose environments. Handles the common pain points: stale containers, port conflicts, and misconfigured volumes.

## When to use

Use this skill when the user asks to:
- Start or stop docker compose services (`docker compose up/down`)
- Debug why a service is not healthy or not starting
- Fix port conflicts between services
- Inspect logs from a specific container

Do NOT use for Kubernetes (`kubectl`) commands or single-container `docker run` invocations.

## Steps

1. **Check current state** — run `docker compose ps` to see which services are up/down/unhealthy
2. **Identify the failing service** — look for services in `Exit` or `unhealthy` state
3. **Read logs** — `docker compose logs --tail=50 <service>` to find the root cause
4. **Fix the issue** — common causes:
   - Port conflict: change `ports:` in `docker-compose.yml` (e.g., `"5433:5432"`)
   - Volume issue: run `docker compose down -v` to clear stale volumes, then re-up
   - Env var missing: add to `environment:` section in `docker-compose.yml`
5. **Restart** — `docker compose up -d` to start in detached mode
6. **Verify** — `docker compose ps` to confirm all services are `running (healthy)`

## Example

```bash
# User: "My postgres container keeps restarting"
docker compose ps                          # see state
docker compose logs --tail=50 postgres     # find error: "port 5432 already in use"
# Fix: edit docker-compose.yml ports: "5433:5432"
docker compose up -d postgres              # restart only postgres
docker compose ps postgres                 # verify: running (healthy)
```

## Output format

After any compose operation, always report:
- Which services changed state
- Current health of all services (`docker compose ps` output summary)
- Any warnings from the logs
