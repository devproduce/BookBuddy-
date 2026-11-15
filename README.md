# Book Buddy

AI-powered personal reading and reflection companion.

## Stack

- **Backend:** Java 21, Spring Boot 3.4, MongoDB Atlas + Vector Search, Groq, ONNX embeddings
- **Web:** Vite + React + TypeScript
- **Mobile:** Flutter Android (later)

## Quick start (local)

### Backend

```bash
cd backend
cp .env.example .env   # set MONGODB_URI, JWT_SECRET, GROQ_API_KEY
./gradlew bootRun
```

Health: `GET http://localhost:8080/api/v1/health`

### Web

```bash
cd web
cp .env.example .env
npm install
npm run dev
```

Open http://localhost:5173

## Docs

- Product requirements: `Book Buddy – Product Requirements Document.md`
- Phase 0 spikes: `docs/spikes/`
- Evaluation sets, emotional-quality rubric and how to run them: `docs/eval/README.md`
- Deploy (EC2, systemd, HTTPS proxy, backups): `docs/deploy/README.md`
- Observability: `docs/observability.md` · NFR targets and how to measure them: `docs/nfr.md`
- Local API + Mongo in containers (optional): `docker compose up --build` (see `docker-compose.yml`)
