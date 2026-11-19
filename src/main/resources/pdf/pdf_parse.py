#!/usr/bin/env python3
"""pdf_parse.py — the open-source PDF parser leg of the PDF note/ask feature.

Scope: turn one PDF into a per-page Markdown corpus that an ask answer can read
on demand. This script is the ONLY place that opens a PDF; the gateway never
parses (opening a PDF in the viewer or writing a note must never pay this cost).

Output layout (the cache entry shape, one directory per document):

    <out>/meta.json                 the manifest (see MANIFEST below)
    <out>/index.json                the page index (term -> pages), see INDEX
    <out>/pages/p%04d.md            one Markdown file per page, 1-based
    <out>/media/p%04d-<index>.<ext> one file per non-decorative embedded image

Every image link inside `pages/pNNNN.md` is RELATIVE to the page file
(`../media/pNNNN-0.png`), so a page file plus the entry directory is relocatable
as a unit and the ask leg can cite `pages/pNNNN.md` without rewriting links.

INDEX (why the entry carries one): an ask must know WHICH pages mention a
question's terms before it opens any page. `index.json` is the inverted index
built here, once, while the document is open anyway:

    {"schema":1,"markdown_version":2,"page_count":3,"terms":{"r2c2":[2],...}}

Terms are lowercase alphanumeric runs of length >= 2, deduplicated per page — the
same tokenisation the ask applies to a question, so both sides compare in one
vocabulary. It holds TERMS, never page bodies: an ask reads the manifest and the
index, then opens only the pages it cites.

READING ORDER: pdfplumber's text lines in `(top, x0)` order — geometry, not
insertion order. That is the documented, reproducible order used for the Markdown
body; it is NOT a layout-analysis heuristic (no multi-column reflow model), which
is stated as an explicit non-goal in `src/main/scala/nebflow/core/pdf/module-doc.md`.

TABLES: pdfplumber's line-based table finder (`page.find_tables()`) yields the
detected tables as Markdown. Text lines whose midpoint falls inside a detected
table rectangle are DROPPED, so table content appears exactly once (as the
Markdown table) instead of twice.

IMAGES: pypdf's `page.images` gives one entry per embedded image with its own
data and extension; images smaller than --min-image-px on either side are treated
as decoration and skipped. One image is written once per page (repeated
placements on the same page share the file and are counted once).

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

Parser identity: pdfplumber (MIT) for text and tables plus pypdf (BSD-3) for the
document structure and embedded images — both permissive, no AGPL anywhere in
this path. Reported in meta.json as {"name":"pdfplumber","version":<version>} so
a cache entry can be invalidated when the parser changes. The pinned dependency
set is `src/main/resources/pdf/requirements.txt`.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import time

SCHEMA = 1
INDEX_SCHEMA = 1
PARSER_NAME = "pdfplumber"
# Bump when the Markdown shape changes: a cache entry carrying an older
# markdown_version is a miss (the gateway reparses instead of serving stale text).
# v2: the entry also carries index.json (see INDEX above).
MARKDOWN_VERSION = 2

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_PARSE = 3

TOKEN_RE = re.compile(r"[^\W_]+", re.UNICODE)


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


def index_terms(text: str) -> set:
    """Tokens exactly as the ask tokenises a question (lowercase, length >= 2)."""
    return {t for t in (m.group(0).lower() for m in TOKEN_RE.finditer(text or "")) if len(t) >= 2}


def slug(text: str, limit: int = 60) -> str:
    """Cell text -> a Markdown-table-safe single line."""
    flat = re.sub(r"\s+", " ", text or "").strip()
    return flat.replace("|", "\\|")[:limit]


def rows_to_markdown(rows) -> str:
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


def text_lines_of(page, page_no: int):
    """Lines with their geometry, in reading order (geometric, not insertion)."""
    try:
        lines = []
        for ln in page.extract_text_lines():
            text = (ln.get("text") or "").strip()
            if text:
                lines.append(
                    {
                        "top": float(ln.get("top") or 0.0),
                        "text": text,
                        "rect": (
                            float(ln.get("x0") or 0.0),
                            float(ln.get("top") or 0.0),
                            float(ln.get("x1") or 0.0),
                            float(ln.get("bottom") or 0.0),
                        ),
                    }
                )
        return lines
    except Exception as exc:  # a layout edge case must not lose the whole page
        sys.stderr.write(f"text line extraction failed on page {page_no}: {exc}\n")
        out = []
        for i, raw in enumerate((page.extract_text() or "").split("\n")):
            body = raw.strip()
            if body:
                out.append({"top": float(i), "text": body, "rect": (0.0, float(i), 1e6, float(i) + 1.0)})
        return out


def tables_of(page, page_no: int):
    """Detected tables as (rect, markdown), never fatal for the page."""
    found = []
    try:
        finder = page.find_tables()
        tables = list(getattr(finder, "tables", finder))
        for t in tables:
            md = rows_to_markdown(t.extract())
            if md:
                bbox = tuple(float(v) for v in t.bbox)
                found.append({"top": bbox[1], "rect": bbox, "md": md})
    except Exception as exc:
        sys.stderr.write(f"table finder failed on page {page_no}: {exc}\n")
    return found


def images_of(pdf_page, page_no: int, out_media: str, min_px: int, parts: list):
    """Write the page's non-decorative images; append their relative links."""
    media_names = []
    try:
        images = list(pdf_page.images)
    except Exception as exc:
        sys.stderr.write(f"image listing failed on page {page_no}: {exc}\n")
        return media_names

    seen = set()
    for im in images:
        name_attr = str(getattr(im, "name", "") or "image.png")
        ext = name_attr.rsplit(".", 1)[-1].lower() if "." in name_attr else "png"
        if not re.fullmatch(r"[a-z0-9]{1,5}", ext):
            ext = "png"
        try:
            data = im.data
        except Exception as exc:
            sys.stderr.write(f"image {name_attr} on page {page_no} not extractable: {exc}\n")
            continue
        if not data:
            continue
        w = h = 0
        try:
            size = im.image.size
            w, h = int(size[0]), int(size[1])
        except Exception:
            w = h = min_px  # unknown geometry: keep the image rather than guess
        if w < min_px or h < min_px:
            continue  # decoration (rules, bullets, logos)
        key = (name_attr, len(data))
        if key in seen:
            continue
        seen.add(key)
        fname = f"p{page_no:04d}-{len(media_names)}.{ext}"
        with open(os.path.join(out_media, fname), "wb") as fh:
            fh.write(data)
        media_names.append(fname)
        parts.append(f"![page {page_no} image {len(media_names)}](../media/{fname})")
    return media_names


