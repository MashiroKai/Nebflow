package nebflow.core

import cats.effect.IO
import io.circe.JsonObject
import nebflow.core.tools.BashTool

// ============================================================
// Safety modes — three trust levels controlled by the user
// ============================================================
//
// Mode 1: confirm-edits  (safest; 2026-09-12 起不再是启动默认)
//   Write, Edit, Bash  → need confirmation
//   Everything else    → auto-approved
//
// Mode 2: auto-edits
//   Bash               → needs confirmation
//   Write, Edit        → auto-approved
//   Everything else    → auto-approved
//
// Mode 3: auto-all  (启动默认 since 2026-09-12 — 作者令：启动即顶档)
//   Everything         → auto-approved (no confirmation ever)
//
// New/unknown tools (ScriptTool, MCP, etc.) default to auto-approved
// in all modes — they are trusted because the user chose to install them.

enum SafetyMode:
  case ConfirmEdits, AutoEdits, AutoAll

object SafetyMode:

  def fromString(s: String): SafetyMode = s match
    case "auto-edits" => AutoEdits
    case "auto-all" => AutoAll
    case _ => ConfirmEdits

  def toString(m: SafetyMode): String = m match
    case ConfirmEdits => "confirm-edits"
    case AutoEdits => "auto-edits"
    case AutoAll => "auto-all"

  /**
   * wire 契约的**三档显式值域** —— 写入口（WS `setSafetyMode`、REST
   * `PUT /api/safety/mode`）的白名单。写入口**不得**用 `fromString` 的静默兜底
   * （`case _ => ConfirmEdits`）处理用户输入：那会把拼错/缺失的值悄悄变成最严档。
   * 该兜底只保留给**配置文件**的"写了值却没写对"路径（见 `GlobalSafety` 三分支）。
   */
  val wireValues: Set[String] = Set("confirm-edits", "auto-edits", "auto-all")

  /** 严格解析 wire 值：非三档显式值 ⇒ None（调用方回 400 / error 帧）。 */
  def fromWire(s: String): Option[SafetyMode] = s match
    case "confirm-edits" => Some(ConfirmEdits)
    case "auto-edits" => Some(AutoEdits)
    case "auto-all" => Some(AutoAll)
    case _ => None

  given io.circe.Encoder[SafetyMode] =
    io.circe.Encoder.encodeString.contramap(toString)

  given io.circe.Decoder[SafetyMode] =
    io.circe.Decoder.decodeString.map(fromString)

end SafetyMode

// ============================================================
// 递进式放行链 (2026-08-30): permission-card answers may carry an
// optional `upgradeMode` — "allow this one AND switch the session to
// <mode>". Escalation ladder: ConfirmEdits → AutoEdits → AutoAll.
//
// Wire contract (permissionAnswer):
//   { approved: true, upgradeMode?: "auto-edits" | "auto-all" }
//   — old replies without upgradeMode are byte-compatible (no upgrade).
//   — a deny with upgradeMode is invalid: the upgrade is IGNORED (warn)
//     and the deny proceeds unchanged (rule #12 spirit: never transform
//     a reply into something the user did not click).
//   — "confirm-edits" / unknown values are not escalation targets and
//     are rejected here (route layer logs and proceeds as plain allow).
// ============================================================
object PermissionUpgrade:

  /** Parse an escalation request off a permission-card reply. */
  def parse(approved: Boolean, upgradeMode: Option[String]): Either[String, Option[SafetyMode]] =
    upgradeMode match
      case None => Right(None)
      case Some("auto-edits") if approved => Right(Some(SafetyMode.AutoEdits))
      case Some("auto-all") if approved => Right(Some(SafetyMode.AutoAll))
      case Some(raw) if !approved =>
        Left(s"upgradeMode '$raw' requires approved=true — deny replies must not carry an upgrade")
      case Some(other) => Left(s"unknown upgradeMode '$other' — valid targets: auto-edits, auto-all")

end PermissionUpgrade

