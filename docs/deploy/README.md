# Deploying Book Buddy on AWS EC2

A single small EC2 instance runs the Spring Boot API behind a TLS reverse proxy. MongoDB is **Atlas** (never on the
box), the LLM is **Groq** (remote), and the web app is a static build hosted anywhere.

```
Browser / phone ──HTTPS──> Caddy or nginx (:443) ──http──> Spring Boot (127.0.0.1:8080) ──TLS──> MongoDB Atlas
                                                                  └──TLS──> Groq API
```

Files in this folder:

| File | Purpose |
| --- | --- |
| `bookbuddy.service` | systemd unit for the jar |
| `Caddyfile` | recommended reverse proxy (automatic HTTPS) |
| `nginx.conf.example` | alternative reverse proxy |
| `backup.md` | backups and the restore drill |
| `../observability.md`, `../nfr.md` | what to watch and the targets |

> Nothing here contains secrets. Real values live in `/etc/bookbuddy/bookbuddy.env` on the server only.

## 1. Sizing and the RAM note

| Instance | RAM | Verdict |
| --- | --- | --- |
| `t3.micro` / `t4g.micro` | 1 GB | **Not supported.** JVM + Spring + PDFBox ingestion will be OOM-killed, and the ONNX model will not fit. |
| `t3.small` / `t4g.small` | **2 GB** | **Minimum.** Works for one or a few users. Add a 2 GB swap file as a safety net. |
| `t3.medium` / `t4g.medium` | 4 GB | Comfortable, especially with the embedding model loaded and large PDFs. |

Why at least 2 GB: the JVM heap (about 600–900 MB under load), Metaspace and thread stacks, PDFBox page rendering
during ingestion, and — when enabled — the ONNX embedding model (multilingual-e5-small is roughly 120–470 MB on disk
depending on quantisation, and its runtime memory is several times that). The `Phase 0` spike records the real floor
for your model in `docs/spikes/README.md` ("EC2 RAM floor"); update it after you measure.

> **Status of embeddings:** `OnnxEmbeddingProvider` validates the model and tokenizer files at startup, but E5
> tokenisation/inference is not implemented yet, so the app currently serves the deterministic hash fallback and
> `GET /api/v1/health` reports `embeddingLoaded: false` even with the model present. The paths below are still the
> ones to use so the deployment does not change when inference lands.

Both x86_64 (`t3`) and ARM (`t4g`, usually cheaper) work: ONNX Runtime and Temurin ship `aarch64` Linux builds.

Optional swap (2 GB instance):

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

## 2. AWS setup

1. **Launch** Ubuntu 24.04 LTS (or Amazon Linux 2023), 2 GB+ RAM, 20 GB gp3 root volume (encrypted).
2. **Elastic IP:** allocate and associate one, so DNS and the Atlas allow-list stay valid across reboots.
3. **DNS:** create an `A` (and optionally `AAAA`) record, e.g. `api.example.com`, pointing at the Elastic IP.
4. **Security group** (inbound):

   | Port | Source | Why |
   | --- | --- | --- |
   | 22 (SSH) | **your IP only** (or use SSM Session Manager and close 22) | admin |
   | 80 | `0.0.0.0/0`, `::/0` | ACME challenge and HTTP→HTTPS redirect |
   | 443 | `0.0.0.0/0`, `::/0` | the API |
   | 8080 | **not open** | the app listens on `127.0.0.1` only |

   Outbound: allow all (Atlas on 27017, Groq on 443, apt, S3).
5. **IAM instance role** (optional, for backups): `s3:PutObject` / `s3:GetObject` on `arn:aws:s3:::YOUR-BUCKET/mongo/*`
   only. Prefer a role over access keys on disk.

## 3. MongoDB Atlas

1. Create a cluster (M0 free tier is fine for personal use) and a database named `bookbuddy`.
2. **Database user:** least privilege — `readWrite` on `bookbuddy` only. Use a long random password and URL-encode
   special characters in the URI.
3. **Network access:** add the **EC2 Elastic IP** (`x.x.x.x/32`). Do not leave `0.0.0.0/0` in place.
4. Copy the SRV connection string into `MONGODB_URI` (see the env file below).
5. **Vector Search index:** create the index described in `docs/spikes/atlas-vector-index.json` on
   `bookbuddy.book_chunks` (384 dimensions, cosine; filter fields `userId`, `bookId`) and keep
   `VECTOR_MODE=atlas`. If the index is missing or Search is unavailable, the app falls back to in-process brute-force
   search (fine for small libraries, slower for large ones); `VECTOR_MODE=memory` forces that path.
6. Backups: see [`backup.md`](backup.md). The M0 tier has **no** Atlas-managed backups, so run the `mongodump`
   script.

## 4. Install Java 21 and create the service user

