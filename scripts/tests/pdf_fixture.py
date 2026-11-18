#!/usr/bin/env python3
"""Build small real PDFs for the PDF note/ask tests.

Usage:
    pdf_fixture.py <out-dir>

Writes (all real PDFs):

    simple.pdf    3 pages: text in reading order (page 1), a bordered 3x3 table
                  plus text (page 2), text plus one 60x40 raster image (page 3)
    empty.pdf     0 pages
    encrypted.pdf a password-protected document

The fixtures are generated on demand (never committed) so the repository holds
no opaque binary blob and the test exercises a file written by an independent
writer, not one this feature produced.

Writer: the PDFs are written byte by byte by this script — the file format is
handled with the standard library only (zlib for the image stream), so the
fixture path adds NO dependency of its own and, in particular, no AGPL writer.
Encryption (the one part that is not worth hand-rolling) is done with pypdf,
which is already a declared dependency of the parser leg and is BSD-3.
"""

from __future__ import annotations

import io
import os
import sys
import zlib

PAGE_W, PAGE_H = 595.0, 842.0  # A4 in points

# Fixed object numbers for the three objects every fixture needs up front; page
# and content objects are numbered dynamically after them.
CATALOG_NO, PAGES_NO, FONT_NO = 1, 2, 3


def _esc(text: str) -> str:
    return text.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")


class PdfBuilder:
    """Minimal PDF writer: objects in insertion order, one page tree, xref."""

    def __init__(self) -> None:
        self.objects: list[bytes] = []
        self.pages: list[int] = []  # object numbers of the page objects

    def add(self, body: bytes) -> int:
        self.objects.append(body)
        return len(self.objects)

    def add_stream(self, dict_body: str, data: bytes) -> int:
        return self.add((f"<<{dict_body}/Length {len(data)}>>\nstream\n").encode("latin-1") + data + b"\nendstream")

    def add_page(self, content: bytes, extra_xobjects: str = "") -> tuple[int, int]:
        content_no = self.add_stream("", content)
        page_no = self.add(
            (
                f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {PAGE_W:g} {PAGE_H:g}] "
                f"/Resources << /Font << /F1 {FONT_NO} 0 R >> {extra_xobjects} >> /Contents {content_no} 0 R >>"
            ).encode("latin-1")
        )
        self.pages.append(page_no)
        return page_no, content_no

    def build(self, root_no: int = 1) -> bytes:
        out = bytearray(b"%PDF-1.4\n")
        offsets = [0] * (len(self.objects) + 1)
        for i, body in enumerate(self.objects, start=1):
            offsets[i] = len(out)
            out += f"{i} 0 obj\n".encode("latin-1") + body + b"\nendobj\n"
        startxref = len(out)
        count = len(self.objects) + 1
        out += f"xref\n0 {count}\n".encode("latin-1")
        out += b"0000000000 65535 f \n"
        for i in range(1, count):
            out += f"{offsets[i]:010d} 00000 n \n".encode("latin-1")
        out += f"trailer\n<< /Size {count} /Root {root_no} 0 R >>\nstartxref\n{startxref}\n%%EOF\n".encode("latin-1")
        return bytes(out)


def _text(x: float, ty: float, body: str, size: int = 12) -> str:
    """One text run. `ty` is measured DOWN from the page top (reading order)."""
    return f"BT /F1 {size} Tf 1 0 0 1 {x:g} {PAGE_H - ty:g} Tm ({_esc(body)}) Tj ET\n"


def _h_line(x0: float, x1: float, ty: float) -> str:
    return f"{x0:g} {PAGE_H - ty:g} m {x1:g} {PAGE_H - ty:g} l S\n"


def _v_line(x: float, ty0: float, ty1: float) -> str:
    return f"{x:g} {PAGE_H - ty0:g} m {x:g} {PAGE_H - ty1:g} l S\n"


def _red_image_xobject() -> bytes:
    w, h = 60, 40
    raw = bytes([200, 30, 30]) * (w * h)
    return zlib.compress(raw)


