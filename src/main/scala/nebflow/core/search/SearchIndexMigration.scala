package nebflow.core.search

import java.nio.file.{Files, Path, Paths}
import java.sql.{Connection, DriverManager}

/** Offline, resumable migration. Only disposable databases under /tmp are accepted. */
object SearchIndexMigration:
  private val BatchSize = 200

  private def exec(c: Connection, sql: String): Unit =
    val s = c.createStatement()
    try s.executeUpdate(sql) finally s.close()

  private def get(c: Connection, key: String): Option[String] =
    val p = c.prepareStatement("SELECT value FROM search_index_meta WHERE key=?")
    try
      p.setString(1, key)
      val r = p.executeQuery()
      if r.next() then Some(r.getString(1)) else None
    finally p.close()

  private def put(c: Connection, key: String, value: String): Unit =
    val p = c.prepareStatement("INSERT INTO search_index_meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")
    try
      p.setString(1, key)
      p.setString(2, value)
      p.executeUpdate()
    finally p.close()

  private def sourceGeneration(c: Connection): String =
    val s = c.createStatement()
    try
      val r = s.executeQuery("SELECT value FROM meta WHERE key='index_generation'")
      if r.next() then r.getString(1) else "initial"
    finally s.close()

  private def grams(text: String): Set[String] =
    // SQLite lower() folds ASCII only; walk Unicode code points rather than UTF-16 units.
    val folded = text.map(ch => if ch >= 'A' && ch <= 'Z' then (ch + 32).toChar else ch)
    val points = folded.codePoints().toArray
    val b = Set.newBuilder[String]
    var i = 0
    while i < points.length do
      b += new String(points, i, 1)
      if i + 1 < points.length then b += new String(points, i, 2)
      i += 1
    b.result()

  def migrate(c: Connection, batchSize: Int = BatchSize, afterBatch: Int => Unit = _ => ()): Unit =
    require(batchSize > 0)
    c.setAutoCommit(false)
    try
      exec(c, "CREATE TABLE IF NOT EXISTS search_index_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
      exec(c, "CREATE TABLE IF NOT EXISTS search_hits(hit_row INTEGER PRIMARY KEY,stable_id TEXT NOT NULL UNIQUE,ts INTEGER NOT NULL,session_id TEXT NOT NULL,kind TEXT NOT NULL,text TEXT NOT NULL,extra TEXT NOT NULL,occurrence_count INTEGER NOT NULL)")
      exec(c, "CREATE TABLE IF NOT EXISTS search_postings(gram TEXT NOT NULL,ts INTEGER NOT NULL,sid_prefix INTEGER NOT NULL,seq INTEGER NOT NULL,hit_row INTEGER NOT NULL,PRIMARY KEY(gram,ts DESC,sid_prefix ASC,seq ASC)) WITHOUT ROWID")
      exec(c, "CREATE TABLE IF NOT EXISTS gram_freq(gram TEXT PRIMARY KEY,cnt INTEGER NOT NULL) WITHOUT ROWID")
      if get(c, "phase").isEmpty then
        put(c, "phase", "building")
        put(c, "backfill_cursor", "0")
        put(c, "source_generation", sourceGeneration(c))
        put(c, "generation", java.util.UUID.randomUUID().toString)
        put(c, "active_generation", "legacy")
      c.commit()
      if get(c, "phase").contains("done") then return
      var cursor = get(c, "backfill_cursor").get.toLong
      var batches = 0
      var more = true
      while more do
        if sourceGeneration(c) != get(c, "source_generation").get then
          throw new IllegalStateException("source generation changed during backfill; rollback and restart")
        val rows = scala.collection.mutable.ArrayBuffer.empty[(Long, String, String, Long, String, String)]
        val select = c.prepareStatement("SELECT rowid,sid,kind,ts,text,extra FROM docs WHERE rowid>? ORDER BY rowid LIMIT ?")
        try
          select.setLong(1, cursor)
          select.setInt(2, batchSize)
          val r = select.executeQuery()
          while r.next() do rows += ((r.getLong(1), r.getString(2), r.getString(3), r.getLong(4), r.getString(5), r.getString(6)))
        finally select.close()
        if rows.isEmpty then more = false
        else
          val hit = c.prepareStatement("INSERT INTO search_hits(stable_id,ts,session_id,kind,text,extra,occurrence_count) VALUES(?,?,?,?,?,?,1) ON CONFLICT(stable_id) DO UPDATE SET occurrence_count=occurrence_count+1")
          val find = c.prepareStatement("SELECT hit_row,occurrence_count FROM search_hits WHERE stable_id=?")
          val posting = c.prepareStatement("INSERT INTO search_postings(gram,ts,sid_prefix,seq,hit_row) VALUES(?,?,?,?,?)")
          val conflict = c.prepareStatement("SELECT h.stable_id FROM search_postings p JOIN search_hits h ON h.hit_row=p.hit_row WHERE p.gram=? AND p.ts=? AND p.sid_prefix=? AND p.seq=?")
          val freq = c.prepareStatement("INSERT INTO gram_freq(gram,cnt) VALUES(?,1) ON CONFLICT(gram) DO UPDATE SET cnt=cnt+1")
          try
            rows.foreach { case (row, sid, kind, ts, text, extra) =>
              val id = SearchIndexService.stableId(sid, kind, ts, text, extra)
              hit.setString(1, id); hit.setLong(2, ts); hit.setString(3, sid); hit.setString(4, kind); hit.setString(5, text); hit.setString(6, extra); hit.executeUpdate()
              find.setString(1, id)
              val found = find.executeQuery()
              if !found.next() then throw new IllegalStateException("inserted hit missing")
              val hitRow = found.getLong(1)
              val count = found.getInt(2)
              found.close()
              if count == 1 then
                val prefix = java.lang.Long.parseLong(id.substring(0, 12), 16)
                val seq = Integer.parseInt(id.substring(12, 16), 16)
                grams(text).foreach { gram =>
                  conflict.setString(1, gram); conflict.setLong(2, ts); conflict.setLong(3, prefix); conflict.setInt(4, seq)
                  val existing = conflict.executeQuery()
                  if existing.next() then
                    val other = existing.getString(1)
                    throw new IllegalStateException(s"64-bit posting collision: ts=$ts ids=$other,$id")
                  existing.close()
                  posting.setString(1, gram); posting.setLong(2, ts); posting.setLong(3, prefix); posting.setInt(4, seq); posting.setLong(5, hitRow); posting.executeUpdate()
                  freq.setString(1, gram); freq.executeUpdate()
                }
              cursor = row
            }
          finally
            hit.close(); find.close(); posting.close(); conflict.close(); freq.close()
          put(c, "backfill_cursor", cursor.toString)
          c.commit()
          batches += 1
          println(s"batch=$batches cursor=$cursor")
          afterBatch(batches)
      if sourceGeneration(c) != get(c, "source_generation").get then
        throw new IllegalStateException("source generation changed before publication; rollback and restart")
      val check = c.createStatement()
      try
        val rs = check.executeQuery("SELECT (SELECT COUNT(*) FROM docs),(SELECT COALESCE(SUM(occurrence_count),0) FROM search_hits),(SELECT COUNT(*) FROM search_postings),(SELECT COALESCE(SUM(cnt),0) FROM gram_freq)")
        if !rs.next() || rs.getLong(1) != rs.getLong(2) || rs.getLong(3) != rs.getLong(4) then
          throw new IllegalStateException("backfill count validation failed")
      finally check.close()
      put(c, "active_generation", get(c, "generation").get)
      put(c, "phase", "done")
      c.commit()
    catch
      case scala.util.control.NonFatal(e) =>
        c.rollback()
        throw e

  /** Restore legacy visibility and remove only this migration's projection. */
  def rollback(c: Connection): Unit =
    c.setAutoCommit(false)
    try
      put(c, "active_generation", "legacy")
      exec(c, "DROP TABLE IF EXISTS search_postings")
      exec(c, "DROP TABLE IF EXISTS gram_freq")
      exec(c, "DROP TABLE IF EXISTS search_hits")
      put(c, "phase", "rolled_back")
      c.commit()
    catch
      case scala.util.control.NonFatal(e) => c.rollback(); throw e

  def main(args: Array[String]): Unit =
    require(args.length == 1 || (args.length == 3 && args(1) == "--stop-after"), "usage: SearchIndexMigration /tmp/<isolated-copy>.sqlite [--stop-after N]")
    val stopAfter = if args.length == 3 then Some(args(2).toInt) else None
    val path = Paths.get(args(0)).toRealPath()
    val temp = Paths.get("/tmp").toRealPath()
    require(path.startsWith(temp) && Files.isRegularFile(path), "only existing isolated files under /tmp are permitted")
    val c = DriverManager.getConnection(s"jdbc:sqlite:$path")
    val started = System.nanoTime()
    try
      migrate(c, afterBatch = n => if stopAfter.contains(n) then throw new IllegalStateException(s"intentional interruption after batch $n"))
      println(f"elapsed_seconds=${(System.nanoTime() - started) / 1e9}%.3f")
    finally c.close()