```bash
# Ubuntu / Debian
sudo apt-get update && sudo apt-get install -y openjdk-21-jre-headless curl
# Amazon Linux 2023:  sudo dnf install -y java-21-amazon-corretto-headless
java -version        # must report 21.x

sudo useradd --system --home-dir /opt/bookbuddy --shell /usr/sbin/nologin bookbuddy
sudo install -d -o bookbuddy -g bookbuddy -m 755 /opt/bookbuddy /opt/bookbuddy/models
sudo install -d -o bookbuddy -g bookbuddy -m 750 /var/lib/bookbuddy /var/lib/bookbuddy/uploads
sudo install -d -o root -g bookbuddy -m 750 /etc/bookbuddy
```

## 5. Build and ship the jar

Do **not** build on a 2 GB instance (Gradle needs more RAM than the app). Build on your machine or in CI:

```bash
cd backend
./gradlew bootJar            # Windows: gradlew.bat bootJar
ls build/libs/               # bookbuddy-0.1.0.jar  (ignore *-plain.jar)
scp build/libs/bookbuddy-0.1.0.jar ubuntu@api.example.com:/tmp/app.jar
```

On the server:

```bash
sudo install -o bookbuddy -g bookbuddy -m 644 /tmp/app.jar /opt/bookbuddy/app.jar && rm /tmp/app.jar
```

Alternative: build the container image from `backend/Dockerfile` and run it with Docker on the instance, using the
same env file (`docker run --env-file /etc/bookbuddy/bookbuddy.env ...`). The systemd route below is the primary path.

## 6. Environment file (secrets)

Create `/etc/bookbuddy/bookbuddy.env` (start from `backend/.env.example`; never commit the real file):

```bash
sudo install -o root -g bookbuddy -m 640 /dev/null /etc/bookbuddy/bookbuddy.env
sudoedit /etc/bookbuddy/bookbuddy.env
```

```ini
MONGODB_URI=mongodb+srv://bookbuddy_app:URL_ENCODED_PASSWORD@cluster0.xxxxx.mongodb.net/bookbuddy?retryWrites=true&w=majority
JWT_SECRET=<output of: openssl rand -base64 48>
GROQ_API_KEY=<your groq key>
GROQ_MODEL=openai/gpt-oss-20b
CORS_ORIGINS=https://app.example.com
VECTOR_MODE=atlas
EMBEDDING_ENABLED=false
LLM_DAILY_SOFT_LIMIT=900
```

Rules for this file: `KEY=value` lines only, no quotes, no inline `# comments` (systemd does not parse them), no
trailing spaces. `CORS_ORIGINS` is a comma-separated list of exact browser origins (scheme + host, no trailing slash).
Rotate `JWT_SECRET` to log everyone out; rotate `GROQ_API_KEY` in the Groq console if it ever leaks.

The unit file already sets `UPLOAD_DIR`, `EMBEDDING_MODEL_PATH`, `EMBEDDING_TOKENIZER_PATH`, `SERVER_ADDRESS`
(loopback) and `FORWARD_HEADERS_STRATEGY` as defaults. Anything you also put in the env file **overrides** those
`Environment=` lines (systemd applies `EnvironmentFile` after `Environment`), so you can move a path by setting it in
`bookbuddy.env` without editing the unit.

### Uploads directory

`/var/lib/bookbuddy/uploads` (`UPLOAD_DIR`) holds PDFs only while they are being ingested; the backend deletes them
afterwards. It must be writable by the `bookbuddy` user and on a disk with room for `MAX_UPLOAD_MB` (default 50) per
concurrent upload. It does not need backing up. A non-empty directory long after an upload finished is a bug worth
reporting. The unit's `ReadWritePaths=/var/lib/bookbuddy` is what makes it writable under `ProtectSystem=strict`.

### Embedding model path

Model files are **not in git** (`backend/models/*.onnx` is ignored). To use them:

```bash
scp model.onnx tokenizer.json ubuntu@api.example.com:/tmp/
sudo install -o bookbuddy -g bookbuddy -m 644 /tmp/model.onnx /tmp/tokenizer.json /opt/bookbuddy/models/
```

then set `EMBEDDING_ENABLED=true` in the env file. Defaults used by the unit: `EMBEDDING_MODEL_PATH=/opt/bookbuddy/models/model.onnx`
and `EMBEDDING_TOKENIZER_PATH=/opt/bookbuddy/models/tokenizer.json`. Use **absolute** paths in production (the
defaults in `application.yml` are relative to the working directory). If the files are missing the app logs a warning
and uses the hash fallback instead of failing to start. Keep the same model for ingestion and queries: switching
models means re-embedding every book.

## 7. systemd service

```bash
sudo cp docs/deploy/bookbuddy.service /etc/systemd/system/bookbuddy.service
sudo systemctl daemon-reload
sudo systemctl enable --now bookbuddy
systemctl status bookbuddy
journalctl -u bookbuddy -f                 # logs; each line carries [cid=<correlation id>]
curl -s http://127.0.0.1:8080/api/v1/health
```

