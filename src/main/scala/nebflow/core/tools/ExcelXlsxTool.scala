package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.shared.TerminalUtils.{escapeShellArg, escapePythonStr}

object ExcelXlsxTool extends Tool:
  val DEFAULT_TIMEOUT = 30_000

  val name = "ExcelXlsx"

  val description = """Full-featured Microsoft Excel .xlsx tool powered by openpyxl.
Operations:
- "read": Read cell values from a sheet. Use "range" (e.g. "A1:D20") and "sheet_name". Defaults to used range. Shows formulas if data_only=False.
- "info": Get workbook info (sheet names, dimensions, defined names).
- "create": Create a new .xlsx with "data" as tab-separated rows (newline-separated). First row can be bold headers. Auto column width.
- "write": Write "data" (tab-separated rows) to a specific starting cell range in an existing sheet.
- "write-formula": Write an Excel formula to a specific "cell" (e.g. "A1") in an existing sheet.
- "add-sheet": Add a new sheet with "sheet_name" and optional "data".
- "set-style": Apply style to a "range" (e.g. "A1:C5"): bold, italic, font_color, font_size, fill_color, border, alignment."""

  val inputSchema = JsonObject.fromIterable(List(
    "type" -> "object".asJson,
    "properties" -> io.circe.Json.obj(
      "operation" -> io.circe.Json.obj("type" -> "string".asJson, "enum" -> io.circe.Json.arr("read".asJson, "info".asJson, "create".asJson, "write".asJson, "write-formula".asJson, "add-sheet".asJson, "set-style".asJson), "description" -> "Operation to perform".asJson),
      "file_path" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Absolute or relative path to the .xlsx file".asJson),
      "sheet_name" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Sheet name (defaults to active/first sheet)".asJson),
      "range" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Cell range, e.g. \"A1:D20\" for read/set-style, or \"A1\" for write start cell.".asJson),
      "cell" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Single cell address for \"write-formula\", e.g. \"A1\".".asJson),
      "data" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Tab-separated columns, newline-separated rows.".asJson),
      "headers" -> io.circe.Json.obj("type" -> "boolean".asJson, "description" -> "For \"create\": first row is header (bold). Default: true.".asJson),
      "formula" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Excel formula string for \"write-formula\", e.g. \"=SUM(A1:A10)\".".asJson),
      "style" -> io.circe.Json.obj(
        "type" -> "object".asJson,
        "description" -> "Style object for \"set-style\" operation.".asJson,
        "properties" -> io.circe.Json.obj(
          "bold" -> io.circe.Json.obj("type" -> "boolean".asJson),
          "italic" -> io.circe.Json.obj("type" -> "boolean".asJson),
          "font_color" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Hex color, e.g. FF0000".asJson),
          "font_size" -> io.circe.Json.obj("type" -> "number".asJson),
          "fill_color" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Hex color, e.g. FFFF00".asJson),
          "border" -> io.circe.Json.obj("type" -> "string".asJson, "enum" -> io.circe.Json.arr("thin".asJson, "medium".asJson, "thick".asJson, "none".asJson)),
          "horizontal_alignment" -> io.circe.Json.obj("type" -> "string".asJson, "enum" -> io.circe.Json.arr("left".asJson, "center".asJson, "right".asJson)),
          "vertical_alignment" -> io.circe.Json.obj("type" -> "string".asJson, "enum" -> io.circe.Json.arr("top".asJson, "center".asJson, "bottom".asJson))
        )
      )
    ),
    "required" -> io.circe.Json.arr("operation".asJson, "file_path".asJson)
  ))

  def summarize(input: JsonObject): String =
    val op = input("operation").flatMap(_.asString).getOrElse("")
    val path = input("file_path").flatMap(_.asString).getOrElse("")
    s"ExcelXlsx($op: $path)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.startsWith("Error") then "Error"
    else
      val op = input("operation").flatMap(_.asString).getOrElse("")
      op match
        case "info" => "Workbook info extracted"
        case "create" => "Workbook created"
        case "write" => "Data written"
        case "write-formula" => "Formula written"
        case "add-sheet" => "Sheet added"
        case "set-style" => "Style applied"
        case _ => s"${result.split("\\n").filter(_.trim.nonEmpty).length} cells read"

  private def esc(s: String): String = escapePythonStr(s)

  private def parseData(data: Option[String]): String =
    data match
      case Some(d) =>
        val rows = d.split("\\n").map(_.split("\\t").map(_.asJson).toList.asJson).toList.asJson
        rows.noSpaces
      case None => "[]"

  private def buildScript(input: JsonObject): String =
    val op = input("operation").flatMap(_.asString).getOrElse("")
    val fp = esc(input("file_path").flatMap(_.asString).getOrElse(""))
    val sn = input("sheet_name").flatMap(_.asString).map(s => s"'${esc(s)}'").getOrElse("None")
    val range = input("range").flatMap(_.asString).getOrElse("")
    val cell = esc(input("cell").flatMap(_.asString).getOrElse("A1"))
    val formula = esc(input("formula").flatMap(_.asString).getOrElse(""))
    val data = parseData(input("data").flatMap(_.asString))
    val headers = input("headers").flatMap(_.asBoolean).getOrElse(true)

    op match
      case "read" =>
        val rangeCode = if range.nonEmpty then s"rng = ws['${esc(range)}']" else "rng = ws.iter_rows(min_row=1, max_row=ws.max_row, max_col=ws.max_column)"
        s"""
import sys
try:
    import openpyxl
except ImportError:
    print("Error: openpyxl not installed. Run: pip install openpyxl"); sys.exit(1)
wb = openpyxl.load_workbook('$fp')
sn = $sn
ws = wb[sn] if sn else wb.active
print(f"Sheet: {ws.title}  Dimensions: {ws.dimensions}")
$rangeCode
for row in rng:
    vals = []
    for cell in row:
        v = cell.value
        if v is None: vals.append("")
        else: vals.append(str(v))
    print("\\t".join(vals))
wb.close()
"""
      case "info" =>
        s"""
import sys
try:
    import openpyxl
except ImportError:
    print("Error: openpyxl not installed. Run: pip install openpyxl"); sys.exit(1)
wb = openpyxl.load_workbook('$fp', data_only=True, read_only=True)
print(f"Sheets: {wb.sheetnames}")
try:
    defined_count = len(list(wb.defined_names))
except:
    try:
        defined_count = len(wb.defined_names.definedName)
    except:
        defined_count = 0
print(f"Defined names: {defined_count}")
for name in wb.sheetnames:
    ws = wb[name]
    try:
        dim = ws.calculate_dimension()
    except:
        dim = ws.dimensions if hasattr(ws, 'dimensions') else "unknown"
    print(f"  {name}: {dim}")
wb.close()
"""
      case "create" =>
        val boldHeaders = if headers then "True" else "False"
        s"""
import sys, json
try:
    import openpyxl
    from openpyxl.styles import Font, PatternFill, Border, Side, Alignment
except ImportError:
    print("Error: openpyxl not installed. Run: pip install openpyxl"); sys.exit(1)
wb = openpyxl.Workbook()
ws = wb.active
ws.title = "Sheet1"
rows = $data
bold = $boldHeaders
for r_idx, row in enumerate(rows, 1):
    for c_idx, val in enumerate(row, 1):
        cell = ws.cell(row=r_idx, column=c_idx, value=val)
        if bold and r_idx == 1:
            cell.font = Font(bold=True)
for col in ws.columns:
    max_len = max((len(str(c.value or "")) for c in col), default=0)
    ws.column_dimensions[col[0].column_letter].width = min(max_len + 2, 50)
wb.save('$fp')
print(f"Created: $fp ({len(rows)} rows x {max((len(r) for r in rows), default=0)} cols)")
"""
      case "write" =>
        val rangeStr = if range.nonEmpty then esc(range) else "A1"
        s"""
import sys, json, re
try:
    import openpyxl
except ImportError:
    print("Error: openpyxl not installed. Run: pip install openpyxl"); sys.exit(1)
wb = openpyxl.load_workbook('$fp')
sn = $sn
ws = wb[sn] if sn else wb.active
rows = $data
m = re.match(r'([A-Z]+)(\\d+)', '$rangeStr')
if m:
    start_col = openpyxl.utils.column_index_from_string(m.group(1))
    start_row = int(m.group(2))
else:
    start_col, start_row = 1, 1
for r_idx, row in enumerate(rows):
    for c_idx, val in enumerate(row):
        ws.cell(row=start_row + r_idx, column=start_col + c_idx, value=val)
wb.save('$fp')
print(f"Wrote {len(rows)} rows starting at $rangeStr")
"""
      case "write-formula" =>
        s"""
import sys
try:
    import openpyxl
except ImportError:
    print("Error: openpyxl not installed. Run: pip install openpyxl"); sys.exit(1)
wb = openpyxl.load_workbook('$fp')
sn = $sn
ws = wb[sn] if sn else wb.active
ws['$cell'] = '$formula'
wb.save('$fp')
print(f"Wrote formula '$formula' to $cell")
"""
      case "add-sheet" =>
        s"""
import sys, json
try:
    import openpyxl
except ImportError:
    print("Error: openpyxl not installed. Run: pip install openpyxl"); sys.exit(1)
wb = openpyxl.load_workbook('$fp')
ws = wb.create_sheet(title=$sn)
rows = $data
for r_idx, row in enumerate(rows, 1):
    for c_idx, val in enumerate(row, 1):
        ws.cell(row=r_idx, column=c_idx, value=val)
wb.save('$fp')
print(f"Added sheet: $sn")
"""
      case "set-style" =>
        val rangeStr = esc(range)
        val style = input("style").flatMap(_.asObject)
        val styleParts = scala.collection.mutable.ListBuffer.empty[String]
        val applyParts = scala.collection.mutable.ListBuffer.empty[String]

        style.foreach { s =>
          val bold = s("bold").flatMap(_.asBoolean)
          val italic = s("italic").flatMap(_.asBoolean)
          val fontColor = s("font_color").flatMap(_.asString)
          val fontSize = s("font_size").flatMap(_.asNumber).flatMap(_.toInt)
          val fillColor = s("fill_color").flatMap(_.asString)
          val border = s("border").flatMap(_.asString)
          val hAlign = s("horizontal_alignment").flatMap(_.asString)
          val vAlign = s("vertical_alignment").flatMap(_.asString)

          if bold.isDefined || italic.isDefined || fontColor.isDefined || fontSize.isDefined then
            val parts = scala.collection.mutable.ListBuffer.empty[String]
            bold.foreach(b => parts += s"bold=${if b then "True" else "False"}")
            italic.foreach(i => parts += s"italic=${if i then "True" else "False"}")
            fontColor.foreach(c => parts += s"color='${esc(c)}'")
            fontSize.foreach(sz => parts += s"size=$sz")
            styleParts += s"font = Font(${parts.mkString(", ")})"
            applyParts += "cell.font = font"

          fillColor.foreach(c =>
            styleParts += s"fill = PatternFill(start_color='${esc(c)}', end_color='${esc(c)}', fill_type='solid')"
            applyParts += "cell.fill = fill"
          )

          border.foreach(b => if b != "none" then
            styleParts += s"side = Side(style='${esc(b)}')"
            styleParts += "border = Border(left=side, right=side, top=side, bottom=side)"
            applyParts += "cell.border = border"
          )

          if hAlign.isDefined || vAlign.isDefined then
            val parts = scala.collection.mutable.ListBuffer.empty[String]
            hAlign.foreach(a => parts += s"horizontal='${esc(a)}'")
            vAlign.foreach(a => parts += s"vertical='${esc(a)}'")
            styleParts += s"alignment = Alignment(${parts.mkString(", ")})"
            applyParts += "cell.alignment = alignment"
        }

        s"""
import sys
try:
    import openpyxl
    from openpyxl.styles import Font, PatternFill, Border, Side, Alignment
except ImportError:
    print("Error: openpyxl not installed. Run: pip install openpyxl"); sys.exit(1)
wb = openpyxl.load_workbook('$fp')
sn = $sn
ws = wb[sn] if sn else wb.active
${styleParts.mkString("\n")}
for row in ws['$rangeStr']:
    for cell in row:
        ${applyParts.mkString("\n        ")}
wb.save('$fp')
print(f"Style applied to $rangeStr")
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
