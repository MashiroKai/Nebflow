package nebflow.core.browser

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/*
 * cubu-round2 批三 · BU secret 引用解析（browser-verbs-p0 设计 P1 项「secret 引用解析」）。
 *
 * browser_fill 的 text 写成 `secret:<name>` 时，daemon 侧从数据根 secrets/<name> 读取明文并注入页面；
 * 模型上下文只看到引用，看不到值；结果只报字符数（BrowserSessions.fill 既有口径）。
 *
 * 红线：
 *  - 名称白名单（小写字母数字起首，允许 . _ -，≤64 位）；拒路径分隔符与 `..`，杜绝逃出 secrets 目录。
 *  - 任何失败文案都不回显名称与值，只给统一原因。
 *  - 值不进日志、不进异常消息（读取失败统一为 Refusal）。
 */
object BrowserSecretRef:

  val Prefix: String = "secret:"

  /** 统一拒绝文案：不含名称、不含值。 */
  val Refusal: String =
    "secret: 引用解析失败（名称非法、不存在或不可读）；本次未填入任何内容，且不回显输入。"

  private val NamePattern = "^[a-z0-9][a-z0-9._-]{0,63}$".r

  /** 输入是否为 secret 引用语法（前缀判定，不校验名称）。 */
  def isRef(text: String): Boolean = text.trim.startsWith(Prefix)

  /** 名称是否合法（白名单 + 禁 `..`）。 */
  def validName(name: String): Boolean =
    NamePattern.matches(name) && !name.contains("..")

  /**
   * 解析引用：返回明文值（仅此一处出现）或统一拒绝文案。
   * secretsDir 为 `<dataRoot>/secrets`，由调用方注入便于测试。
   */
  def resolve(secretsDir: Path, text: String): Either[String, String] =
    val name = text.trim.stripPrefix(Prefix)
    if !isRef(text) || !validName(name) then Left(Refusal)
    else
      val p = secretsDir.resolve(name)
      if !Files.isRegularFile(p) then Left(Refusal)
      else
        scala.util
          .Try(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
          .toEither
          .left
          .map(_ => Refusal)

end BrowserSecretRef
