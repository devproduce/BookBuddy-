# ADR 0001 – Core architecture

## Status

Accepted

## Context

Book Buddy needs cloud-compatible backend, multi-user-ready data, transient PDF processing, and Atlas Vector Search.

## Decision

- Spring Boot 3.x on AWS EC2
- MongoDB Atlas for documents + vector search
- Transient PDF upload; delete after extract
- Email/password JWT auth with `userId` scoping
- Vite+React web first; Flutter Android later
- Groq LLM (allow-list); multilingual-e5-small ONNX embeddings (384-d)

## Consequences

EC2 must have enough RAM for the embedding model. Clients keep original PDFs; backend stores derived text/embeddings only.