The unit runs as `bookbuddy`, restarts on failure, binds to loopback, caps memory (`MemoryMax=1700M`, lower it on very
small hosts and raise it on bigger ones together with `-XX:MaxRAMPercentage`), and applies standard systemd sandboxing.

Deploying a new version:

```bash
sudo install -o bookbuddy -g bookbuddy -m 644 /tmp/app.jar /opt/bookbuddy/app.jar
sudo systemctl restart bookbuddy && sleep 20 && curl -fsS http://127.0.0.1:8080/api/v1/health
```

Keep the previous jar as `app.jar.prev` (`sudo cp -p /opt/bookbuddy/app.jar /opt/bookbuddy/app.jar.prev` first) so a
rollback is a `cp` and a restart.

## 8. Reverse proxy with HTTPS

Pick one. Both terminate TLS, forward `X-Forwarded-*`, and **disable buffering for the chat SSE endpoints**:

- `POST /api/v1/conversations/{id}/messages/stream`
- `POST /api/v1/conversations/{id}/messages/{mid}/regenerate` (when called with `Accept: text/event-stream`)

If a proxy buffers these, the user sees the whole answer arrive at once instead of streaming, and long replies can
look stuck. The backend sends a `: keepalive` comment every 15 s and times a stream out after 5 minutes, so the proxy
read timeout must be above that (6 minutes in the samples).

**Caddy (recommended — certificates are automatic):**

```bash
sudo apt-get install -y caddy               # see caddyserver.com/docs/install for the official repo
sudo cp docs/deploy/Caddyfile /etc/caddy/Caddyfile
sudoedit /etc/caddy/Caddyfile               # replace api.example.com
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

**nginx + certbot:**

```bash
sudo apt-get install -y nginx certbot python3-certbot-nginx
sudo cp docs/deploy/nginx.conf.example /etc/nginx/conf.d/bookbuddy.conf
sudoedit /etc/nginx/conf.d/bookbuddy.conf   # replace api.example.com
sudo certbot --nginx -d api.example.com
sudo nginx -t && sudo systemctl reload nginx
```

Set `FORWARD_HEADERS_STRATEGY=framework` (already in the unit) so the app sees the original scheme and host.

Verify SSE end to end (replace the token and ids):

```bash
curl -N -H "Authorization: Bearer $TOKEN" -H "Accept: text/event-stream" -H "Content-Type: application/json" \
  -d '{"content":"hello"}' https://api.example.com/api/v1/conversations/$CID/messages/stream
```

Events should appear incrementally (`meta`, then `token` events), not all at once at the end.

## 9. Web app

```bash
cd web
echo 'VITE_API_BASE_URL=https://api.example.com/api/v1' > .env.production   # git-ignored
npm ci && npm run build                                                     # output: web/dist
```

Host `web/dist` on any static host (S3 + CloudFront, Netlify, Cloudflare Pages) or from the same Caddy (see the
commented block in the `Caddyfile`). It is a single-page app: unknown paths must fall back to `index.html`. Put the
final web origin in `CORS_ORIGINS`.

## 10. Go-live checklist

- [ ] `java -version` is 21 and the instance has ≥ 2 GB RAM (+ swap)
- [ ] Security group exposes only 80/443 (and 22 from your IP); 8080 is closed to the world
- [ ] Atlas allow-list contains only the Elastic IP; DB user is `readWrite` on `bookbuddy` only
- [ ] `/etc/bookbuddy/bookbuddy.env` is mode 640, owned by `root:bookbuddy`, and `JWT_SECRET` is random and ≥ 32 chars
- [ ] `https://api.example.com/api/v1/health` returns `status: UP`, `mongo: true`, `llmConfigured: true`
- [ ] Register, upload a small PDF, ask a question and see tokens stream in the browser
- [ ] `CORS_ORIGINS` matches the web origin exactly
- [ ] A daily backup is scheduled and **one restore has been tested** ([`backup.md`](backup.md))
- [ ] An uptime check (UptimeRobot, Healthchecks, CloudWatch) pings `/api/v1/health`
- [ ] `sudo reboot` — everything comes back on its own (service enabled, Elastic IP attached)

## 11. Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Service restarts in a loop, `OutOfMemoryError` in the journal | instance too small, or `MaxRAMPercentage` too high with the model loaded |
| `health` shows `"mongo": false` | Atlas allow-list lacks the Elastic IP, wrong password/URL encoding, or DNS/egress blocked |
| Chat answers arrive all at once | proxy buffering SSE — re-check the `flush_interval -1` / `proxy_buffering off` blocks |
| Browser CORS errors | `CORS_ORIGINS` missing the exact web origin (scheme, host, port) |
| `413` on PDF upload | proxy body limit (`60MB` in samples) below the file size, or above `MAX_UPLOAD_MB` |
| Answers fall back to "Here is what your books say…" | `GROQ_API_KEY` missing/invalid, or daily soft limit reached (`llmQuota` in `health`) |
