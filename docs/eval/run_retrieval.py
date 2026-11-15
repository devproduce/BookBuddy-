#!/usr/bin/env python3
"""Live retrieval eval: replays retrieval.jsonl against a running Book Buddy backend and prints recall@5, MRR and
no-answer handling. Standard library only.

Usage (PowerShell or bash):

    python docs/eval/run_retrieval.py \
        --book sample-en="The Lighthouse" --book sample-hi="लाइटहाउस" \
        --email you@example.com --password '...'

    # or reuse a token:  set BB_TOKEN=... (the value of `bookbuddy.token` in the browser's local storage)

Each --book maps the "book" label used in retrieval.jsonl to the title of a book in your library (status
COMPLETED). Items whose label is not mapped are skipped. Use --json to write per-item results to a file.

An item is a hit at rank r when the r-th citation is from the right book and its page range overlaps one of
expectedPages. "answerable": false items pass when the answer has no citations (the backend's no-answer reply).
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
K = 5


def call(base: str, method: str, path: str, token: str | None = None, body: dict | None = None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Accept", "application/json")
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=120) as res:
            text = res.read().decode("utf-8")
            return json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        raise SystemExit(f"{method} {path} failed with HTTP {e.code}: {detail}") from None
    except urllib.error.URLError as e:
        raise SystemExit(f"Cannot reach {base}: {e.reason}") from None


def pages_overlap(start, end, expected: list[int]) -> bool:
    if start is None:
        return False
    end = start if end is None else end
    return any(start <= p <= end for p in expected)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", default=os.environ.get("BB_API", "http://localhost:8080/api/v1"))
    ap.add_argument("--email", default=os.environ.get("BB_EMAIL"))
    ap.add_argument("--password", default=os.environ.get("BB_PASSWORD"))
    ap.add_argument("--token", default=os.environ.get("BB_TOKEN"))
    ap.add_argument("--book", action="append", default=[], metavar="LABEL=TITLE")
    ap.add_argument("--file", default=str(HERE / "retrieval.jsonl"))
    ap.add_argument("--json", help="write per-item results to this file")
    args = ap.parse_args()

    base = args.base_url.rstrip("/")
    token = args.token
    if not token:
        if not (args.email and args.password):
            raise SystemExit("Provide --token (or BB_TOKEN), or --email and --password.")
        token = call(base, "POST", "/auth/login", body={"email": args.email, "password": args.password})["token"]

    wanted = {}
    for spec in args.book:
        label, _, title = spec.partition("=")
        if not title:
            raise SystemExit(f"--book expects LABEL=TITLE, got {spec!r}")
        wanted[label.strip()] = title.strip()

    library = {b["title"]: b for b in call(base, "GET", "/books", token)}
    book_ids: dict[str, str] = {}
    for label, title in wanted.items():
        book = library.get(title)
        if not book:
            raise SystemExit(f"No book titled {title!r} in the library. Have: {sorted(library)}")
        if book.get("status") != "COMPLETED":
            raise SystemExit(f"{title!r} is {book.get('status')}, not COMPLETED yet.")
        book_ids[label] = book["id"]

    items = [json.loads(l) for l in Path(args.file).read_text(encoding="utf-8").splitlines() if l.strip()]
    results = []
    skipped = 0
    for item in items:
        book_id = book_ids.get(item["book"])
        if not book_id:
            skipped += 1
            continue
        answer = call(base, "POST", "/rag/ask", token, {"question": item["question"], "bookIds": [book_id]})
        citations = answer.get("citations", [])
        rank = None
        for i, c in enumerate(citations[:K], start=1):
            if c["bookId"] == book_id and pages_overlap(c.get("pageStart"), c.get("pageEnd"), item["expectedPages"]):
                rank = i
                break
        if item["answerable"]:
            ok = rank is not None
        else:
            ok = len(citations) == 0
        results.append({"id": item["id"], "lang": item["lang"], "answerable": item["answerable"],
                        "rank": rank, "ok": ok, "citations": len(citations)})

    scored = [r for r in results if r["answerable"]]
    no_answer = [r for r in results if not r["answerable"]]
    if not results:
        raise SystemExit("Nothing ran: map at least one --book label used in the file.")

    def pct(n, d):
        return "n/a" if d == 0 else f"{100 * n / d:.0f}%"

    print(f"Ran {len(results)} items ({skipped} skipped: book label not mapped)\n")
    print(f"{'id':<9}{'lang':<10}{'result':<8}{'rank':<6}")
    for r in results:
        print(f"{r['id']:<9}{r['lang']:<10}{'ok' if r['ok'] else 'MISS':<8}{r['rank'] or '-':<6}")
    hits = sum(1 for r in scored if r["rank"])
    mrr = sum(1 / r["rank"] for r in scored if r["rank"]) / len(scored) if scored else 0.0
    print(f"\nrecall@{K}: {hits}/{len(scored)} ({pct(hits, len(scored))})")
    print(f"MRR:       {mrr:.3f}")
    print(f"no-answer: {sum(1 for r in no_answer if r['ok'])}/{len(no_answer)} handled")
    for lang in sorted({r["lang"] for r in scored}):
        sub = [r for r in scored if r["lang"] == lang]
        print(f"  {lang:<9} recall@{K}: {sum(1 for r in sub if r['rank'])}/{len(sub)}")

    if args.json:
        Path(args.json).write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
