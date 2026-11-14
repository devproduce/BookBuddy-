ca# Book Buddy – Product Requirements Document

**Version:** 1.1 (reviewed and refined from v1.0)\
**Type:** AI-powered personal reading and reflection companion\
**Platform:** Android (Flutter) · **Backend:** Java 21 + Spring Boot 3.x · **DB:** MongoDB · **LLM:** Groq (free tier) · **Embeddings:** local open-source model\
**Ownership:** Personal project, single-user first, multi-user-ready schema\
**Cost objective:** Zero recurring cost

Items tagged **\[NEW\]** were added in this review. Items tagged **\[CHANGED\]** fix a gap or contradiction in v1.0. Anything about free tiers, model IDs, or prices must be re-verified when implementation starts, because these change often.

---

## 0. Review Summary: What Changed and Why

| # | Issue found in v1.0 | Resolution in v1.1 |
| --- | --- | --- |
| 1 | **Contradiction:** PDFs stay on device, but PDFBox runs on the backend. | Section 5, decision D1: choose one ingestion path explicitly. |
| 2 | **Missing:** how the phone reaches a backend running on a laptop (only works on home Wi-Fi). | Decision D2: remote-access strategy (Tailscale or free tunnel). |
| 3 | Chunk size 500 tokens is at the limit of E5's 512-token window once the `passage:` prefix is added. | Chunking reduced to about 350 tokens, counted with the E5 tokenizer. |
| 4 | `user_memories` has no embedding field, so semantic memory retrieval is impossible as designed. | Schema updated, plus a vector index on memories. |
| 5 | Safety is deferred to Phase 5, but this is an emotional-support product. | Safety pipeline designed in detail and moved to the first chat milestone. |
| 6 | No Groq free-tier budgeting, even though memory extraction, summarization and chat all consume the same quota. | LLM call budget and priority rules added. |
| 7 | No ingestion job model, duplicate detection, or resumability. | `ingestion_jobs` collection, content hash, idempotent batches. |
| 8 | Hindi support is listed, but PDF extraction, sentence splitting and LLM quality for Hindi are not addressed. | Hindi-specific requirements added. |
| 9 | Voice: no echo or barge-in handling, no Hinglish handling, no background behavior. | Voice requirements expanded. |
| 10 | No auth design, backups, observability targets, measurable NFRs, risks, or open questions. | Added. |
| 11 | Vector search treated as Atlas-only. | Abstraction plus a simple in-app fallback (see D3). |
| 12 | Quotes could be hallucinated. | Programmatic quote verification added. |

---

## 1. Product Overview

Book Buddy is a personal reading companion. It combines two sources of understanding:

1. **The books** the user has uploaded (via Retrieval-Augmented Generation).
2. **The reader** (via a persistent, user-editable memory).

The LLM connects the two through natural text or voice conversation, so answers fit both the question and the person asking.

**Vision:** Help people turn the wisdom of books into a deeper understanding of themselves and their lives.

**Principles:** Personalization · Contextual understanding · Emotional intelligence (grounded, non-judgmental, no overclaiming) · Continuity · Low-friction access (voice) · **\[NEW\] User control and transparency** (the user can always see, edit and delete what the system knows, and see *why* an answer was given).

## 2. Problem Statement

Existing PDF-chat apps retrieve passages but do not know the reader. A user asking "Why do I keep worrying about things that haven't happened yet?" gets a generic explanation of anxiety. Book Buddy should recall relevant past conversations, understand the user's patterns, retrieve relevant passages from *their* books, connect the ideas to their situation, and encourage reflection without sounding generic or judgmental.

## 3. Target Users

- **Initial:** one individual (the builder), private, personal PDF collection, English and Hindi.
- **Future:** readers of personal-development and philosophy books who want to discuss ideas and apply them.
- **\[NEW\] Design constraint:** every table carries `userId`, and every query is user-scoped from day one, so multi-user support is a later configuration change rather than a rewrite.

## 4. Scope

### 4.1 In scope (v1)

Android app; PDF upload and text extraction; chunking and local embeddings; vector retrieval; Groq-powered RAG with page citations; persistent conversations and summaries; personal memory with management UI; emotional-support behavior with safety handling; native STT/TTS in English and Hindi; basic reading progress.

### 4.2 Out of scope (v1)

Public multi-user platform, social features, payments, paid infrastructure, iOS, book marketplace, analytics dashboards, autonomous psychological profiling, diagnosis, guaranteed offline LLM, OCR (later enhancement, but the app must detect and report scanned PDFs clearly).

## 5. Key Architectural Decisions **\[NEW\]**

These were implicit or contradictory in v1.0. Each is a decision to confirm before Phase 1 ends.

### D1. PDF ingestion path **\[CHANGED\]**

v1.0 says the PDF never leaves the device but also puts PDFBox on the backend. Pick one:

| Option | How | Pros | Cons |
| --- | --- | --- | --- |
| **A (recommended for v1):** transient upload | App sends the PDF to the backend over HTTPS. The backend extracts text with PDFBox, stores only text, chunks and embeddings, then **deletes the PDF**. | Simple, robust, best extraction quality, no Flutter PDF-parsing dependency, easy Hindi handling. | PDF binary briefly leaves the phone (to your own backend). |
| B: on-device extraction | A Flutter/Android library extracts text per page and sends text in batches. | The PDF never leaves the device. | Weaker library support, memory pressure on large books, harder Devanagari handling. |

Whichever option is chosen, update the privacy statement to match. The original PDF always stays on the device for reading, and only derived text and embeddings are stored on the backend.

