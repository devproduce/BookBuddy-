#!/usr/bin/env python3
"""Phase 0 Groq spike: measure TTFT and streaming for EN/HI prompts.

Usage:
  set GROQ_API_KEY=...
  python docs/spikes/groq_spike.py
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.request

API = os.environ.get("GROQ_BASE_URL", "https://api.groq.com/openai/v1")
KEY = os.environ.get("GROQ_API_KEY", "")
MODEL = os.environ.get("GROQ_MODEL", "llama-3.3-70b-versatile")


def stream_chat(prompt: str, label: str) -> dict:
    if not KEY:
        raise SystemExit("GROQ_API_KEY required")
    body = json.dumps(
        {
            "model": MODEL,
            "messages": [{"role": "user", "content": prompt}],
            "stream": True,
            "temperature": 0.4,
        }
    ).encode()
    req = urllib.request.Request(
        f"{API}/chat/completions",
        data=body,
        headers={
            "Authorization": f"Bearer {KEY}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    t0 = time.perf_counter()
    ttft = None
    tokens = 0
    text = []
    with urllib.request.urlopen(req, timeout=120) as resp:
        for raw in resp:
            line = raw.decode("utf-8", errors="replace").strip()
            if not line.startswith("data:"):
                continue
            payload = line[5:].strip()
            if payload == "[DONE]":
                break
            data = json.loads(payload)
            delta = data["choices"][0]["delta"].get("content") or ""
            if delta and ttft is None:
                ttft = time.perf_counter() - t0
            if delta:
                tokens += 1
                text.append(delta)
    total = time.perf_counter() - t0
    result = {
        "label": label,
        "model": MODEL,
        "ttft_s": round(ttft or -1, 3),
        "total_s": round(total, 3),
        "chunks": tokens,
        "preview": "".join(text)[:240],
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return result


def main() -> None:
    results = [
        stream_chat("In one short paragraph, what does it mean to observe a thought?", "en"),
        stream_chat("एक छोटे अनुच्छेद में बताएँ कि विचार को देखना क्या होता है?", "hi"),
    ]
    out = os.path.join(os.path.dirname(__file__), "results", "groq_spike.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"Wrote {out}", file=sys.stderr)


if __name__ == "__main__":
    main()
