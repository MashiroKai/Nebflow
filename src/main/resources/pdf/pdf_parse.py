#!/usr/bin/env python3
"""pdf_parse.py — the open-source PDF parser leg of the PDF note/ask feature.

Scope: turn one PDF into a per-page Markdown corpus that an ask answer can read
on demand. This script is the ONLY place that opens a PDF; the gateway never
parses (opening a PDF in the viewer or writing a note must never pay this cost).

Output layout (the cache entry shape, one directory per document):

    <out>/meta.json                 the manifest (see MANIFEST below)
    <out>/pages/p%04d.md            one Markdown file per page, 1-based
    <out>/media/p%04d-<index>.<ext> one file per non-decorative embedded image

Every image link inside `pages/pNNNN.md` is RELATIVE to the page file
(`../media/pNNNN-0.png`), so a page file plus the entry directory is relocatable
as a unit and the ask leg can cite `pages/pNNNN.md` without rewriting links.

Reading order: text blocks come from PyMuPDF's `page.get_text("blocks")` with
`sort=True` (vertical band first, then horizontal within the band). That is the
documented, reproducible order used for the Markdown body; it is NOT a
layout-analysis heuristic (no multi-column reflow model), which is stated as an
explicit non-goal in `src/main/scala/nebflow/core/pdf/module-doc.md`.

Tables: `page.find_tables()` (PyMuPDF's built-in table finder) yields the
detected tables as Markdown. Text blocks whose midpoint falls inside a detected
table rectangle are DROPPED, so table content appears exactly once (as the
Markdown table) instead of twice.

Images: `page.get_images(full=True)` + `doc.extract_image(xref)`. Images smaller
than --min-image-px on either side are treated as decoration and skipped. One
image is written once per page (repeated placements on the same page share the
file and are counted once).

Failure contract (readable failures are the point — a caller must be able to
tell "corrupt PDF" from "disk full"):

    exit 0  success; meta.json is present and complete
    exit 2  usage error (bad arguments)
    exit 3  parse failure; stdout carries ONE line of JSON
              {"ok":false,"error":"<CODE>","message":"..."}
            CODE in PDF_NOT_FOUND / PDF_INVALID / PDF_ENCRYPTED / PDF_EMPTY /
            PDF_BUSY / OUT_NOT_EMPTY / TABLE_FAILED / PARSE_FAILED
            Nothing is written to <out> except the staging directory, which the
            caller removes; a failed run never publishes a partial entry.

Parser identity: PyMuPDF (AGPL-3.0, https://pypi.org/project/PyMuPDF/), reported
in meta.json as {"name":"pymupdf","version":<version>} so a cache entry can be
invalidated when the parser changes.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import time

SCHEMA = 1
PARSER_NAME = "pymupdf"
# Bump when the Markdown shape changes: a cache entry carrying an older
# markdown_version is a miss (the gateway reparses instead of serving stale text).
MARKDOWN_VERSION = 1

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_PARSE = 3


def fail(code: str, message: str) -> None:
    sys.stdout.write(json.dumps({"ok": False, "error": code, "message": message}) + "\n")
    sys.stdout.flush()
    sys.exit(EXIT_PARSE)


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def slug(text: str, limit: int = 60) -> str:
    """Cell text -> a Markdown-table-safe single line."""
    flat = re.sub(r"\s+", " ", text or "").strip()
    return flat.replace("|", "\\|")[:limit]


def table_to_markdown(table) -> str:
    rows = table.extract()
    if not rows:
        return ""
    width = max(len(r) for r in rows)
    out = []
    header = [slug(c) for c in rows[0]] + [""] * (width - len(rows[0]))
    out.append("| " + " | ".join(header) + " |")
    out.append("| " + " | ".join(["---"] * width) + " |")
    for r in rows[1:]:
        cells = [slug(c) for c in r] + [""] * (width - len(r))
        out.append("| " + " | ".join(cells) + " |")
    return "\n".join(out)


def inside(rect, x, y) -> bool:
    x0, y0, x1, y1 = rect
    return x0 <= x <= x1 and y0 <= y <= y1


def extract_page(doc, page, page_no: int, out_media: str, min_px: int):
    """Return (markdown_body, media_names, table_count, image_count)."""
    text_blocks = []
    for b in page.get_text("blocks", sort=True):
        # (x0, y0, x1, y1, text, block_no, block_type); type 1 = image block.
        if len(b) >= 7 and b[6] != 0:
            continue
        x0, y0, x1, y1, text = b[0], b[1], b[2], b[3], b[4]
        body = (text or "").strip()
        if body:
            text_blocks.append({"rect": (x0, y0, x1, y1), "text": body})

    tables = []
    table_rects = []
    try:
        found = page.find_tables()
        for t in found.tables:
            md = table_to_markdown(t)
            if md:
                tables.append({"rect": tuple(t.bbox), "md": md})
                table_rects.append(tuple(t.bbox))
    except Exception as exc:  # table finding must never kill the whole parse
        sys.stderr.write(f"table finder failed on page {page_no}: {exc}\n")

    # Drop text blocks swallowed by a table (they are rendered as the table).
    kept = []
    for blk in text_blocks:
        x0, y0, x1, y1 = blk["rect"]
        mx, my = (x0 + x1) / 2.0, (y0 + y1) / 2.0
        if any(inside(r, mx, my) for r in table_rects):
            continue
        kept.append(blk)

    # Merge tables into the reading order by their top edge.
    flow = [{"y": b["rect"][1], "kind": "text", "blk": b} for b in kept]
    flow += [{"y": t["rect"][1], "kind": "table", "blk": t} for t in tables]
    flow.sort(key=lambda item: (round(item["y"], 1), item["kind"]))

    parts = []
    for item in flow:
        if item["kind"] == "text":
            parts.append(item["blk"]["text"])
        else:
            parts.append(item["blk"]["md"])

    media_names = []
    images = []
    try:
        images = page.get_images(full=True)
    except Exception as exc:
        sys.stderr.write(f"image listing failed on page {page_no}: {exc}\n")

    seen = set()
    for xref, *_ in [(im[0], im) for im in images]:
        if xref in seen:
            continue
        seen.add(xref)
        try:
            info = doc.extract_image(xref)
        except Exception as exc:
            sys.stderr.write(f"image {xref} on page {page_no} not extractable: {exc}\n")
            continue
        w = int(info.get("width") or 0)
        h = int(info.get("height") or 0)
        if w < min_px or h < min_px:
            continue  # decoration (rules, bullets, logos)
        ext = (info.get("ext") or "png").lower()
        if not re.fullmatch(r"[a-z0-9]{1,5}", ext):
            ext = "png"
        name = f"p{page_no:04d}-{len(media_names)}.{ext}"
        with open(os.path.join(out_media, name), "wb") as fh:
            fh.write(info["image"])
        media_names.append(name)
        parts.append(f"![page {page_no} image {len(media_names)}](../media/{name})")

    body = "\n\n".join(parts).strip()
    return body, media_names, len(tables), len(media_names)


def main() -> int:
    ap = argparse.ArgumentParser(description="Parse a PDF into per-page Markdown (PDF note/ask).")
    ap.add_argument("--input", required=True, help="absolute path of the PDF to parse")
    ap.add_argument("--out", required=True, help="absolute path of the cache entry directory")
    ap.add_argument("--name", default="", help="display name recorded in the manifest")
    ap.add_argument("--min-image-px", type=int, default=32, help="skip images smaller than this on either side")
    args = ap.parse_args()

    src = os.path.abspath(args.input)
    out = os.path.abspath(args.out)

    if not os.path.isfile(src):
        fail("PDF_NOT_FOUND", f"no such file: {src}")
    if os.path.exists(out) and os.listdir(out):
        fail("OUT_NOT_EMPTY", f"output directory already has content: {out}")

    try:
        import fitz  # PyMuPDF
    except Exception as exc:
        fail("PARSE_FAILED", f"PyMuPDF (fitz) is not importable: {exc}")

    digest = sha256_file(src)

    os.makedirs(os.path.join(out, "pages"), exist_ok=True)
    os.makedirs(os.path.join(out, "media"), exist_ok=True)

    try:
        doc = fitz.open(src)
    except Exception as exc:
        fail("PDF_INVALID", f"cannot open as a PDF: {exc}")

    try:
        if doc.needs_pass:
            fail("PDF_ENCRYPTED", "the document is encrypted; extraction needs a password")
        page_count = doc.page_count
        if page_count <= 0:
            fail("PDF_EMPTY", "the document has no pages")

        page_names = []
        all_media = []
        tables_total = 0
        images_total = 0
        for i in range(page_count):
            page_no = i + 1
            try:
                page = doc.load_page(i)
            except Exception as exc:
                fail("PDF_INVALID", f"page {page_no} cannot be loaded: {exc}")
            try:
                body, media, tcount, icount = extract_page(doc, page, page_no, os.path.join(out, "media"), args.min_image_px)
            except Exception as exc:
                fail("PARSE_FAILED", f"page {page_no} extraction failed: {exc}")
            tables_total += tcount
            images_total += icount
            name = f"p{page_no:04d}.md"
            with open(os.path.join(out, "pages", name), "w", encoding="utf-8") as fh:
                fh.write(f"# Page {page_no}\n\n")
                fh.write(body)
                fh.write("\n")
            page_names.append(name)
            all_media.extend(f"media/{m}" for m in media)
    finally:
        try:
            doc.close()
        except Exception:
            pass

    try:
        pymupdf_version = getattr(fitz, "__version__", None) or getattr(fitz, "VersionBind", "unknown")
    except Exception:
        pymupdf_version = "unknown"

    meta = {
        "schema": SCHEMA,
        "source_sha256": digest,
        "source_name": args.name or os.path.basename(src),
        "page_count": page_count,
        "page_names": page_names,
        "parser": {"name": PARSER_NAME, "version": str(pymupdf_version)},
        "markdown_version": MARKDOWN_VERSION,
        "generated_at_ms": int(time.time() * 1000),
        "media": all_media,
        "table_count": tables_total,
        "image_count": images_total,
    }
    tmp_meta = os.path.join(out, "meta.json.tmp")
    with open(tmp_meta, "w", encoding="utf-8") as fh:
        json.dump(meta, fh, ensure_ascii=False, indent=2, sort_keys=True)
        fh.write("\n")
    os.replace(tmp_meta, os.path.join(out, "meta.json"))
    sys.stdout.write(json.dumps({"ok": True, "source_sha256": digest, "page_count": page_count}) + "\n")
    return EXIT_OK


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except FileNotFoundError as exc:  # staging dir removed concurrently
        fail("PARSE_FAILED", f"file disappeared during parse: {exc}")
    except Exception as exc:  # never a bare traceback: the caller needs a code
        fail("PARSE_FAILED", f"{type(exc).__name__}: {exc}")