### D2. How the phone reaches the backend **\[NEW\]**

A backend on a laptop is unreachable away from home Wi-Fi. Options, all free:

- **Tailscale** (private VPN mesh, recommended for single-user): the phone reaches the laptop from anywhere, with no public exposure.
- Cloudflare Tunnel (public HTTPS URL; requires strong auth).
- Free-tier host (cold starts, limited RAM; the embedding model may not fit).

The backend must still require authentication (Section 11) even behind a VPN.

### D3. Vector search strategy **\[CHANGED\]**

- Atlas free tier (M0) has historically supported Atlas Vector Search with tight limits (storage, number of search indexes). Verify current limits.
- A local MongoDB Community deployment may or may not include vector search depending on version and components. Verify.
- **Scale reality:** one user, roughly 10–100 books, about 1,000 chunks per book, 384-dim vectors. That is on the order of 100k vectors at most, so brute-force cosine similarity in memory (or a simple pre-filtered scan) is fast enough.
- **Decision:** hide retrieval behind `VectorSearchRepository` with two implementations: `AtlasVectorSearchRepository` and `InMemoryVectorSearchRepository` (loads a user's vectors at startup or on demand). Start with whichever works with zero setup friction, and switch via configuration.

### D4. Embedding runtime in Java **\[NEW\]**

Running `multilingual-e5-small` inside Spring Boot requires an ONNX export plus ONNX Runtime (or Deep Java Library) and the matching tokenizer (SentencePiece/XLM-R). Facts to design around:

- Output dimension: **384**.
- Maximum input: **512 tokens**.
- Prefixes are mandatory: `"passage: "` for stored chunks and `"query: "` for questions. Mixing them degrades quality silently.
- Vectors should be L2-normalized, with cosine similarity used consistently.
- Alternative if Hindi retrieval quality is weak: a larger multilingual model (for example BGE-M3 class), at higher CPU and RAM cost. Decide by benchmark, not assumption (Section 16).

### D5. LLM choice and behavior **\[NEW\]**

- `openai/gpt-oss-20b` on Groq is a reasoning model. Hidden reasoning tokens count toward latency and quota, so set reasoning effort to low for chat and voice, and consider higher effort only for memory consolidation.
- Verify: current model ID, free-tier limits (requests/min, requests/day, tokens/min, tokens/day), structured-output support, and **Hindi/Hinglish quality**. Benchmark at least one alternative model on the same evaluation set.
- Never silently fall back to a paid model. Enforce an allow-list of model IDs in configuration.

---

## 6. Functional Requirements: Book Management

**Objective:** maintain a personal PDF library with reliable processing.

**Requirements**

1. Select PDFs from the device (Android document picker).
2. Extract text, then detect metadata where possible: title, author, page count, language, upload date. **\[NEW\]** Use PDF info dictionary first, then first-page heuristics, then ask the user.
3. Edit metadata; list, search and delete books.
4. Show processing status per book: `QUEUED → EXTRACTING → CHUNKING → EMBEDDING → COMPLETED | FAILED | NEEDS_OCR`. **\[CHANGED\]** (adds states and a clear scanned-PDF outcome)
5. **\[NEW\] Duplicate detection** using a SHA-256 content hash of the file (or of the extracted text). On a duplicate, offer to open the existing book instead.
6. **\[NEW\] Scanned PDF detection:** if the extracted text per page is below a threshold, mark `NEEDS_OCR` with a plain-language message. Do not index garbage.
7. **\[NEW\] Corrupted, encrypted or password-protected PDFs** fail with a specific error. Do not crash the job.
8. **\[NEW\] Size limits:** configurable maximum file size and page count, with a clear message when exceeded.
9. **\[NEW\] Resumable processing:** a job that fails or is interrupted can be retried from the last completed stage. Processing is idempotent, with unique index `(bookId, chunkIndex)`.
10. **\[NEW\]** Deleting a book removes its chunks, embeddings, reading progress and book references in conversations (references degrade to "deleted book").

**Hindi and multilingual extraction **\[NEW\]****

- Some Hindi PDFs use legacy font encodings (for example Kruti Dev) that extract as garbage. Detect via script and dictionary heuristics, and flag as low-quality extraction.
- Sentence splitting must handle the Devanagari danda (`।`) as well as `.`, `?`, `!`.
- Store a per-book and per-chunk `language` value.

**Text cleaning **\[NEW\]** (explicit rules)**

Remove repeated headers, footers and page numbers; de-hyphenate line-break splits; merge hard-wrapped lines into paragraphs; normalize Unicode (NFC); drop tables of contents and index pages from the retrievable set, or tag them `FRONT_MATTER`/`BACK_MATTER` with lower retrieval weight.

---

## 7. Functional Requirements: Retrieval-Augmented Generation

### 7.1 Ingestion pipeline

Extract → clean → segment into paragraphs and sentences → chunk → attach metadata (bookId, page range, chapter if detectable, language) → embed (batched, with `passage:` prefix) → store → mark book `COMPLETED`.

### 7.2 Chunking **\[CHANGED\]**

- Target **about 350 tokens** with **about 60 token overlap**, measured with the **embedding model's tokenizer** (not a GPT tokenizer), so the prefix plus chunk stays under 512.
- Respect paragraph and sentence boundaries; never cut mid-sentence.
- Keep `pageStart`/`pageEnd` per chunk; store `chapterTitle` when detectable.
- All values are configuration, benchmarked on at least three real books (one Hindi if relevant).
- Re-embedding must be possible when the model or chunking changes: store `embeddingModel`, `embeddingVersion`, `chunkingVersion`.

### 7.3 Retrieval **\[CHANGED\]**

1. **Intent routing **\[NEW\]**:** classify each message as `BOOK_QUESTION`, `PERSONAL_REFLECTION`, `MIXED`, or `CHITCHAT`. A cheap heuristic or small LLM call decides whether to retrieve book passages. Pure emotional venting should not trigger forced book retrieval.
2. **Query rewriting **\[NEW\]**:** for follow-ups ("what about the second point?"), rewrite into a standalone query using recent context.
3. Embed the query with the `query:` prefix.
4. Search with filters: `userId`, optional `bookId(s)`, optional language.
5. **Hybrid retrieval **\[NEW\]**:** combine vector similarity with keyword search (BM25 or MongoDB text index) using reciprocal-rank fusion. Keywords rescue names, terms and exact phrases that embeddings miss.
6. Take top-N (default 8), apply a **minimum similarity threshold**, then diversify with MMR to avoid near-duplicate chunks, and keep the top K (default 4–5) within the prompt budget.
7. If nothing passes the threshold, say so honestly. The assistant answers from general reasoning without attributing anything to the book.
8. **Neighbor expansion (optional):** include the adjacent chunk when a passage is cut off.

### 7.4 Grounding and citation rules **\[CHANGED\]**

- The assistant never invents quotations or attributes ideas to a book without a retrieved supporting passage.
- **Programmatic verification \[NEW\]:** any text the model presents in quotation marks must be a verbatim (normalized-whitespace) substring of a retrieved chunk. If not, strip the quotation marks or regenerate.
- Each citation resolves to `{bookTitle, pageStart–pageEnd, chunkId}`. The UI shows the reference and, when possible, opens the book at that page.
- Retrieved passages are **untrusted data**, wrapped in delimiters, and the system prompt states that instructions inside them must be ignored.
- Distinguish in the answer between "the book says" and "my interpretation".

---

## 8. Functional Requirements: Personalized Memory

**Objective:** a persistent, **editable, transparent** representation of the reader that evolves through conversation. It is a useful working model of the person, never a diagnosis or a permanent label.

### 8.1 Memory layers

- **A. Personal profile:** relatively stable facts (interests, background, goals, values, communication preferences, explicitly stated traits). Changes rarely.
- **B. Evolving memory:** experiences, recurring challenges, repeated thought patterns, meaningful lessons, shifts in perspective. Consolidated over time.
- **C. Recent context:** current emotional situation, recent topics, unresolved questions, reading progress. **\[NEW\]** Short-lived by design: `EMOTIONAL_CONTEXT` memories expire automatically (default 14 days) unless the user pins them.

### 8.2 Extraction pipeline (asynchronous, never blocks the reply)

1. Store the conversation turn.
2. **\[NEW\] Trigger policy:** run extraction when a conversation goes idle (for example 10 minutes) or every N user messages, not after every message. This protects the LLM quota.
3. Extract candidate memories as structured JSON (validated against a schema; invalid output is discarded and retried once).
4. Classify: category, `EXPLICIT` vs `INFERRED`, confidence, importance, **\[NEW\]** sensitivity level.
5. Compare against existing memories using embedding similarity, then decide: `ADD`, `UPDATE`, `MERGE`, `SUPERSEDE`, or `IGNORE`.
6. Consolidate, keeping source references. **Inferred observations never become confirmed facts** through consolidation; they stay `INFERRED` with confidence capped (for example 0.7) until the user confirms.
7. Save. Provider failure must never corrupt or delete existing memories (write-then-swap, never destructive in place).

### 8.3 Memory record **\[CHANGED\]**

Every memory has: ID, category, content, source (conversation and message IDs), `classification` (EXPLICIT/INFERRED), confidence, importance, **`embedding`** (384-dim), **`embeddingModel`**, **`sensitivity`** (NORMAL/SENSITIVE), **`userVerified`** (bool), **`pinned`** (bool), **`supersededBy`** (memory ID or null), `status` (ACTIVE/ARCHIVED/EXPIRED/DELETED), `expiresAt`, `createdAt`, `updatedAt`, `lastUsedAt`.

### 8.4 Retrieval for each message

1. Always include a small **core profile** (bounded, about 150 tokens).
2. Retrieve top memories by vector similarity to the current message, combined with an importance and recency score. Exclude archived, expired and superseded memories.
3. Add the latest conversation summary only if relevant.
4. Enforce a hard token budget (Section 12.3). Not every memory goes into every prompt.
5. **\[NEW\] Transparency:** each assistant message records which memory IDs were used, so the UI can answer "why did you say that?" and the user can correct a memory from there.

### 8.5 Sensitive information and consent **\[NEW\]**

- Sensitive categories (health and mental health, sexuality, religion, politics, finances, relationships, legal issues, and similar) are **not inferred**. They are stored only when the user states them explicitly and memory is enabled, and they are flagged `SENSITIVE`.
- A first-run consent screen explains what is remembered, where it is stored, and that prompts are sent to a third-party LLM provider.
- **Incognito conversations:** a per-conversation toggle that disables memory extraction and excludes the conversation from summaries.
- A global switch pauses extraction. Users can delete any memory, clear all memory, and export everything.

### 8.6 Cold start **\[NEW\]**

An empty memory makes early sessions feel generic. Offer an optional, skippable onboarding of five to seven short questions (interests, what the user wants from reading, preferred tone and length, languages). Answers are stored as `EXPLICIT` memories.

### 8.7 User memory management

View, edit, delete, add manually, correct inferred observations, confirm inferred observations (sets `userVerified`), pin, clear history, toggle extraction. Grouped as: About me, Interests, Preferences, Goals, Experiences, Observed patterns.

---

## 9. Functional Requirements: Personalized Emotional Support

### 9.1 Behavior

Understand the concern, name the emotional context tentatively ("it sounds like…", never "you are…"), draw on relevant memories, bring in a book passage only when it genuinely helps, connect ideas to the user's situation, and **encourage reflection rather than prescribe decisions**.

The assistant should be empathetic without being saccharine, avoid generic motivation, avoid judgment, avoid pretending to know exactly how the user feels, offer honest and balanced views, ask a follow-up question when useful (at most one per reply), and keep replies concise by default. It must not force book references into unrelated conversations.

### 9.2 Safety pipeline **\[CHANGED\]** (build in the first chat milestone, not Phase 5)

1. **Pre-check on every user message** (before normal generation): a lightweight classifier combining keyword and phrase lists in English, Hindi and Hinglish with an LLM-based risk check. Levels: `NONE`, `ELEVATED`, `ACUTE` (self-harm, suicide, abuse, immediate danger).
2. **`ACUTE`:** bypass personalization and book retrieval. Respond with a warm, non-judgmental message that takes the person seriously, encourages reaching out to someone they trust, and provides region-appropriate crisis resources. Do not lecture, diagnose, or end the conversation.
3. **`ELEVATED`:** continue supportively, gently encourage professional support, and avoid book quotes that could be misread as advice.
4. **Resources must be configurable per locale** and verified before release. For India, candidates include Tele-MANAS (14416), KIRAN (1800-599-0019) and emergency services (112); confirm numbers are current and make them editable in configuration. Show the user's own saved trusted contact if they have set one.
5. **Never** diagnose; never present book passages as medical advice; never encourage reliance on the app; periodically and naturally encourage human connection and professional help when patterns warrant it.
6. **Safety events are logged** (level and timestamp only, with no message content in logs) so the behavior can be audited.
7. Safety cases are part of the regression test set (Section 16) and run before every prompt change.

### 9.3 Healthy-use guardrails **\[NEW\]**

Avoid manipulative engagement patterns (no guilt-based notifications, no streak pressure). Add a gentle nudge toward real-world support when conversations suggest heavy reliance. Provide a one-tap "talk to a human" resources screen in Settings.

---

## 10. Functional Requirements: Conversations and Voice

### 10.1 Conversation management

- Create, list, open, rename and delete conversations; auto-generated titles (after the first exchange); message timestamps.
- **Rolling summaries:** when the live window exceeds a threshold (for example about 3,000 tokens), summarize older turns. Preserve topics, decisions, personal facts, unresolved questions and emotional context. Raw messages are always retained for the user.
- **\[NEW\]** Failed generations: the user's message is saved first, the failure is shown with a **Retry** action, and nothing is lost.
- **\[NEW\]** Optional selection of which books are in scope for a conversation (all, one, or none).
- **\[NEW\]** Stop generation: cancels the SSE stream and the upstream LLM request; the partial reply is kept and marked `INTERRUPTED`.

### 10.2 Voice conversation

**Speech-to-text (Android native)**

- English (`en-IN`/`en-US`) and Hindi (`hi-IN`); partial results; automatic finalization; permission handling; clear recovery from errors and timeouts.
- **\[NEW\]** Check whether on-device recognition for the locale is installed and guide the user to download offline packs; otherwise warn that audio may be processed by Google's service.
- **\[NEW\] Hinglish/code-switching** is common. Treat recognition quality as a risk and evaluate (Section 16). Allow the user to set a default voice language and switch quickly.

**Text-to-speech (Android native)**

- Adjustable speed and pitch, voice selection, pause, resume, stop and restart.
- **\[NEW\]** Choose the TTS voice **per reply** based on detected response language. If no Hindi voice is installed, tell the user how to install one.
- **\[NEW\]** Strip markdown, citations and URLs before speaking. Speak citations as a short phrase ("from chapter two of The Untethered Soul") or skip them in voice mode.

**Conversation loop**

Listen → transcribe → send → stream reply → **speak sentence by sentence as sentences complete** → listen again. A state machine (`IDLE, LISTENING, THINKING, SPEAKING, ERROR`) lives in `VoiceProvider`.

**Barge-in and echo **\[NEW\]**:** with speaker output, the microphone can pick up the assistant's own voice. v1 behavior: pause recognition while speaking by default, and offer a **tap-to-interrupt** control. True hands-free barge-in is only enabled when headphones are connected, or after testing on the target device.

**Other voice requirements **\[NEW\]**:** keep the screen awake in voice mode; handle audio focus (pause for calls and other apps); handle Bluetooth headsets; run as a foreground service if background voice is desired; short, spoken-friendly replies in voice mode (shorter system-prompt style, no lists).

**Latency targets:** see Section 15. Practical levers: low reasoning effort, concise prompts, streaming, sentence-level TTS start, and skipping retrieval when the intent router says it is not needed.

---

## 11. Privacy, Security and Compliance

1. Original PDFs stay on the device for reading; the backend retains only derived text, chunks and embeddings (see D1). If transient upload is used, the file is deleted after processing and this is stated in the app.
2. API keys (Groq and others) live only on the backend, loaded from environment variables or a secrets file excluded from Git. Never in Flutter code or the repository. Add secret scanning (for example gitleaks) to CI.
3. **\[NEW\] Authentication:** even single-user, the backend is reachable over a network. Minimum: a long random device token (or JWT issued after a one-time pairing) sent in the `Authorization` header, stored in Android Keystore/`flutter_secure_storage`, plus rate limiting. Spring Security configured deny-by-default.
4. HTTPS for all remote traffic (Tailscale/Cloudflare Tunnel provide transport security; avoid cleartext HTTP outside local development).
5. **\[NEW\] Third-party disclosure:** prompts (including memories, conversation text and book passages) go to the LLM provider. Review the provider's current data-retention and training-use terms for the free tier, and disclose this in the consent screen. Send only the context required.
6. **\[NEW\] Data rights:** delete a book, a conversation, a memory, or all data; export all data as JSON. Deletion cascades to chunks, embeddings, summaries and memory sources. Hard delete, not soft delete, for user-initiated deletion.
7. **\[NEW\] Logging hygiene:** never log message content, memories or book text at INFO level; use IDs and sizes. Redact tokens.
8. **\[NEW\] Backups:** scheduled `mongodump` (or Atlas backup if available on the chosen tier) with an encrypted copy stored off the laptop. Test a restore once.
9. **\[NEW\] Encryption at rest:** enable disk encryption on the host machine; consider field-level encryption for `messages.content` and `user_memories.content` if the host is shared.
10. **\[NEW\] Prompt-injection defense:** book text and any user-imported content are untrusted. Delimit, instruct the model to ignore embedded instructions, never give the model tools that can modify data based on retrieved text.
11. **\[NEW\] Copyright note:** books are the user's own copies for personal use. Do not expose book text to anyone else or build any sharing feature without revisiting this.

---

## 12. Technical Architecture

### 12.1 Technology stack

| Layer | Technology | Notes |
| --- | --- | --- |
| Mobile | Flutter / Dart, Provider | Android only in v1 |
| Backend | Java 21, Spring Boot 3.x, Gradle | REST + SSE; consider virtual threads |
| Database | MongoDB | Atlas M0 or local; see D3 |
| Vector search | Atlas Vector Search **or** in-memory cosine | Behind `VectorSearchRepository` |
| Embeddings | `multilingual-e5-small` via ONNX Runtime/DJL | 384-dim, 512-token max, prefixes required |
| LLM | Groq, `openai/gpt-oss-20b` (verify) | Allow-listed models only |
| PDF | Apache PDFBox | Backend, per D1 |
| STT / TTS | Android native | Flutter plugins such as `speech_to_text`, `flutter_tts` (verify maintenance status) |
| HTTP/SSE client | `dio` or `http` + SSE handling | Cancelable streams |
| Testing | JUnit 5, Testcontainers (MongoDB), WireMock for Groq | **\[NEW\]** |
| CI | GitHub Actions | **\[NEW\]** build, test, secret scan |

### 12.2 Backend structure

Keep the package layout from v1.0 (`config, book, document, embedding, vector, rag, conversation, memory, ai, common`) and add:

- `safety` **\[NEW\]**: `RiskClassifier`, `CrisisResponseService`, locale resource config.
- `ingestion` **\[NEW\]**: `IngestionJobService`, stage runners, retry policy.
- `quota` **\[NEW\]**: `LlmBudgetService` tracking requests/tokens against free-tier limits, with priority queueing.
- `prompt` **\[NEW\]**: versioned prompt templates loaded from resources (not hard-coded), each with an ID such as `chat.system.v3`.

RAG, memory and conversation logic depend on the `LlmProvider` and `EmbeddingProvider` interfaces only. Extend `LlmProvider` with: streaming (`Flux<String>`/callback), cancellation, a `reasoningEffort` option, JSON-schema output, and structured error types (`RateLimited(retryAfter)`, `Unavailable`, `InvalidOutput`).

### 12.3 Prompt assembly and token budget **\[NEW\]**

Components, in order: system identity and behavior → safety rules → core profile → relevant memories → conversation summary → recent messages → retrieved passages (delimited, untrusted) → current question.

Indicative budget (tune by measurement):

| Component | Tokens |
| --- | --- |
| System + safety rules | 600 |
| Core profile | 150 |
| Relevant memories | 300–400 |
| Conversation summary | 300 |
| Recent messages | 800 |
| Book passages (4–5 × \~350) | 1,500–1,750 |
| Question | 100 |
| **Total input** | **about 4,000–4,300** |

The prompt builder enforces limits by dropping lowest-priority items first (passages beyond K, then older messages, then lower-ranked memories). Label provenance in the prompt: `USER-STATED`, `INFERRED`, `BOOK EXCERPT`.

### 12.4 LLM quota management **\[NEW\]**

Chat, extraction, consolidation, summarization, intent routing, and safety checks all draw on one free quota. Rules:

- Priority order: safety > live chat > summaries > memory extraction > consolidation/rebuild.
- Background jobs are deferred when the remaining budget is low and resume later (a durable queue in MongoDB is sufficient).
- Batch work: one extraction call per conversation window, not per message; consolidate on a schedule (for example nightly or after N new memories).
- Cache the intent router and query rewrites where practical; skip the LLM for trivial intents using heuristics.
- Surface quota exhaustion in the UI honestly, with an estimated retry time.

---

## 13. Data Model (MongoDB)

Keep the collections from v1.0 and apply the following changes. All documents carry `userId`. Reference between collections; never embed unbounded arrays.

| Collection | Changes **\[CHANGED/NEW\]** |
| --- | --- |
| `users` | Add `consent` (version, timestamps), `memoryEnabled`, `defaultLanguage`, `crisisContacts`. |
| `books` | Add `contentHash`, `fileSizeBytes`, `extractionQuality`, `languageConfidence`, `failureReason`, `chunkCount`, `chunkingVersion`. `fileUri` remains a device-local reference (informational only). |
| `book_chunks` | Add `chapterTitle`, `language`, `tokenCount`, `chunkingVersion`, `embeddingVersion`, `sectionType` (BODY/FRONT/BACK). Unique index `(bookId, chunkIndex)`. Vector index on `embedding` with filter fields `userId`, `bookId`. |
| `ingestion_jobs` **\[NEW\]** | `bookId`, `stage`, `status`, `attempts`, `lastError`, `progress`, timestamps. |
| `conversations` | Add `incognito`, `bookScope`, `messageCount`, `lastSummarizedMessageId`. |
| `messages` | Add `status` (COMPLETE/INTERRUPTED/FAILED), `citations` (chunkId, book, pages), `memoryIdsUsed`, `modelId`, `promptVersion`, `tokenUsage`, `safetyLevel`, `language`, `inputMode` (TEXT/VOICE). |
| `user_memories` | Add the fields in Section 8.3, including `embedding`. Vector index with filter `userId`, `status`. |
| `memory_summaries` | Add `version`, `generatedFromMemoryIds`, `userEdited`. |
| `reading_progress` | Keep; add `lastOpenedAt`. |
| `llm_usage` **\[NEW\]** | Per-call model, purpose, token counts, latency, outcome (no content). Drives quota logic and observability. |
| `jobs_queue` **\[NEW\]** | Durable background tasks (extraction, summarization, consolidation) with priority and `notBefore`. |

**Indexes:** `(userId, updatedAt)` on conversations, `(conversationId, createdAt)` on messages, `(userId, status, category)` on memories, `(userId, contentHash)` unique on books, plus a text index on `book_chunks.content` for hybrid search.

**Atlas M0 sizing check \[NEW\]:** about 1,000 chunks per book × (384 floats ≈ 1.5 KB + about 2 KB text) ≈ 3–4 MB per book, so tens of books fit within the free storage limit, but verify the current cap and index limits before committing.

---

## 14. API Requirements

**Conventions \[NEW\]:** all endpoints under `/api/v1`; JSON DTOs only; ISO-8601 UTC timestamps; cursor-based pagination (`limit`, `cursor`) for lists; a uniform error body `{code, message, retryable, retryAfterSeconds?}`; `Idempotency-Key` supported on POSTs that create data; correlation ID header returned on every response; token auth on every route except health.

### 14.1 Books

| Method | Endpoint | Purpose |
| --- | --- | --- |
| POST | `/books` | Create book record (metadata, hash); returns 409 with existing ID if duplicate |
| POST | `/books/{id}/file` | Upload PDF for transient processing (D1-A), or |
| POST | `/books/{id}/pages` | Send extracted page text in batches (D1-B); idempotent per page range |
| POST | `/books/{id}/process` | Start or resume processing |
| POST | `/books/{id}/cancel` | Cancel an active job |
| GET | `/books`, `/books/{id}` | List, details |
| PATCH | `/books/{id}` | Edit metadata |
| GET | `/books/{id}/status` | Stage, progress, error (poll, or SSE for progress) |
| DELETE | `/books/{id}` | Delete with cascade |
| GET | `/books/{id}/chunks?page=` | Retrieve passages (used for citation "open at page") |

### 14.2 Conversations

| Method | Endpoint | Purpose |
| --- | --- | --- |
| POST/GET | `/conversations` | Create (with `incognito`, `bookScope`) / list |
| GET/PATCH/DELETE | `/conversations/{id}` | Read, rename, delete |
| GET | `/conversations/{id}/messages` | History (paginated) |
| POST | `/conversations/{id}/messages` | Non-streaming send |
| POST | `/conversations/{id}/messages/stream` | SSE stream |
| POST | `/conversations/{id}/messages/{mid}/regenerate` | Retry a failed or interrupted reply |

**SSE contract \[NEW\]:** events `meta` (messageId), `token` (text delta), `citations`, `safety` (if a crisis resource card should render), `done` (usage), `error` (code, retryable). Heartbeat comments every 15 seconds. Client cancel (closing the connection) must cancel the upstream Groq call. Beware proxy buffering (tunnels and reverse proxies) when testing streaming.

### 14.3 Memory and settings

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/memory/profile` | Consolidated profile |
| GET/POST | `/memory` | List (filter by category/status) / add manual |
| PATCH/DELETE | `/memory/{id}` | Edit (sets `userVerified`) / delete |
| POST | `/memory/{id}/confirm` | Confirm an inferred observation |
| POST | `/memory/rebuild` | Async rebuild; returns a job ID |
| DELETE | `/memory` | Clear all |
| GET/PUT | `/settings` | Consent, memory toggle, language, voice prefs |
| GET | `/export` | Full data export |
| DELETE | `/account/data` | Delete all user data |
| GET | `/health` | Liveness and dependency checks (Mongo, embedding model loaded, LLM reachable) |

### 14.4 Reading progress

`GET /reading-progress`, `PUT /reading-progress/{bookId}` as in v1.0.

---

## 15. Non-Functional Requirements (Measurable) **\[CHANGED\]**

| Area | Target (initial, tune after measurement) |
| --- | --- |
| Chat latency | Time to first streamed token under 2.5 s (p50), under 5 s (p95) on home network |
| Voice latency | End of speech to first spoken word under 3 s (p50) |
| Retrieval | Query embedding + search + fusion under 400 ms for a 100k-chunk corpus |
| Ingestion | A 300-page text PDF completes in under 5 minutes on the host machine; progress visible throughout |
| Embedding throughput | Batch size configurable; target at least 20 chunks/s on the dev machine (measure) |
| Memory footprint | Backend with the embedding model loaded runs within the host's available RAM (measure; document the minimum) |
| Availability | Best effort (personal use); graceful degradation when LLM or network is down |
| Reliability | No message loss; failed jobs resumable; background jobs idempotent; LLM failures never alter stored memories |
| Data safety | Backups at least daily; one tested restore |
| Maintainability | Versioned prompts, embeddings and chunking; structured JSON logging with correlation IDs; config via environment |
| Accessibility **\[NEW\]** | Support system font scaling, screen readers (semantic labels), sufficient contrast, dark mode |
| Localization **\[NEW\]** | UI strings externalized (English first, Hindi later); locale-aware dates |

---

## 16. Testing and Evaluation

### 16.1 Engineering tests **\[NEW\]**

- Unit tests for chunking, text cleaning, token budgeting, prompt assembly, quote verification and memory merge logic.
- Integration tests with Testcontainers (MongoDB) and a mocked Groq (WireMock) including 429, timeout, malformed JSON and stream-cut scenarios.
- Contract tests for the SSE stream and the error format.
- Flutter widget tests for chat and memory screens; integration test for the voice state machine with fakes.

### 16.2 Evaluation sets **\[NEW\]**

Build small, versioned golden sets (about 30–50 items each) and rerun them on every model, prompt, chunking or embedding change.

- **Retrieval set:** question → expected pages. Metrics: recall@5, MRR, no-answer handling. Include Hindi and Hinglish queries and at least one book per language.
- **Embedding benchmark:** compare `multilingual-e5-small` against one larger multilingual model on the retrieval set before locking the choice.
- **Faithfulness set:** answers checked for unsupported claims and fabricated quotes (the quote verifier should catch 100% of fabricated quotes).
- **Memory set:** scripted multi-session dialogues with expected extractions, expected non-extractions (to catch invented facts and sensitive inference), merges, updates and expiry.
- **Emotional-support rubric:** scenarios from v1.0 (career uncertainty, self-doubt, overthinking, disappointment, relationships, low motivation, habits, philosophy), scored 1–5 on empathy, non-judgment, honesty, relevance, appropriate use of books, avoiding over-claiming, and brevity. Use LLM-as-judge for screening plus periodic manual review.
- **Safety set (blocking):** explicit and implicit self-harm phrasing in English, Hindi and Hinglish; expected level and response behavior. A regression here blocks release.
- **Voice tests:** recognition accuracy on a fixed phrase list in each language, time from end of speech to first audio, interruption responsiveness, recovery after errors, behavior with and without headphones.

### 16.3 Early spikes **\[NEW\]** (do these before building features)

1. Run `multilingual-e5-small` in Java (ONNX) and confirm embeddings match a Python reference within tolerance.
2. Measure real Groq free-tier limits and streaming latency for the chosen model, in English and Hindi.
3. Test Android STT/TTS in Hindi on the target phone, including offline packs and Hinglish.
4. Test phone-to-laptop connectivity (Tailscale) including SSE streaming.
5. Confirm vector search path (Atlas M0 or in-memory) with a realistic corpus.

---

## 17. Flutter Application Requirements

**Screens:** Onboarding and consent (**\[NEW\]**), Home, Library, Add Book/Processing, Book Details, Chat (text and voice), Conversation History, Memory, Settings (language, voice, privacy, data export/delete, crisis resources).

**Home:** continue reading, recent conversations, prominent "talk to Book Buddy" entry.

**Library:** cover or placeholder, title, author, progress, processing status with retry, search and filter, add.

**Chat:** text and voice input, streaming rendering, stop button, tap a citation to see the passage (and open the book at the page where possible), retry on failure, incognito indicator, "why this answer?" panel listing memories and passages used **\[NEW\]**, copy and share message.

**Memory:** grouped lists, edit, delete, confirm inferred, pin, pause memory, clear all.

**Architecture:** Provider with the suggested providers; repositories and services (not widgets) own networking; typed models; offline handling (queue unsent messages, show cached history); secure token storage; centralized error mapping to friendly messages; **\[NEW\]** a simple PDF viewer so citations can open at a page (evaluate a Flutter PDF package or hand off to a system viewer with a page hint).

**Reading features to consider \[NEW\]:** current-page tracking, bookmarks, highlights with notes (highlights can later be used as high-signal retrieval and memory inputs).

---

## 18. Cost, Infrastructure and Failure Handling

### 18.1 Cost

Keep Mode A (fully local backend and MongoDB, Groq remote) and Mode B (Atlas free tier). **\[NEW\]** Add the cost of availability: Mode A requires the host machine to be on and reachable (see D2). Hosting the backend on an always-on free service is an option only if the embedding model and RAM requirements fit; otherwise run embeddings at ingestion time on the laptop and keep only query-time embedding in the hosted service (small, fast).

### 18.2 Failure handling

| Failure | Behavior |
| --- | --- |
| Groq 429/quota | Show "temporarily unavailable" with retry time; queue background jobs; voice and text input still work |
| LLM down/timeouts | Save the user's message, offer retry; no partial writes to memory |
| Malformed structured output | Retry once with a repair prompt, then discard and log |
| Embedding failure | Retry with backoff; book stays `EMBEDDING` and resumable |
| MongoDB down | Health check fails; app shows a clear offline state |
| PDF errors | Specific failure reason and suggested action |
| Backend unreachable | Cached history readable; messages queued |
| Missing voice for language | Explain how to install; fall back to text |

Never silently switch to a paid model or a different provider. Any provider change is explicit configuration.

---

## 19. Development Roadmap **\[CHANGED\]**

Each phase has exit criteria. Safety and a thin voice prototype move earlier than in v1.0.

| Phase | Scope | Exit criteria |
| --- | --- | --- |
| 0. Spikes | Section 16.3 | Go/no-go decisions recorded for D1–D5 |
| 1. Foundation | Flutter + Spring Boot + MongoDB, auth, CI, health, book metadata CRUD | App talks to backend securely from home and remotely |
| 2. Ingestion and RAG | Extraction, cleaning, chunking, embeddings, vector search, hybrid retrieval, cited answers, quote verification | Retrieval set meets agreed recall@5; zero fabricated quotes on the faithfulness set |
| 3. Chat and safety | Conversations, streaming, stop/retry, summaries, **safety pipeline**, consent screen | Safety set passes; messages never lost on failure |
| 4. Memory | Extraction, consolidation, retrieval, management UI, incognito, expiry | Memory set passes; user can see, edit and delete everything |
| 5. Emotional quality | Prompt iteration against the rubric, intent routing, "why this answer?" | Rubric average at or above target; manual review of 20 transcripts |
| 6. Voice | STT/TTS en+hi, sentence-level TTS, interruption, voice mode state machine | Latency target met on the target phone |
| 7. Hardening | Quota manager tuning, caching, backups, observability, accessibility, data export | Restore test passed; one-week daily use without data loss |

---

## 20. Success Criteria

Functional (from v1.0, retained): upload and chat with a PDF; grounded answers; persistent conversations; accurate and editable memory; combined personal and book context; native voice in English and Hindi where supported; no paid APIs or servers.

**\[NEW\] Measurable additions:** retrieval recall@5 at or above the agreed target on your golden set; zero fabricated quotes; zero safety-set failures; at least 90% of extracted memories judged accurate on manual review; memory used in a response only when relevant (at most 10% judged irrelevant personalization); latency targets in Section 15 met; you actually use it for at least four weeks and keep it (the best product signal for a personal project).

---

## 21. Risks and Mitigations **\[NEW\]**

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Free-tier quota or terms change | LLM stops or limits | Provider abstraction, budget manager, second free provider evaluated, explicit config |
| Hindi/Hinglish quality (STT, embeddings, LLM, TTS) | Poor core experience for Hindi use | Early spikes and benchmarks; set per-language expectations |
| Chosen vector option unavailable on free tier | Blocks retrieval | In-memory fallback behind the same interface |
| Phone cannot reach laptop | App unusable away from home | Tailscale; document fallback hosting |
| Harmful or inappropriate emotional response | User harm | Safety pipeline, rubric, regression set, healthy-use guardrails |
| Memory inaccuracies or creepy over-personalization | Loss of trust | Explicit vs inferred, transparency panel, easy edit/delete, relevance filtering |
| Privacy exposure via third-party LLM | Sensitive data disclosed | Consent screen, minimal context, review provider terms, incognito mode |
| Poor PDF extraction (scanned, legacy Hindi fonts, columns) | Bad retrieval | Quality scoring, `NEEDS_OCR` state, per-book quality flag |
| Embedding model RAM/CPU on host | Slow or unstable backend | Measure; batch size tuning; smaller model fallback |
| Scope creep | Never ships | Phase exit criteria; keep v1 scope table as the contract |
| Single point of failure (laptop, no backups) | Data loss | Scheduled encrypted backups, tested restore |

---

## 22. Open Questions **\[NEW\]**

1. Ingestion path: transient upload (D1-A) or on-device extraction (D1-B)?
2. Remote access: Tailscale, tunnel, or free host?
3. Vector search: Atlas M0 or in-memory first?
4. Primary reading language: mostly English, mostly Hindi, or mixed? This drives the embedding model and LLM benchmarks.
5. Typical book sizes and count (affects storage and ingestion targets)?
6. Should voice mode work with the screen off?
7. Is a built-in PDF reader required in v1, or is "open in an external viewer" acceptable?
8. Is this strictly personal, or could it be shared with friends or family later (affects auth and data isolation effort now)?

---

## 23. Product Suggestions (Beyond v1 Scope)

- **Highlights and notes as memory and retrieval signals:** what the user highlights is a strong sign of what matters to them.
- **Reflection journal:** short daily or weekly check-ins that feed memory; "how have my thoughts on X changed over three months?" is a feature plain PDF chat cannot offer.
- **Cross-book synthesis:** "How do these three books treat the idea of detachment?" with citations from each.
- **Passage resurfacing:** gently surface a relevant passage when a recurring pattern appears (opt-in, never nagging).
- **Reading-aware mode:** answers aware of the user's current page, with spoiler-light behavior for fiction.
- **Quote cards:** export a verified quote with page reference as an image.
- **Memory timeline:** visualize how themes and mood-related topics evolve (kept private, optional).
- **Voice-first journaling:** talk freely, get a short structured reflection and optional memory suggestions the user approves.
- **Calibrated tone setting:** gentle, direct, or Socratic, user-selectable.
- **OCR** via ML Kit for scanned books once the core is stable.
- **Optional local LLM mode** for private conversations when a capable local model is practical, as an alternative to sending sensitive content to a cloud provider.

---

## 24. Final Product Definition

Book Buddy is a personalized reading and reflection companion that grows with its reader. **Knowledge from books + knowledge about the reader + context from ongoing conversation**, connected through natural text and voice, with the reader always in control of what is remembered and why an answer was given.
