# Observability

Small, single-host, privacy-first. Nothing here sends data to a third party by default.

## Health: `GET /api/v1/health` (public, no auth)

```json
{
  "status": "UP",
  "mongo": true,
  "embeddingLoaded": false,
  "llmConfigured": true,
  "embeddingModel": "hash-sha256-fallback",
  "vectorMode": "atlas",
  "llmQuota": "OK"
}
```

| Field | Meaning |
| --- | --- |
| `status` | `UP`, or `DEGRADED` when the MongoDB ping fails. The HTTP status stays 200, so alert on the body (`"status":"UP"`) rather than on the code alone |
| `mongo` | Atlas ping succeeded |
| `embeddingLoaded` | a real ONNX model is producing vectors (`false` = deterministic hash fallback; also `false` today even with the model file present, because E5 inference is not implemented yet) |
| `llmConfigured` | `GROQ_API_KEY` is set (says nothing about Groq being reachable) |
| `embeddingModel` | name of the model whose vectors are produced right now |
| `vectorMode` | configured search mode (`atlas` tries `$vectorSearch` with brute-force fallback; `memory` is brute-force only) |
| `llmQuota` | coarse state of the daily soft limit: `OK`, `LOW` (≥ 85% used, so summaries/extraction pause), `EXHAUSTED` (chat still tries; see below), `UNKNOWN` when Mongo is down |

The endpoint is deliberately coarse: it never exposes exact counters, paths, versions or user data. Exact numbers are
in the `llm_usage` collection. `/actuator/health` is also exposed (details only for authenticated callers).

**Alerting:** point an uptime monitor (UptimeRobot, Healthchecks.io, CloudWatch synthetic) at
`https://api.example.com/api/v1/health` with a keyword check for `"status":"UP"`; page on two consecutive failures.

## LLM usage: the `llm_usage` collection

Every LLM call is recorded by `LlmBudgetService.record(...)`: one document per call with `userId` (null for system
calls), `purpose`, `model`, `promptTokens`, `completionTokens`, `latencyMs`, `outcome` (`OK`/`ERROR`), `day` (UTC) and
`createdAt`. **Prompt and completion text are never stored.** Recording never throws, so a database hiccup cannot break
chat.

| `purpose` | Recorded in | Notes |
| --- | --- | --- |
| `CHAT` | `ConversationService` (streamed replies, including cancelled and failed streams) and `RagService.ask` (`POST /rag/ask`) | tokens from the provider, or estimated when the stream gives none; `/rag/ask` records count and latency only (`complete()` returns no usage) |
| `SAFETY` | `RiskClassifier` (LLM second opinion) | never refused by the budget |
| `SUMMARY` | `RollingSummaryService` | background; deferred above 85% of the limit |
| `EXTRACTION` | `MemoryExtractor` | background; deferred above 65% |
| `CONSOLIDATION` | *(none yet)* | consolidation currently makes no LLM call; it is only gated on the budget |

Budget rules (see `LlmBudgetService`): the daily soft limit (`LLM_DAILY_SOFT_LIMIT`, default 900 calls per UTC day)
is shared; lower-priority work backs off first and is **deferred, not dropped**.

Useful queries (`mongosh`):

```js
// calls today by purpose and outcome
db.llm_usage.aggregate([
  { $match: { day: new Date().toISOString().slice(0, 10) } },
  { $group: { _id: { purpose: "$purpose", outcome: "$outcome" }, calls: { $sum: 1 },
              promptTokens: { $sum: "$promptTokens" }, completionTokens: { $sum: "$completionTokens" } } }
])

// p50/p95 chat latency over the last 7 days
db.llm_usage.aggregate([
  { $match: { purpose: "CHAT", outcome: "OK", createdAt: { $gte: new Date(Date.now() - 7*864e5) } } },
  { $group: { _id: null, p: { $percentile: { input: "$latencyMs", p: [0.5, 0.95], method: "approximate" } } } }
])

// error rate per day
db.llm_usage.aggregate([
  { $group: { _id: "$day", total: { $sum: 1 }, errors: { $sum: { $cond: [{ $eq: ["$outcome","ERROR"] }, 1, 0] } } } },
  { $sort: { _id: -1 } }, { $limit: 14 }
])
```

(`$percentile` needs MongoDB 7.0+, which Atlas provides.) `latencyMs` for streamed chat is the whole stream
duration, not time-to-first-token; measure TTFT from the client or a proxy log (see `docs/nfr.md`).

`llm_usage` grows by one small document per call. It is per-user data, so `DELETE /account/data` removes the user's
rows. If it ever becomes large, add a TTL index on `createdAt` (e.g. 90 days).

## Logs

- Output goes to stdout → `journalctl -u bookbuddy` (systemd) or `docker logs` (Docker). Level `INFO`.
- Every request has a **correlation id**: `CorrelationIdFilter` accepts a client `X-Correlation-Id` (safe characters,
  ≤ 64) or generates a UUID, echoes it in the response header, and puts it in the log context. The log pattern prints
  it as `[cid=...]`, so one failing request can be traced with `journalctl -u bookbuddy | grep <cid>`. The nginx
  sample forwards the header; Caddy passes it through by default.
- The id is in the `X-Correlation-Id` response header of every response (including errors), so a user or the web
  client can quote it in a bug report. (It is not part of the JSON error body.)
- **Never logged:** message text, prompts, completions, passwords, tokens, PDF text. Safety events log only the level
  and a timestamp (`com.bookbuddy.safety.events`); watch for `safety_event level=ACUTE` if you want an audit trail.
- Format today is plain text with the correlation id (not JSON). The PRD asks for structured JSON logging; Spring Boot
  3.4 supports it with a one-line change (`logging.structured.format.console: ecs`), which is worth enabling once
  you ship logs to a collector. Until then `journalctl -o cat | grep` is enough for one host.

## What to watch

| Signal | Where | Act when |
| --- | --- | --- |
| Uptime and `status` | external monitor on `/api/v1/health` | 2 consecutive failures |
| `llmQuota` | `/health` or `llm_usage` | `LOW`/`EXHAUSTED` for several days: lower usage, switch model, or raise the limit after checking Groq's real limits |
| LLM error rate | `llm_usage.outcome` | > 5% in a day: check Groq status and key |
| Memory / OOM | `systemctl status bookbuddy`, `journalctl -k | grep -i oom` | any OOM kill: bigger instance or lower heap |
| Disk | `df -h /` and `/var/lib/bookbuddy/uploads` | > 80%, or files lingering in `uploads/` |
| TLS certificate expiry | automatic (Caddy / certbot timer) | renewal errors in `journalctl -u caddy` / `certbot.timer` |
| Backup freshness | `HEALTHCHECK_URL` dead-man's switch + newest object in S3 | no backup in 26 h |
| Job backlog | `db.jobs_queue.countDocuments({ status: { $in: ["PENDING", "RUNNING"] } })`; failures: `{ status: "FAILED" }` | `PENDING` steadily growing, or any `FAILED` you did not expect |

## Not implemented (intentionally small)

Metrics endpoint (Prometheus) and distributed tracing are not enabled; `management.endpoints.web.exposure` is limited
to `health,info`. Add `micrometer-registry-prometheus` and expose `prometheus` only on a private interface if you
need dashboards.