def extract_page(pdf_page, pypdf_page, page_no: int, out_media: str, min_px: int):
    """Return (markdown_body, media_names, table_count, image_count)."""
    lines = text_lines_of(pdf_page, page_no)
    tables = tables_of(pdf_page, page_no)
    table_rects = [t["rect"] for t in tables]

    # Drop text lines swallowed by a table (they are rendered as the table).
    kept = []
    for ln in lines:
        x0, y0, x1, y1 = ln["rect"]
        mx, my = (x0 + x1) / 2.0, (y0 + y1) / 2.0
        if any(inside(r, mx, my) for r in table_rects):
            continue
        kept.append(ln)

    # Merge tables into the reading order by their top edge.
    flow = [{"top": ln["top"], "kind": "text", "text": ln["text"]} for ln in kept]
    flow += [{"top": t["top"], "kind": "table", "text": t["md"]} for t in tables]
    flow.sort(key=lambda item: (round(item["top"], 1), item["kind"]))

    parts = [item["text"] for item in flow]
    media_names = images_of(pypdf_page, page_no, out_media, min_px, parts)

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
        import pdfplumber  # MIT
    except Exception as exc:
        fail("PARSE_FAILED", f"pdfplumber is not importable: {exc}")
    try:
        from pypdf import PdfReader  # BSD-3
    except Exception as exc:
        fail("PARSE_FAILED", f"pypdf is not importable: {exc}")

    digest = sha256_file(src)

    # Structure first (page count / encryption) with pypdf, then content with
    # pdfplumber: the encryption verdict must be a readable code, not a traceback
    # from whichever library happens to touch the file first. An encrypted
    # document's page tree cannot even be counted before decryption, so the
    # encryption check comes first and answers for itself.
    try:
        reader = PdfReader(src)
    except Exception as exc:
        fail("PDF_INVALID", f"cannot open as a PDF: {exc}")

    if bool(getattr(reader, "is_encrypted", False)):
        fail("PDF_ENCRYPTED", "the document is encrypted; extraction needs a password")

    try:
        structure_pages = len(reader.pages)
    except Exception as exc:
        fail("PDF_INVALID", f"cannot read the page tree: {exc}")

    if structure_pages <= 0:
        fail("PDF_EMPTY", "the document has no pages")

    os.makedirs(os.path.join(out, "pages"), exist_ok=True)
    os.makedirs(os.path.join(out, "media"), exist_ok=True)

    try:
        pdf = pdfplumber.open(src)
    except Exception as exc:
        fail("PDF_INVALID", f"pdfplumber cannot open this document: {exc}")

    try:
        page_count = len(pdf.pages)
        if page_count <= 0:
            fail("PDF_EMPTY", "the document has no pages")

        page_names = []
        page_terms = []
        all_media = []
        tables_total = 0
        images_total = 0
        for i in range(page_count):
            page_no = i + 1
            try:
                pdf_page = pdf.pages[i]
                pypdf_page = reader.pages[i]
            except Exception as exc:
                fail("PDF_INVALID", f"page {page_no} cannot be loaded: {exc}")
            try:
                body, media, tcount, icount = extract_page(
                    pdf_page, pypdf_page, page_no, os.path.join(out, "media"), args.min_image_px
                )
            except Exception as exc:
                fail("PARSE_FAILED", f"page {page_no} extraction failed: {exc}")
            tables_total += tcount
            images_total += icount
            name = f"p{page_no:04d}.md"
            page_text = f"# Page {page_no}\n\n{body}\n"
            with open(os.path.join(out, "pages", name), "w", encoding="utf-8") as fh:
                fh.write(page_text)
            page_names.append(name)
            page_terms.append((page_no, index_terms(page_text)))
            all_media.extend(f"media/{m}" for m in media)
    finally:
        try:
            pdf.close()
        except Exception:
            pass
        try:
            reader.stream.close()
        except Exception:
            pass

    try:
        parser_version = str(getattr(pdfplumber, "__version__", "unknown"))
    except Exception:
        parser_version = "unknown"

    meta = {
        "schema": SCHEMA,
        "source_sha256": digest,
        "source_name": args.name or os.path.basename(src),
        "page_count": page_count,
        "page_names": page_names,
        "parser": {"name": PARSER_NAME, "version": parser_version},
        "markdown_version": MARKDOWN_VERSION,
        "generated_at_ms": int(time.time() * 1000),
        "media": all_media,
        "table_count": tables_total,
        "image_count": images_total,
    }

    # The inverted index: term -> ascending pages carrying it (see INDEX above).
    inverted = {}
    for page_no, terms in sorted(page_terms):
        for term in sorted(terms):
            inverted.setdefault(term, []).append(page_no)
    index = {
        "schema": INDEX_SCHEMA,
        "markdown_version": MARKDOWN_VERSION,
        "page_count": page_count,
        "terms": inverted,
    }

    for name, payload in (("meta.json", meta), ("index.json", index)):
        tmp = os.path.join(out, name + ".tmp")
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, ensure_ascii=False, indent=2, sort_keys=True)
            fh.write("\n")
        os.replace(tmp, os.path.join(out, name))

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