def build_simple(path: str) -> None:
    b = PdfBuilder()
    b.add(b"<< /Type /Catalog /Pages 2 0 R >>")  # 1
    b.add(b"<< /Type /Pages /Kids [] /Count 0 >>")  # 2 (Kids filled at the end)
    b.add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")  # 3
    p1 = (
        # Deliberately written out of visual order: reading order must come from
        # geometry, not from insertion order.
        _text(72, 140, "Second paragraph of page one.")
        + _text(72, 100, "First paragraph of page one.")
    )
    b.add_page(p1.encode("latin-1"))  # 4 page, 5 content

    x0, ty0, x1, ty1 = 72.0, 140.0, 372.0, 220.0
    p2 = _text(72, 100, "Page two intro.")
    p2 += _h_line(x0, x1, ty0) + _h_line(x0, x1, ty0 + 20) + _h_line(x0, x1, ty0 + 40)
    p2 += _h_line(x0, x1, ty0 + 60) + _h_line(x0, x1, ty0 + 80)
    p2 += _v_line(x0, ty0, ty1) + _v_line(x0 + 100, ty0, ty1)
    p2 += _v_line(x0 + 200, ty0, ty1) + _v_line(x1, ty0, ty1)
    for r in range(3):
        for c in range(3):
            p2 += _text(x0 + c * 100 + 6, ty0 + r * 20 + 14, f"r{r + 1}c{c + 1}")
    p2 += _text(72, 260, "Page two outro after the table.")
    b.add_page(p2.encode("latin-1"))

    p3 = _text(72, 100, "Page three with an embedded image.")
    p3 += "q 60 0 0 40 72 622 cm /Im0 Do Q\n"
    img_no = b.add_stream("/Type /XObject /Subtype /Image /Width 60 /Height 40 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode ", _red_image_xobject())
    b.add_page(p3.encode("latin-1"), extra_xobjects=f"/XObject << /Im0 {img_no} 0 R >>")

    kids = " ".join(f"{n} 0 R" for n in b.pages)
    b.objects[PAGES_NO - 1] = f"<< /Type /Pages /Kids [{kids}] /Count {len(b.pages)} >>".encode("latin-1")
    with open(path, "wb") as fh:
        fh.write(b.build(root_no=CATALOG_NO))


# A page tree with zero kids is a valid document that every writer refuses to
# CREATE; it is written with the same byte-level builder, with a real xref, so it
# is a well-formed PDF that simply has no pages (a reader may not tolerate a
# structurally broken file here — "no pages" must be told apart from "corrupt").
def build_empty(path: str) -> None:
    b = PdfBuilder()
    b.add(b"<< /Type /Catalog /Pages 2 0 R >>")
    b.add(b"<< /Type /Pages /Kids [] /Count 0 >>")
    b.add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    with open(path, "wb") as fh:
        fh.write(b.build(root_no=CATALOG_NO))


def build_encrypted(path: str, simple_path: str) -> None:
    """Encrypt the simple fixture with pypdf (BSD-3; already a parser dependency)."""
    from pypdf import PdfReader, PdfWriter

    reader = PdfReader(simple_path)
    writer = PdfWriter()
    for page in reader.pages:
        writer.add_page(page)
    try:
        writer.encrypt(user_password="user", owner_password="owner", algorithm="AES-256")
    except Exception:
        writer.encrypt(user_password="user", owner_password="owner")
    buf = io.BytesIO()
    writer.write(buf)
    with open(path, "wb") as fh:
        fh.write(buf.getvalue())


def main() -> int:
    if len(sys.argv) != 2:
        sys.stderr.write("usage: pdf_fixture.py <out-dir>\n")
        return 2
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    simple = os.path.join(out, "simple.pdf")
    build_simple(simple)
    build_empty(os.path.join(out, "empty.pdf"))
    build_encrypted(os.path.join(out, "encrypted.pdf"), simple)
    return 0


if __name__ == "__main__":
    sys.exit(main())
