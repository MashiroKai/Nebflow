package nebflow.core.tools

import nebflow.core.delegate.SessionResumption

/**
 * 真实四代会话链（bb1a8bbb → 556fef54 → 5dcbf6c5 → 836a475e）修复后对照读数。
 *  只读 ~/.stand/delegates 下四份记录，打印每代原始 displayName 与 displayTitle 归一结果。
 *  用法：sbt "Test/runMain nebflow.core.tools.SessionResumptionProbe"
 */
object SessionResumptionProbe:

  def main(args: Array[String]): Unit =
    val gen = List(
      "bb1a8bbb" -> "/Users/kaiyu/.stand/delegates/delegate-nebflow-bb1a8bbb.json",
      "556fef54" -> "/Users/kaiyu/.stand/delegates/delegate-nebflow-556fef54.json",
      "5dcbf6c5" -> "/Users/kaiyu/.stand/delegates/delegate-nebflow-5dcbf6c5.json",
      "836a475e" -> "/Users/kaiyu/.stand/delegates/delegate-nebflow-836a475e.json"
    )
    val normalized = gen.flatMap { case (id, path) =>
      val f = java.nio.file.Path.of(path)
      if !java.nio.file.Files.exists(f) then None
      else
        val json = io.circe.parser.parse(new String(java.nio.file.Files.readAllBytes(f), "UTF-8")).toOption
        def field(ks: String*): String =
          json.flatMap(j => ks.flatMap(k => j.hcursor.get[String](k).toOption).headOption).getOrElse("")
        val raw = field("displayTitle", "title")
        val clean = SessionResumption.displayTitle(raw, "<fallback>")
        val sid = field("sessionId")
        println(
          s"$id | rawLen=${raw.length} | prefixHits=${"continuation".r.findAllIn(raw).size} | title=${clean.take(46)}"
        )
        Some(clean)
    }
    println(s"distinct normalized titles = ${normalized.distinct.size} (of ${normalized.size} generations)")
    println(s"zero continuation marks = ${normalized.forall(!_.toLowerCase.contains("continuation"))}")
  end main
end SessionResumptionProbe
