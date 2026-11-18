#!/usr/bin/env python3
"""Build small real PDFs for the PDF note/ask tests.

Usage:
    pdf_fixture.py <out-dir>

Writes (all real PDFs produced by a real PDF writer, PyMuPDF):

    simple.pdf    3 pages: text in reading order (page 1), a bordered 3x3 table
                  plus text (page 2), text plus one 60x40 raster image (page 3)
    empty.pdf     0 pages
    encrypted.pdf a password-protected document

The fixtures are generated on demand (never committed) so the repository holds
no opaque binary blob and the test exercises a file written by an independent
writer, not one this feature produced.
"""

from __future__ import annotations

import os
import sys

import fitz


def build_simple(path: str) -> None:
    doc = fitz.open()
    p1 = doc.new_page()
    # Two blocks, deliberately inserted out of visual order: reading order must
    # come from geometry, not from insertion order.
    p1.insert_text((72, 140), "Second paragraph of page one.")
    p1.insert_text((72, 100), "First paragraph of page one.")

    p2 = doc.new_page()
    p2.insert_text((72, 100), "Page two intro.")
    x0, y0, x1, y1 = 72, 140, 372, 220
    p2.draw_rect(fitz.Rect(x0, y0, x1, y1))
    for i in range(1, 4):
        p2.draw_line(fitz.Point(x0, y0 + i * 20), fitz.Point(x1, y0 + i * 20))
        p2.draw_line(fitz.Point(x0 + i * 100, y0), fitz.Point(x0 + i * 100, y1))
    for r in range(3):
        for c in range(3):
            p2.insert_text((x0 + c * 100 + 6, y0 + r * 20 + 14), f"r{r + 1}c{c + 1}")
    p2.insert_text((72, 260), "Page two outro after the table.")

    p3 = doc.new_page()
    p3.insert_text((72, 100), "Page three with an embedded image.")
    pix = fitz.Pixmap(fitz.csRGB, fitz.IRect(0, 0, 60, 40))
    pix.set_rect(pix.irect, (200, 30, 30))
    p3.insert_image(fitz.Rect(72, 140, 192, 220), pixmap=pix)

    doc.save(path)
    doc.close()


# A page tree with zero kids is a valid document that every writer refuses to
# CREATE; it is written literally so the empty-document path stays covered.
EMPTY_PDF = b"""%PDF-1.4
1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
2 0 obj<</Type/Pages/Kids[]/Count 0>>endobj
trailer<</Root 1 0 R/Size 3>>
%%EOF
"""


def build_empty(path: str) -> None:
    with open(path, "wb") as fh:
        fh.write(EMPTY_PDF)


def build_encrypted(path: str) -> None:
    doc = fitz.open()
    page = doc.new_page()
    page.insert_text((72, 100), "secret")
    doc.save(path, encryption=fitz.PDF_ENCRYPT_AES_256, owner_pw="owner", user_pw="user")
    doc.close()


def main() -> int:
    if len(sys.argv) != 2:
        sys.stderr.write("usage: pdf_fixture.py <out-dir>\n")
        return 2
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    build_simple(os.path.join(out, "simple.pdf"))
    build_empty(os.path.join(out, "empty.pdf"))
    build_encrypted(os.path.join(out, "encrypted.pdf"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
