package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.shared.TerminalUtils.{escapeShellArg, escapePythonStr}

object PdfTool extends Tool:
  val DEFAULT_TIMEOUT = 30_000

  val name = "Pdf"

  val description = """Full-featured PDF tool powered by pypdf, pdfplumber, and reportlab.
Operations:
- "read": Extract text from a PDF. Use "pages" for page range (e.g. "1-5", "3").
- "tables": Extract tables from a PDF as tab-separated rows (requires pdfplumber).
- "merge": Merge multiple PDFs. Provide "file_paths" array and "output_path".
- "split": Split a PDF into single-page files. Use "output_path" as output directory.
- "info": Get PDF metadata (title, author, subject, creator, pages, page size, encrypted).
- "rotate": Rotate pages by angle (90, 180, 270). Use "pages" for specific pages or omit for all.
- "encrypt": Add password protection. Provide "password" and "output_path".
- "watermark": Add text watermark to all pages. Provide "text" and "output_path" (uses reportlab).
- "create": Create a simple PDF with "text" content (uses reportlab)."""

  val inputSchema = JsonObject.fromIterable(List(
    "type" -> "object".asJson,
    "properties" -> io.circe.Json.obj(
      "operation" -> io.circe.Json.obj("type" -> "string".asJson, "enum" -> io.circe.Json.arr("read".asJson, "tables".asJson, "merge".asJson, "split".asJson, "info".asJson, "rotate".asJson, "encrypt".asJson, "watermark".asJson, "create".asJson), "description" -> "Operation to perform".asJson),
      "file_path" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Primary PDF file path".asJson),
      "file_paths" -> io.circe.Json.obj("type" -> "array".asJson, "items" -> io.circe.Json.obj("type" -> "string".asJson), "description" -> "PDF file paths for \"merge\" operation".asJson),
      "pages" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Page range, e.g. \"1-5\" or \"3\". Defaults to all.".asJson),
      "output_path" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Output file or directory path".asJson),
      "angle" -> io.circe.Json.obj("type" -> "number".asJson, "description" -> "Rotation angle: 90, 180, or 270. Used by \"rotate\".".asJson),
      "password" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Password for \"encrypt\" operation.".asJson),
      "text" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Text content for \"create\" or \"watermark\".".asJson)
    ),
    "required" -> io.circe.Json.arr("operation".asJson, "file_path".asJson)
  ))

  def summarize(input: JsonObject): String =
    val op = input("operation").flatMap(_.asString).getOrElse("")
    val path = input("file_path").flatMap(_.asString).getOrElse("")
    s"Pdf($op: $path)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.startsWith("Error") then "Error"
    else
      val op = input("operation").flatMap(_.asString).getOrElse("")
      op match
        case "merge" => "PDFs merged"
        case "split" => "PDF split"
        case "info" => "PDF info extracted"
        case "rotate" => "Pages rotated"
        case "encrypt" => "PDF encrypted"
        case "watermark" => "Watermark added"
        case "create" => "PDF created"
        case "tables" =>
          val count = result.split("--- Table").length - 1
          if count > 0 then s"$count tables extracted" else "No tables extracted"
        case _ => s"${result.split("\n").filter(_.trim.nonEmpty).length} lines extracted"

  private def esc(s: String): String = escapePythonStr(s)

  private def parsePages(pages: Option[String]): Option[(Int, Option[Int])] =
    pages.flatMap { p =>
      "^(\\d+)(?:-(\\d+))?$".r.findFirstMatchIn(p).map { m =>
        (m.group(1).toInt - 1, Option(m.group(2)).map(_.toInt))
      }
    }

  private def buildScript(input: JsonObject): String =
    val op = input("operation").flatMap(_.asString).getOrElse("")
    val fp = esc(input("file_path").flatMap(_.asString).getOrElse(""))
    val outputPath = esc(input("output_path").flatMap(_.asString).getOrElse(""))
    val pages = input("pages").flatMap(_.asString)
    val angle = input("angle").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(90)
    val password = esc(input("password").flatMap(_.asString).getOrElse(""))
    val text = esc(input("text").flatMap(_.asString).getOrElse(""))

    val filePaths = input("file_paths").flatMap(_.asArray) match
      case Some(arr) => arr.flatMap(_.asString).map(p => s"'${esc(p)}'").mkString(", ")
      case None => ""

    val chFontSetup = """
import os
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
_chinese_font_paths = [
    ('/System/Library/Fonts/PingFang.ttc', 0),
    ('/System/Library/Fonts/Hiragino Sans GB.ttc', 0),
    ('/Library/Fonts/Arial Unicode.ttf', None),
    ('/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc', 0),
    ('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc', 0),
    ('/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc', 0),
    ('C:/Windows/Fonts/simsun.ttc', 0),
    ('C:/Windows/Fonts/msyh.ttc', 0),
    ('C:/Windows/Fonts/simhei.ttf', None),
]
_chinese_font = 'Helvetica'
for _path, _idx in _chinese_font_paths:
    if os.path.exists(_path):
        try:
            if _idx is not None:
                pdfmetrics.registerFont(TTFont('ChineseFont', _path, subfontIndex=_idx))
            else:
                pdfmetrics.registerFont(TTFont('ChineseFont', _path))
            _chinese_font = 'ChineseFont'
            break
        except:
            continue
"""

    op match
      case "read" =>
        val range = parsePages(pages)
        val rangeCode = range match
          case Some((start, Some(end))) => s"pages = reader.pages[$start:$end]"
          case Some((start, None)) => s"pages = [reader.pages[$start]]"
          case None => "pages = reader.pages"
        s"""
import sys
try:
    from pypdf import PdfReader
except ImportError:
    print("Error: pypdf not installed. Run: pip install pypdf"); sys.exit(1)
reader = PdfReader('$fp')
print(f"Total pages: {len(reader.pages)}")
$rangeCode
for i, page in enumerate(pages):
    idx = reader.pages.index(page) + 1
    text = page.extract_text()
    if text:
        print(f"--- Page {idx} ---")
        print(text)
"""
      case "tables" =>
        s"""
import sys
try:
    import pdfplumber
except ImportError:
    print("Error: pdfplumber not installed. Run: pip install pdfplumber"); sys.exit(1)
with pdfplumber.open('$fp') as pdf:
    for i, page in enumerate(pdf.pages):
        tables = page.extract_tables()
        for j, table in enumerate(tables):
            if not table: continue
            print(f"--- Table {j+1} on page {i+1} ---")
            for row in table:
                print("\\t".join(str(c or "") for c in row))
"""
      case "merge" =>
        s"""
import sys
try:
    from pypdf import PdfReader, PdfWriter
except ImportError:
    print("Error: pypdf not installed. Run: pip install pypdf"); sys.exit(1)
writer = PdfWriter()
paths = [$filePaths]
total = 0
for p in paths:
    r = PdfReader(p)
    for page in r.pages:
        writer.add_page(page)
        total += 1
with open('$outputPath', 'wb') as f:
    writer.write(f)
print(f"Merged {total} pages from {len(paths)} files -> $outputPath")
"""
      case "split" =>
        val outDir = if outputPath.nonEmpty then outputPath else "'.'"
        s"""
import sys, os
try:
    from pypdf import PdfReader, PdfWriter
except ImportError:
    print("Error: pypdf not installed. Run: pip install pypdf"); sys.exit(1)
reader = PdfReader('$fp')
outdir = $outDir
os.makedirs(outdir, exist_ok=True)
base = os.path.splitext(os.path.basename('$fp'))[0]
for i, page in enumerate(reader.pages):
    w = PdfWriter()
    w.add_page(page)
    out = os.path.join(outdir, f"{base}_page_{i+1}.pdf")
    with open(out, 'wb') as f:
        w.write(f)
    print(f"Saved: {out}")
print(f"Split {len(reader.pages)} pages")
"""
      case "info" =>
        s"""
import sys
try:
    from pypdf import PdfReader
except ImportError:
    print("Error: pypdf not installed. Run: pip install pypdf"); sys.exit(1)
reader = PdfReader('$fp')
meta = reader.metadata
def safe_meta(m, attr):
    try:
        val = getattr(m, attr, None)
        return val if val else "(none)"
    except:
        return "(none)"
print(f"Title: {safe_meta(meta, 'title')}")
print(f"Author: {safe_meta(meta, 'author')}")
print(f"Subject: {safe_meta(meta, 'subject')}")
print(f"Creator: {safe_meta(meta, 'creator')}")
print(f"Producer: {safe_meta(meta, 'producer')}")
print(f"Pages: {len(reader.pages)}")
if reader.pages:
    try:
        p = reader.pages[0]
        w = float(getattr(p.mediabox, 'width', 0))
        h = float(getattr(p.mediabox, 'height', 0))
        print(f"Page size: {w:.0f} x {h:.0f} pts")
    except:
        print("Page size: unknown")
print(f"Encrypted: {reader.is_encrypted}")
"""
      case "rotate" =>
        val range = parsePages(pages)
        val rangeCode = range match
          case Some((start, Some(end))) => s"indices = list(range($start, $end))"
          case Some((start, None)) => s"indices = list(range($start, $start + 1))"
          case None => "indices = list(range(len(reader.pages)))"
        s"""
import sys
try:
    from pypdf import PdfReader, PdfWriter
except ImportError:
    print("Error: pypdf not installed. Run: pip install pypdf"); sys.exit(1)
reader = PdfReader('$fp')
writer = PdfWriter()
$rangeCode
for i, page in enumerate(reader.pages):
    if i in indices:
        page.rotate($angle)
    writer.add_page(page)
with open('$outputPath', 'wb') as f:
    writer.write(f)
print(f"Rotated {len(indices)} page(s) by $angle degrees -> $outputPath")
"""
      case "encrypt" =>
        s"""
import sys
try:
    from pypdf import PdfReader, PdfWriter
except ImportError:
    print("Error: pypdf not installed. Run: pip install pypdf"); sys.exit(1)
reader = PdfReader('$fp')
writer = PdfWriter()
for page in reader.pages:
    writer.add_page(page)
writer.encrypt('$password', '$password')
with open('$outputPath', 'wb') as f:
    writer.write(f)
print(f"Encrypted -> $outputPath")
"""
      case "watermark" =>
        s"""
import sys, io
try:
    from pypdf import PdfReader, PdfWriter
    from reportlab.pdfgen import canvas
    from reportlab.lib.pagesizes import letter
except ImportError:
    print("Error: pypdf and/or reportlab not installed. Run: pip install pypdf reportlab"); sys.exit(1)
$chFontSetup
packet = io.BytesIO()
c = canvas.Canvas(packet, pagesize=letter)
c.setFont(_chinese_font, 40)
c.setFillColorRGB(0.5, 0.5, 0.5, alpha=0.3)
c.saveState()
c.translate(300, 400)
c.rotate(45)
c.drawCentredString(0, 0, '$text')
c.restoreState()
c.save()
packet.seek(0)
wm_reader = PdfReader(packet)
wm_page = wm_reader.pages[0]
reader = PdfReader('$fp')
writer = PdfWriter()
for page in reader.pages:
    page.merge_page(wm_page)
    writer.add_page(page)
with open('$outputPath', 'wb') as f:
    writer.write(f)
print(f"Watermarked -> $outputPath")
"""
      case "create" =>
        val out = if outputPath.nonEmpty then outputPath else fp
        s"""
import sys
try:
    from reportlab.pdfgen import canvas
    from reportlab.lib.pagesizes import letter
except ImportError:
    print("Error: reportlab not installed. Run: pip install reportlab"); sys.exit(1)
$chFontSetup
c = canvas.Canvas('$out', pagesize=letter)
width, height = letter
c.setFont(_chinese_font, 12)
c.drawString(72, height - 72, '$text')
c.save()
print(f"Created PDF -> $out")
"""
      case _ => "print('Error: unknown operation')"

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val shell = PersistentShell.get()
    val py = buildScript(input)
    shell.execute(s"python3 -c ${escapeShellArg(py)}", DEFAULT_TIMEOUT).map { output =>
      if output.nonEmpty then Right(output) else Right("[No output]")
    }.handleError { e =>
      Right(s"Error: ${e.getMessage}")
    }
