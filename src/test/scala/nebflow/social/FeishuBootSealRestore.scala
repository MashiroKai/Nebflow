package nebflow.social

import io.circe.Json
import nebflow.shared.PathUtil

/**
 * feishu-boot-seal (2026-09-28) — MANUAL restore entry point, Test scope only.
 *
 * The live host's `~/.nebflow/nebflow.json` came out of the 18:00:06 incident
 * with `socialChannels.channels.feishu.enabled == false` while the channel's
 * stored credentials (app_id / region / `app_secret_ref`) were untouched. The
 * REST face cannot be used to put it back: `POST /api/social/channels/feishu`
 * answers 403 without `~/.nebflow/auth.json`'s token, and this batch is
 * forbidden from reading credential values. The restore therefore goes through
 * the SAME in-repo write path the product uses —
 * [[SocialChannels.save]](`PathUtil.dataRoot`, "feishu", {"enabled": true}) —
 * with an explicit `enabled` so the write is a user-authorised statement about
 * the switch, not an inference.
 *
 * Same shape as [[FeishuLiveProbe]]: an `object` with a `main` (so `sbt test`
 * never discovers it), an explicit `System.exit` owning the exit code, and a
 * refusal to print anything but a mask:
 *   · no credential value is read, printed or written — the body carries
 *     `enabled` alone, and the report prints `enabled`, `updatedAt` and the
 *     field KEY names plus `_ref` PATHS only;
 *   · no gateway restart, no port touch, no signal: this process only performs
 *     one surgical read-modify-write of the config file, exactly like the
 *     product's own save path.
 *
 * Modes:
 *   read                — print the reading only (writes nothing)
 *   restore             — print the pre-reading, then set `enabled = true`
 *   restore <true|false> — same, with an explicit target value
 *
 * Exit codes: 0 = done, 2 = target channel unknown, 3 = save refused, 4 = readback mismatch.
 */
object FeishuBootSealRestore:

  private val ChannelId = "feishu"

  /** Everything a reader needs, and nothing that could leak a credential:
   *  field KEY names and `_ref` PATHS are structure, never content. */
  private def reading(root: os.Path): String =
    val entry = SocialChannels.readChannels(root).hcursor
      .downField("channels").downField(ChannelId)
    val enabled = entry.downField("enabled").as[Boolean].toOption
    val updatedAt = entry.downField("updatedAt").as[Long].toOption
    val fields = entry.downField("fields").focus.getOrElse(Json.obj())
    val names = fields.asObject.map(_.keys.toList.sorted).getOrElse(Nil)
    val refs = names.filter(_.endsWith("_ref")).flatMap { k =>
      fields.hcursor.downField(k).as[String].toOption.map(v => s"$k=$v")
    }
    val keyState = if enabled.isEmpty then "<absent>" else enabled.get.toString
    s"enabled=$keyState updatedAt=${updatedAt.map(_.toString).getOrElse("<absent>")} " +
      s"fieldKeys=[${names.mkString(",")}] refs=[${refs.mkString(",")}]"

  /** One live-config snapshot, byte-exact, so the pre-state survives the write
    *  even if the host's own backups rotate. Pure copy; nothing is modified. */
  private def snapshot(root: os.Path): Unit =
    val src = PathUtil.configJsonWritePath(root)
    try
      if os.exists(src) then
        val out = os.pwd / "config-snapshot-before-restore.json"
        os.copy.over(src, out)
        println(s"[restore] snapshot: $out (bytes=${os.size(out)})")
    catch case e: Exception => println(s"[restore] snapshot skipped: ${e.getMessage}")

  private def root(): os.Path = PathUtil.dataRoot

  def main(args: Array[String]): Unit =
    val mode = args.headOption.getOrElse("read")
    val target = args.drop(1).headOption.map(_.toBoolean).getOrElse(true)
    val exitCode =
      try run(mode, target)
      catch
        case e: Throwable =>
          println(s"[restore] FATAL ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")
          5
    println(s"[restore] exit=$exitCode")
    System.exit(exitCode)

  private def run(mode: String, target: Boolean): Int =
    val r = root()
    println(s"[restore] dataRoot=$r config=${PathUtil.configJsonWritePath(r)}")
    println(s"[restore] PRE  ${reading(r)}")
    mode match
      case "read" => 0
      case "restore" =>
        snapshot(r)
        // The explicit switch — the body says what the USER decided, so the
        // save path never has to infer it (and, on the fixed writer, a body
        // without the key would keep the stored value instead).
        val body = io.circe.parser
          .parse(s"""{"enabled":$target}""").toOption.get
        SocialChannels.save(r, ChannelId, body) match
          case Left(err) =>
            println(s"[restore] save refused: $err")
            3
          case Right(ok) =>
            println(s"[restore] save ok: ${ok.noSpaces}")
            val post = reading(r)
            println(s"[restore] POST $post")
            val landed = SocialChannels.readChannels(r).hcursor
              .downField("channels").downField(ChannelId)
              .downField("enabled").as[Boolean].toOption
            if landed.contains(target) then 0
            else
              println(s"[restore] readback mismatch: wanted $target, read $landed")
              4
      case other =>
        println(s"[restore] unknown mode '$other' (read | restore [true|false])")
        2