// ============================================================
// F1 (#433): global safety mode —— **唯一持久权威源 = 应用的权限模式**
//
// 语义（2026-09-13 作者重裁「候选 B」；permshield S1 后端切片）：
// 本对象读出的值是**整个应用**的权限模式，也是**档位的唯一来源**。
// 2026-09-12 引入的「会话内临时覆盖」（仅内存、按 rootSessionId 存放于
// `SharedResources.permissionPolicies`）层**已整体停用并删除**：
//
//     有效档位 = 本对象读出的全局值        （全仓唯一一条路径）
//
// ⇒ 顶栏盾牌（WS `setSafetyMode`）与确认卡递进升级（`applyPermissionUpgrade`）
//    写入的就是**本键**（`nebflow.json` → `safety.defaultMode`，落盘）——
//    与 REST `PUT /api/safety/mode` 同一条持久路径，故**重启后仍生效**；
// ⇒ 档位是**应用级**的：换会话 / 新会话 / 重启都读同一个值，不再有"本会话
//    临时收紧/放宽"这种第二介质（作者：「不需要两个位置可调」）；
// ⇒ `SessionMeta.safetyMode`（`sessions/_index.json` 的逐会话键）是**非权威
//    遗留字段**：存量字节一字不动（读时忽略），其值取什么都不影响有效档位
//    （由 `SafetyModeGlobalOnlySpec` / `SessionStoreRecoverAuthoritySpec` 承重钉住）。
//
// The user's auto-all was only ever a single session's meta field — the
// system had no global channel, and ~/.nebflow/permission_policy.json was
// a dead file no code read (incident 2026-08-26). This object wires the
// global mode from nebflow.json:
//
//   { "safety": { "defaultMode": "auto-all" } }
//
// Hot-read per access (config path resolves per call, same pattern as
// PresetStore) — flipping the value takes effect on the next decision /
// seeding without a restart.
//
// 启动默认 = 全部放行 (2026-09-12 作者令：「把 nebflow 启动时的信任模式默认
// 开全部放行」)：**读不到有效值**（缺文件 / 缺键 / 文件不可解析 / 读盘失败）
// ⇒ AutoAll —— 未配置时用的是「启动默认档」（顶档），不再回退到最严档。
// 取值分三档：
//   · 可解析且可识别（auto-all / auto-edits / confirm-edits）→ 该档（配置优先）
//   · 可解析但不可识别（如 "yolo" 拼错）→ ConfirmEdits（保守：写了值却没写对，
//     不放大权限，与 `SafetyMode.fromString` 的既有兜底同源）
//   · 读不到（None）→ AutoAll（启动默认）
//   · 键存在但类型不符（`null` / 数字 / 对象）→ `.as[String]` 失败 ⇒ 归入
//     「读不到」⇒ AutoAll（与缺键同路）；注意 `ConfigService.mergeConfig` 把
//     `null` 当**删键**语义 ⇒ 前端"清空"动作会落成"删键 = 回顶档"。UI 只写三档
//     显式值，不暴露"未设置"态（设计 §2.3 / §6.4）。
// ============================================================
object GlobalSafety:

  /** Read `safety.defaultMode` from nebflow.json; AutoAll 当未配置（见上）。 */
  def defaultMode: IO[SafetyMode] =
    IO.blocking {
      val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
      if !os.exists(configPath) then None
      else
        io.circe.parser
          .parse(os.read(configPath))
          .toOption
          .flatMap(_.hcursor.downField("safety").downField("defaultMode").as[String].toOption)
    }.handleErrorWith(_ => IO.pure(None))
      .map(_.fold(SafetyMode.AutoAll)(SafetyMode.fromString))

end GlobalSafety

// ============================================================
// 2026-09-13（permshield S1）：此前的 `SafetyModeAuthority`（唯一合并点
// `有效档位 = 覆盖 ?? 全局`）**已删除** —— 覆盖层停用后合并规则不再存在，
// 「有效档位 = GlobalSafety.defaultMode」就是唯一定义，无需第二个 object 转述。
// 原来经它解析的消费点一律改走 `SharedResources.effectiveSafetyMode`（单一入口）。
// ============================================================

// ============================================================
// Reversibility check — drives the confirm/auto-approve decision
// ============================================================

object ToolReversibility:

  // Tools that always need confirmation regardless of mode
  // (except auto-all which overrides everything)
  private val EditTools = Set("Write", "Edit")

  // Bash is special — same tool can be safe or dangerous depending on command
  private val SafeHttpMethods = Set("GET", "HEAD", "OPTIONS")

  def isReversible(toolName: String, input: JsonObject, mode: SafetyMode): Boolean =
    mode match
      case SafetyMode.AutoAll =>
        true

      case SafetyMode.AutoEdits =>
        // Write/Edit auto-approved, Bash still checked
        if EditTools.contains(toolName) then true
        else if toolName == "Bash" then checkBash(input)
        else if toolName == "Curl" then checkCurl(input)
        else true // all other tools (Read, Grep, MCP, ScriptTool, etc.) auto-approved

      case SafetyMode.ConfirmEdits =>
        // Write/Edit need confirmation, Bash checked, others auto-approved
        if EditTools.contains(toolName) then false
        else if toolName == "Bash" then checkBash(input)
        else if toolName == "Curl" then checkCurl(input)
        else true // all other tools auto-approved

  private def checkBash(input: JsonObject): Boolean =
    input("command").flatMap(_.asString).forall { cmd =>
      !BashTool.isDangerous(cmd) && BashTool.checkInjection(cmd).isEmpty
    }

  private def checkCurl(input: JsonObject): Boolean =
    input("method").flatMap(_.asString).forall(m => SafeHttpMethods.contains(m.toUpperCase))

end ToolReversibility
