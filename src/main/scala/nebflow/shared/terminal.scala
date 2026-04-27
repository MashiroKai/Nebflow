package nebflow.shared

/** ANSI 转义码和终端工具 */
object TerminalUtils:
  // ANSI 颜色码
  val Reset = "\u001b[0m"
  val Bold = "\u001b[1m"
  val Dim = "\u001b[2m"
  val Italic = "\u001b[3m"
  val Underline = "\u001b[4m"
  val Red = "\u001b[31m"
  val Green = "\u001b[32m"
  val Yellow = "\u001b[33m"
  val Blue = "\u001b[34m"
  val Magenta = "\u001b[35m"
  val Cyan = "\u001b[36m"
  val White = "\u001b[37m"
  val BrightRed = "\u001b[91m"
  val BrightGreen = "\u001b[92m"
  val BrightYellow = "\u001b[93m"
  val BrightBlue = "\u001b[94m"
  val BrightCyan = "\u001b[96m"
  val BgRed = "\u001b[41m"
  val BgGreen = "\u001b[42m"
  val BgYellow = "\u001b[43m"
  val BgBlue = "\u001b[44m"
  val BgMagenta = "\u001b[45m"
  val BgCyan = "\u001b[46m"
  val BgWhite = "\u001b[47m"

  // 光标控制
  def cursorUp(n: Int): String = s"\u001b[${n}A"
  def cursorDown(n: Int): String = s"\u001b[${n}B"
  def cursorForward(n: Int): String = s"\u001b[${n}C"
  def cursorBack(n: Int): String = s"\u001b[${n}D"
  def clearLine: String = "\u001b[2K"
  def clearScreen: String = "\u001b[2J\u001b[H"
  def saveCursor: String = "\u001b[s"
  def restoreCursor: String = "\u001b[u"
  def hideCursor: String = "\u001b[?25l"
  def showCursor: String = "\u001b[?25h"
  def alternateScreen: String = "\u001b[?1049h"
  def normalScreen: String = "\u001b[?1049l"

  /** Shell 参数转义 */
  def escapeShellArg(s: String): String =
    "'" + s.replace("'", "'\\''") + "'"

  /** Python 字符串字面量转义 */
  def escapePythonStr(s: String): String =
    s
      .replace("\\", "\\\\")
      .replace("'", "\\'")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
