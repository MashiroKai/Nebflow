#!/usr/bin/env python
"""crop_paper_figure.py — pymupdf 区域高清裁图（两遍法）
visual-report-suite / academic-research 场景工具。2026-10-04 于 viz-venv 实测通过。

用法:
  第一遍(整页定界): python crop_paper_figure.py <pdf> <页码1基> <zoom> [整页输出.png]
  第二遍(区域裁切): python crop_paper_figure.py <pdf> <页码1基> <zoom> <成片.png> x0,y0,x1,y1

坐标: clip 用 PDF 点坐标(pt); 渲染像素 = pt * zoom, 定界时把像素边界 ÷ zoom 换算回 pt。
成片红线: 宽 >=1500 px (不够就加大 zoom 重裁); zoom 不得低于 2。
"""
import sys
import pymupdf as fitz  # PyMuPDF (viz-venv 实测 1.28.2)

pdf, page_no, zoom = sys.argv[1], int(sys.argv[2]), float(sys.argv[3])
doc = fitz.open(pdf)
page = doc[page_no - 1]
print(f"page {page_no}/{doc.page_count}: {page.rect.width:.1f} x {page.rect.height:.1f} pt")

if len(sys.argv) <= 5:  # 第一遍: 整页渲染, 供 Read 定界
    out = sys.argv[4] if len(sys.argv) == 5 else "page_full.png"
    pix = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom))
else:                   # 第二遍: 区域裁切
    out = sys.argv[4]
    x0, y0, x1, y1 = map(float, sys.argv[5].split(","))
    pix = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom), clip=fitz.Rect(x0, y0, x1, y1))

pix.save(out)
print(f"wrote {out}: {pix.width}x{pix.height} px "
      f"{'[width OK >=1500]' if pix.width >= 1500 else '[width <1500 - raise zoom]'}")
