# Phase 0 – Spikes

Run these before relying on production paths. Record results under `results/`.

## 1. ONNX multilingual-e5-small

- Place model files under `backend/models/` (gitignored): `model.onnx` (+ tokenizer files).
- Run: `./gradlew :bootRun` with `bookbuddy.embedding.enabled=true`, or unit spike `EmbeddingSpikeTest`.
- Compare cosine similarity of Java vs Python reference vectors for the same `query:` / `passage:` strings; expect mean absolute error under ~1e-3 after L2 normalize.
- Document host RAM used with model loaded (target ≥2 GB free on EC2).

## 2. Groq free-tier + streaming

- Set `GROQ_API_KEY` and allow-listed model id.
- Run spike script / `GroqSpike` against English and Hindi prompts.
- Record: RPM/RPD/TPM if returned, TTFT p50, stream stability, Hindi quality notes.

## 3. Atlas Vector Search

- Create index on `book_chunks.embedding` (384 dims, cosine) with filters `userId`, `bookId`.
- Insert sample vectors; run `$vectorSearch`; confirm latency for ~10k docs.

## 4. PDF upload smoke

- Multipart upload → PDFBox extract → delete temp file in `finally`.
- Confirm no orphan files under `uploads/` after success or failure.

## Go / no-go checklist

| Spike | Go? | Notes | Date |
| --- | --- | --- | --- |
| E5 ONNX | | | |
| Groq | | | |
| Atlas vector | | | |
| PDF transient | | | |
| EC2 RAM floor | | GB | |
