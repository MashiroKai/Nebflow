package nebflow.core.tools

/**
 * 不透明 shell 命令的**风险类**（2026-11-18 审批门收窄批）。
 *
 * 判据 = **操作本身**（写 / 删 / 出网 / 凭据），而不是「命令文本里出现了哪个
 * 文件名」。文件名是**数据**，写操作才是**风险**；把前者当判据的旧规则既过宽
 * （只读引用被拦）又可绕（换个名字/动态拼名就漏）。
 *
 * 本模块**刻意不含任何文档类文件名字面量**（如 `*.md`）——`scripts/check-permission-gate.mjs`
 * 静态钉住这一点：审批触发判据不得基于文件名字面量。凭据位置名（`id_rsa`、
 * `.env` …）是**风险类**本身（任务点名「凭据读取」为合法判据），不属该禁止面。
 *
 * 已知不是 shell 解析器：引号内的字面文本、变量拼接、嵌套 `sh -c` 等仍是启发式，
 * 假阳/假阴都存在——见 `openspec/changes/permission-timeout-narrow-gate/proposal.md`。
 */
enum CommandRisk:
  case Write, Delete, NetworkEgress, CredentialAccess

object CommandRisk:

  // 首/尾边界同时钉住：`curling`、`ssh-keygen`、`rmdir` 的子串不误判。
  private val WriteVerbs =
    """(?s)(?:^|[\s;|&(/])(?:tee|truncate|dd|mv|cp|install|patch|chmod|chown|ln|mkdir|touch|rmdir)(?=$|[\s;|&)])""".r

  private val DeleteVerbs =
    """(?s)(?:^|[\s;|&(/])(?:rm|unlink|shred)(?=$|[\s;|&)])""".r

  private val NetworkVerbs =
    """(?s)(?:^|[\s;|&(|'"`])(?:ssh|scp|sftp|rsync|curl|wget|nc|ncat|telnet|Invoke-WebRequest|Invoke-RestMethod)(?=$|[\s;|&)])""".r

  private val EditorVerbs =
    """(?s)(?:^|[\s;|&(/])(?:vi|vim|nvim|nano|emacs|ed|ex)(?=$|[\s;|&)])""".r

  private val GitMutations =
    """(?s)(?:^|[\s;|&(])git\s+(?:add|commit|checkout|restore|reset|apply|rm|clean|switch|revert|merge|rebase|push|stash\s+(?:push|pop|drop|clear|apply|save))(?=$|[\s;|&)])""".r

  private val SedInPlace =
    """(?s)(?:^|[\s;|&(])sed\s+(?:-[^\s]*i[^\s]*|--in-place(?:=[^\s]*)?)(?=$|[\s;|&)])""".r
  // `> f` / `>>f` 是写；`->` `=>` 与 `2>&1` 不算；写 /dev/null 的噪音不计。
  private val Redirection = """(?s)(?<![=<-])>{1,2}\s*(?!&|/dev/null|$)[^\s;|&]""".r
  private val DeleteFlags = """(?s)(?:-delete\b|git\s+clean\s+-[a-zA-Z]*f)""".r

  private val CredentialPaths =
    """(?i)(?:^|[\s;|&(/=~'"`])(?:\.ssh|\.aws|\.gnupg|\.netrc|id_rsa|id_ed25519|\.env|credentials|secrets)(?=$|[\s;|&)'"`/])""".r

  /** 命令命中的全部风险类（只读命令 ⇒ 空集）。 */
  def classify(command: String): Set[CommandRisk] =
    if command == null || command.trim.isEmpty then Set.empty
    else
      val c = command.replace('\\', '/')
      val out = scala.collection.mutable.LinkedHashSet.empty[CommandRisk]
      if WriteVerbs.findFirstIn(c).nonEmpty || EditorVerbs.findFirstIn(c).nonEmpty ||
        SedInPlace.findFirstIn(c).nonEmpty || GitMutations.findFirstIn(c).nonEmpty ||
        Redirection.findFirstIn(c).nonEmpty
      then out += CommandRisk.Write
      if DeleteVerbs.findFirstIn(c).nonEmpty || DeleteFlags.findFirstIn(c).nonEmpty then out += CommandRisk.Delete
      if NetworkVerbs.findFirstIn(c).nonEmpty then out += CommandRisk.NetworkEgress
      if CredentialPaths.findFirstIn(c).nonEmpty then out += CommandRisk.CredentialAccess
      out.toSet

  /** 风险判据单点：只读命令为 false（这是「正常读文件不再触发审批」的依据）。 */
  def isRisky(command: String): Boolean = classify(command).nonEmpty

end CommandRisk
