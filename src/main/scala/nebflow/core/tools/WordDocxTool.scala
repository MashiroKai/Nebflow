package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.shared.TerminalUtils.{escapeShellArg, escapePythonStr}

object WordDocxTool extends Tool:
  val DEFAULT_TIMEOUT = 30_000

  val name = "WordDocx"

  val description = """Full-featured Microsoft Word .docx tool powered by python-docx.
Operations:
- "read": Extract all text — paragraphs and table cells — from a .docx file.
- "info": Get document metadata (title, author, created/modified), paragraph count, table count, section count, and page dimensions.
- "create": Create a new .docx. Use "text" for paragraphs (\n separated). Use "table_data" for tables (array of rows, each row an array of cell strings). Use "style" for paragraph style (e.g. "Heading 1", "Normal").
- "append": Append paragraphs and/or tables to an existing .docx.
- "replace": Find and replace text in all paragraphs (exact match, first occurrence per paragraph).
- "add-table": Insert a table into an existing .docx. Provide "table_data" as array of rows."""

  val inputSchema = JsonObject.fromIterable(List(
    "type" -> "object".asJson,
    "properties" -> io.circe.Json.obj(
      "operation" -> io.circe.Json.obj("type" -> "string".asJson, "enum" -> io.circe.Json.arr("read".asJson, "info".asJson, "create".asJson, "append".asJson, "replace".asJson, "add-table".asJson), "description" -> "Operation to perform".asJson),
      "file_path" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Absolute or relative path to the .docx file".asJson),
      "text" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Paragraph text, newline-separated. Used by \"create\", \"append\".".asJson),
      "table_data" -> io.circe.Json.obj("type" -> "array".asJson, "items" -> io.circe.Json.obj("type" -> "array".asJson, "items" -> io.circe.Json.obj("type" -> "string".asJson)), "description" -> "Table data as array of rows (each row is array of cell strings).".asJson),
      "search_text" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Text to search for. Used by \"replace\".".asJson),
      "replace_text" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Replacement text. Used by \"replace\".".asJson),
      "style" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Paragraph style name, e.g. \"Heading 1\", \"Normal\". Default: \"Normal\".".asJson)
    ),
    "required" -> io.circe.Json.arr("operation".asJson, "file_path".asJson)
  ))

  def summarize(input: JsonObject): String =
    val op = input("operation").flatMap(_.asString).getOrElse("")
    val path = input("file_path").flatMap(_.asString).getOrElse("")
    s"WordDocx($op: $path)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.startsWith("Error") then "Error"
    else
      val op = input("operation").flatMap(_.asString).getOrElse("")
      op match
        case "info" => "Document info extracted"
        case "create" => "Document created"
        case "append" => "Content appended"
        case "replace" => "Text replaced"
        case "add-table" => "Table added"
        case _ => s"${result.split("\n").filter(_.trim.nonEmpty).length} lines extracted"

  private def esc(s: String): String = escapePythonStr(s)

  private def buildScript(input: JsonObject): String =
    val op = input("operation").flatMap(_.asString).getOrElse("")
    val fp = esc(input("file_path").flatMap(_.asString).getOrElse(""))
    val text = input("text").flatMap(_.asString).getOrElse("").replace("'", "\\'").replace("\n", "\\n")
    val style = esc(input("style").flatMap(_.asString).getOrElse("Normal"))
    val search = input("search_text").flatMap(_.asString).getOrElse("").replace("'", "\\'")
    val replace = input("replace_text").flatMap(_.asString).getOrElse("").replace("'", "\\'")

    val tableData = input("table_data").flatMap(_.asArray) match
      case Some(arr) =>
        val rows = arr.map { row =>
          row.asArray.map(_.map(_.asString.getOrElse("")).mkString("[", ", ", "]")).getOrElse("[]")
        }.mkString("[", ", ", "]")
        rows
      case None => "[]"

    op match
      case "read" => s"""
import sys
try:
    from docx import Document
except ImportError:
    print("Error: python-docx not installed. Run: pip install python-docx"); sys.exit(1)

doc = Document('$fp')
print(f"=== Paragraphs ({len(doc.paragraphs)}) ===")
for i, p in enumerate(doc.paragraphs):
    t = p.text.strip()
    if t:
        print(f"[{i+1}] {t}")
print(f"\\n=== Tables ({len(doc.tables)}) ===")
for ti, table in enumerate(doc.tables):
    print(f"--- Table {ti+1} ---")
    for ri, row in enumerate(table.rows):
        cells = [cell.text for cell in row.cells]
        print(f"  Row {ri+1}: " + " | ".join(cells))
"""
      case "info" => s"""
import sys
try:
    from docx import Document
except ImportError:
    print("Error: python-docx not installed. Run: pip install python-docx"); sys.exit(1)

doc = Document('$fp')
props = doc.core_properties
print(f"Title: {props.title or '(none)'}")
print(f"Author: {props.author or '(none)'}")
print(f"Subject: {props.subject or '(none)'}")
print(f"Created: {props.created or '(none)'}")
print(f"Modified: {props.modified or '(none)'}")
print(f"Paragraphs: {len(doc.paragraphs)}")
print(f"Tables: {len(doc.tables)}")
print(f"Sections: {len(doc.sections)}")
for i, s in enumerate(doc.sections):
    w = s.page_width
    h = s.page_height
    print(f"  Section {i+1}: {w/914400:.1f}cm x {h/914400:.1f}cm")
"""
      case "create" => s"""
import sys, json
try:
    from docx import Document
except ImportError:
    print("Error: python-docx not installed. Run: pip install python-docx"); sys.exit(1)

doc = Document()
text = '$text'
for para in text.split('\\n'):
    if para.strip():
        doc.add_paragraph(para, style='$style')
table_data = $tableData
for row_data in table_data:
    if row_data:
        table = doc.add_table(rows=1, cols=len(row_data))
        hdr = table.rows[0].cells
        for ci, val in enumerate(row_data):
            hdr[ci].text = str(val)
        for cell in table.rows[0].cells:
            for p in cell.paragraphs:
                if p.runs:
                    p.runs[0].bold = True
doc.save('$fp')
print(f"Created: $fp")
"""
      case "append" => s"""
import sys, json
try:
    from docx import Document
except ImportError:
    print("Error: python-docx not installed. Run: pip install python-docx"); sys.exit(1)

doc = Document('$fp')
text = '$text'
for para in text.split('\\n'):
    if para.strip():
        doc.add_paragraph(para, style='$style')
table_data = $tableData
for row_data in table_data:
    if row_data:
        table = doc.add_table(rows=1, cols=len(row_data))
        for ci, val in enumerate(row_data):
            table.rows[0].cells[ci].text = str(val)
doc.save('$fp')
print(f"Appended to: $fp")
"""
      case "replace" => s"""
import sys
try:
    from docx import Document
except ImportError:
    print("Error: python-docx not installed. Run: pip install python-docx"); sys.exit(1)

doc = Document('$fp')
count = 0
for p in doc.paragraphs:
    if '$search' in p.text:
        p.text = p.text.replace('$search', '$replace', 1)
        count += 1
for table in doc.tables:
    for row in table.rows:
        for cell in row.cells:
            if '$search' in cell.text:
                cell.text = cell.text.replace('$search', '$replace', 1)
                count += 1
doc.save('$fp')
print(f"Replaced {count} occurrence(s) of '$search'")
"""
      case "add-table" => s"""
import sys, json
try:
    from docx import Document
except ImportError:
    print("Error: python-docx not installed. Run: pip install python-docx"); sys.exit(1)

doc = Document('$fp')
table_data = $tableData
if table_data and len(table_data) > 0:
    cols = max(len(r) for r in table_data)
    table = doc.add_table(rows=len(table_data), cols=cols)
    for ri, row_data in enumerate(table_data):
        for ci, val in enumerate(row_data):
            table.rows[ri].cells[ci].text = str(val)
doc.save('$fp')
print(f"Added 1 table ({len(table_data)} rows) to: $fp")
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
