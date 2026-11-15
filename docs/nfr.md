# Non-functional requirements and how to measure them

Source: PRD §15 (targets are initial; tune after measurement). This page adds **how to measure each one**, the
**current status**, and where the numbers get recorded. "Status" is honest about what is measured vs. not yet.

Record every measurement (date, commit, host, result) in `docs/spikes/results/` (git-ignored for raw data) and
summarise it in the table at the bottom.

| Area | Target | How to measure | Where to look | Status |
| --- | --- | --- | --- | --- |
| **Chat latency** | Time to first streamed token ≤ 2.5 s p50, ≤ 5 s p95 (home network) | Client-side: time from sending `POST …/messages/stream` to the first `token` SSE event. `curl -N -w '%{time_starttransfer}\n'` against the stream endpoint is a quick proxy (includes the `meta` event, so it slightly underestimates). Run ≥ 30 messages, English and Hindi, with and without book retrieval; report p50/p95 | Web devtools Network → EventStream; proxy access log; `llm_usage.latencyMs` (whole stream, **not** TTFT) | Not measured |
| **Voice latency** | End of speech → first spoken word ≤ 3 s p50 | Stopwatch/log timestamps in the Flutter app: STT final result → first TTS audio. ≥ 20 utterances per language | Mobile app logs | N/A until mobile exists |
| **Retrieval** | Query embedding + search + fusion ≤ 400 ms for a 100k-chunk corpus | Time `RagService.retrieve` (embed + `HybridSearchService`) on a seeded corpus of 100k chunks; run once with Atlas `$vectorSearch` and once with `VECTOR_MODE=memory`. Brute-force over 100k × 384-d vectors in-process is the risk; the Atlas path is the one expected to meet the target | Add a temporary timer log, or a JUnit/JMH harness over `BruteForceVectorSearchRepository`; Atlas Performance Advisor | Not measured. Expect memory mode to miss at 100k chunks |
| **Ingestion** | 300-page text PDF done < 5 min on the host; progress visible throughout | Upload a 300-page text PDF; time from `POST` to book status `COMPLETED`; poll `GET /books/{id}/status` for progress changes. Repeat on the real EC2 size (2 GB) as well as the dev machine | `ingestion_jobs` collection (stage, timestamps); journal | Not measured on EC2 |
| **Embedding throughput** | ≥ 20 chunks/s on the dev machine (configurable batch) | Embed N=2,000 chunks, divide by wall time, after JIT warm-up | A small benchmark around `EmbeddingProvider.embedPassage`. Meaningful only once ONNX inference exists | Blocked: ONNX inference not implemented (hash fallback is not representative) |
| **Memory footprint** | Runs within host RAM with the model loaded; document the minimum | `ps -o rss= -p $(systemctl show -p MainPID --value bookbuddy)` and `systemd-cgtop` at idle, during ingestion of a 300-page PDF, and during 3 concurrent chats; keep the peak. Check `journalctl -k` for OOM kills | systemd cgroup, `jcmd <pid> VM.native_memory` | Working assumption: **≥ 2 GB instance**. Replace with the measured floor in `docs/spikes/README.md` |
| **Availability** | Best effort; degrade gracefully when LLM or network is down | Fault drills: unset `GROQ_API_KEY` (chat falls back to extractive/offline answers), block egress to Groq, stop Mongo access (health → `DEGRADED`). Confirm no 5xx storms and the UI shows a friendly message. External uptime monitor gives the monthly number | `/api/v1/health`, uptime monitor | Fallbacks are implemented and unit-tested; drills not yet run on a deployed box |
| **Reliability** | No message loss; failed jobs resumable; jobs idempotent; LLM failures never alter stored memories | Kill the service mid-stream and mid-ingestion, restart; check the user message is stored, the assistant message is `INTERRUPTED` (retryable), and the job resumes or fails cleanly. Replay a memory-extraction job twice and confirm no duplicates. Inject Groq 429/timeout (WireMock) and confirm memories unchanged | Integration tests (Testcontainers + WireMock), `jobs_queue` | Partially covered by unit tests; crash drills not run |
| **Data safety** | Backups ≥ daily; one tested restore | Follow the drill in [`deploy/backup.md`](deploy/backup.md); record date and recovery time. Alert if the newest backup is > 26 h old | S3 listing; backup log; dead-man's switch | Scripts provided; **restore drill not yet performed** |
| **Maintainability** | Versioned prompts, embeddings and chunking; structured JSON logs with correlation IDs; config via environment | Prompt id is stored on each assistant message (`promptVersion`); embedding model name is exposed in `/health`; every setting is an env var (`backend/.env.example`). Log lines carry `[cid=…]`; JSON format is not enabled yet (one-line Spring Boot 3.4 switch, see `observability.md`). CI runs tests and a secret scan | Code review; `docs/observability.md` | Mostly met; JSON logs outstanding |
| **Accessibility** | System font scaling, screen-reader labels, contrast, dark mode | Web: Lighthouse accessibility ≥ 90, keyboard-only walkthrough, axe DevTools on Login/Library/Chat/Settings, 200% zoom, check contrast ratios (≥ 4.5:1 text). Mobile: TalkBack pass and system font scale 1.3× | Lighthouse, axe | Not measured |
| **Localization** | Externalised UI strings (English first, Hindi later); locale-aware dates | Grep for hard-coded user-facing strings; render dates with `Intl.DateTimeFormat` and confirm `hi-IN` output once a catalogue exists | Code review | Not started (English only) |

## Measurement conditions (write these down with every number)

- Commit SHA, backend `JAVA_OPTS`, instance type, region, Atlas tier and region (cross-region adds 100+ ms per query).
- Network path: home network → API over HTTPS via the reverse proxy (the number users feel), and on-box `localhost`
  (the number you can optimise).
- Corpus size and language mix; embedding model in use (`/health` → `embeddingModel`, `embeddingLoaded`).
- Groq model id and time of day (free-tier latency varies; avoid concluding from a single run).

## Quick measurement recipes

**Time to first token (curl):**

```bash
for i in $(seq 1 30); do
  curl -sN -o /dev/null -w '%{time_starttransfer}\n' \
    -H "Authorization: Bearer $TOKEN" -H "Accept: text/event-stream" -H "Content-Type: application/json" \
    -d '{"content":"What is the main idea of chapter 1?"}' \
    "https://api.example.com/api/v1/conversations/$CID/messages/stream"
done | sort -n | awk '{a[NR]=$1} END {print "p50="a[int(NR*0.5)]" p95="a[int(NR*0.95)]}'
```

(Create a fresh conversation per run if you want to avoid conversation-length effects, and note the first call
warms caches.)

**Peak RSS during ingestion:**

```bash
PID=$(systemctl show -p MainPID --value bookbuddy)
while sleep 2; do ps -o rss= -p "$PID"; done | sort -n | tail -1   # KB; stop with Ctrl-C after ingestion ends
```

**LLM quota and error rate:** queries in [`observability.md`](observability.md).

## Results log

| Date | Commit | Host | Metric | Result | Target met? |
| --- | --- | --- | --- | --- | --- |
| | | | | | |
