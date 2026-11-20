package nebflow.core.search

import java.nio.file.{Files, Path, Paths}
import java.sql.{Connection, DriverManager}

/** Offline, resumable migration. Only disposable databases under /tmp are accepted. */
object SearchIndexMigration:
  private val BatchSize = 200

  private def exec(c: Connection, sql: String): Unit =
    val s = c.createStatement()
    try s.executeUpdate(sql)
    finally s.close()

  private def get(c: Connection, key: String): Option[String] =
    val p = c.prepareStatement("SELECT value FROM search_index_meta WHERE key=?")
    try
      p.setString(1, key)
      val r = p.executeQuery()
      if r.next() then Some(r.getString(1)) else None
    finally p.close()

  private def put(c: Connection, key: String, value: String): Unit =
    val p = c.prepareStatement(
      "INSERT INTO search_index_meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value"
    )
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

  def migrate(
    c: Connection,
    source: Connection,
    batchSize: Int = BatchSize,
    maxRows: Int = 1000,
    afterBatch: Int => Unit = _ => (),
    beforeBatchCommit: Int => Unit = _ => ()
  ): Unit =
    require(batchSize > 0)
    c.setAutoCommit(false)
    try
      exec(c, "CREATE TABLE IF NOT EXISTS search_index_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
      exec(
        c,
        "CREATE TABLE IF NOT EXISTS search_hits(hit_row INTEGER PRIMARY KEY,stable_id TEXT NOT NULL UNIQUE,ts INTEGER NOT NULL,session_id TEXT NOT NULL,kind TEXT NOT NULL,text TEXT NOT NULL,extra TEXT NOT NULL,occurrence_count INTEGER NOT NULL,ord INTEGER NOT NULL)"
      )
      exec(
        c,
        "CREATE TABLE IF NOT EXISTS search_postings(gram TEXT NOT NULL,ts INTEGER NOT NULL,sid_prefix INTEGER NOT NULL,seq INTEGER NOT NULL,hit_row INTEGER NOT NULL,PRIMARY KEY(gram,ts DESC,sid_prefix ASC,seq ASC)) WITHOUT ROWID"
      )
      exec(c, "CREATE TABLE IF NOT EXISTS gram_freq(gram TEXT PRIMARY KEY,cnt INTEGER NOT NULL) WITHOUT ROWID")
      if get(c, "phase").isEmpty then
        put(c, "phase", "building")
        put(c, "backfill_cursor", "0")
        put(c, "source_generation", sourceGeneration(source))
        put(c, "generation", java.util.UUID.randomUUID().toString)
        put(c, "active_generation", "legacy")
      c.commit()
      if get(c, "phase").contains("done") then return
      var cursor = get(c, "backfill_cursor").get.toLong
      var batches = 0
      val highWater = get(c, "high_water").map(_.toLong).getOrElse {
        val s = source.createStatement()
        try
          val r =
            s.executeQuery(s"SELECT COALESCE(MAX(rowid),0) FROM (SELECT rowid FROM docs ORDER BY rowid LIMIT $maxRows)")
          r.next()
          val value = r.getLong(1)
          put(c, "high_water", value.toString)
          c.commit()
          value
        finally s.close()
      }
      var more = true
      while more do
        if sourceGeneration(source) != get(c, "source_generation").get then
          throw new IllegalStateException("source generation changed during backfill; rollback and restart")
        val rows = scala.collection.mutable.ArrayBuffer.empty[(Long, String, String, Long, String, String, Int)]
        val select = source.prepareStatement(
          "SELECT rowid,sid,kind,ts,text,extra,ord FROM docs WHERE rowid>? AND rowid<=? ORDER BY rowid LIMIT ?"
        )
        try
          select.setLong(1, cursor)
          select.setLong(2, highWater)
          select.setInt(3, batchSize)
          val r = select.executeQuery()
          while r.next() do
            rows += ((
              r.getLong(1),
              r.getString(2),
              r.getString(3),
              r.getLong(4),
              r.getString(5),
              r.getString(6),
              r.getInt(7)
            ))
        finally select.close()
        end try
        if rows.isEmpty then more = false
        else
          val hit = c.prepareStatement(
            "INSERT INTO search_hits(stable_id,ts,session_id,kind,text,extra,occurrence_count,ord) VALUES(?,?,?,?,?,?,1,?) ON CONFLICT(stable_id) DO UPDATE SET occurrence_count=occurrence_count+1, extra=CASE WHEN (excluded.session_id,excluded.ord,excluded.extra)<(search_hits.session_id,search_hits.ord,search_hits.extra) THEN excluded.extra ELSE search_hits.extra END, ord=MIN(search_hits.ord,excluded.ord)"
          )
          val find = c.prepareStatement("SELECT hit_row,occurrence_count FROM search_hits WHERE stable_id=?")
          val posting =
            c.prepareStatement("INSERT INTO search_postings(gram,ts,sid_prefix,seq,hit_row) VALUES(?,?,?,?,?)")
          val conflict = c.prepareStatement(
            "SELECT h.stable_id FROM search_postings p JOIN search_hits h ON h.hit_row=p.hit_row WHERE p.gram=? AND p.ts=? AND p.sid_prefix=? AND p.seq=?"
          )
          val freq =
            c.prepareStatement("INSERT INTO gram_freq(gram,cnt) VALUES(?,1) ON CONFLICT(gram) DO UPDATE SET cnt=cnt+1")
          try
            rows.foreach { case (row, sid, kind, ts, text, extra, ord) =>
              val id = SearchIndexService.stableId(sid, kind, ts, text, extra)
              hit.setString(1, id); hit.setLong(2, ts); hit.setString(3, sid); hit.setString(4, kind);
              hit.setString(5, text); hit.setString(6, extra); hit.setInt(7, ord); hit.executeUpdate()
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
                  conflict.setString(1, gram); conflict.setLong(2, ts); conflict.setLong(3, prefix);
                  conflict.setInt(4, seq)
                  val existing = conflict.executeQuery()
                  if existing.next() then
                    val other = existing.getString(1)
                    throw new IllegalStateException(s"64-bit posting collision: ts=$ts ids=$other,$id")
                  existing.close()
                  posting.setString(1, gram); posting.setLong(2, ts); posting.setLong(3, prefix);
                  posting.setInt(4, seq); posting.setLong(5, hitRow); posting.executeUpdate()
                  freq.setString(1, gram); freq.executeUpdate()
                }
              end if
              cursor = row
            }
          finally
            hit.close(); find.close(); posting.close(); conflict.close(); freq.close()
          end try
          put(c, "backfill_cursor", cursor.toString)
          beforeBatchCommit(batches + 1)
          c.commit()
          batches += 1
          println(s"batch=$batches cursor=$cursor")
          afterBatch(batches)
        end if
      end while
      if sourceGeneration(source) != get(c, "source_generation").get then
        throw new IllegalStateException("source generation changed before publication; rollback and restart")
      val check = c.createStatement()
      try
        val expected = source.createStatement()
        val expectedCount =
          try
            val r = expected.executeQuery(s"SELECT COUNT(*) FROM docs WHERE rowid<=$highWater")
            r.next()
            r.getLong(1)
          finally expected.close()
        val rs = check.executeQuery(
          "SELECT (SELECT COALESCE(SUM(occurrence_count),0) FROM search_hits),(SELECT COUNT(*) FROM search_postings),(SELECT COALESCE(SUM(cnt),0) FROM gram_freq)"
        )
        if !rs.next() || expectedCount != rs.getLong(1) || rs.getLong(2) != rs.getLong(3) then
          throw new IllegalStateException("backfill count validation failed")
      finally check.close()
      put(c, "active_generation", get(c, "generation").get)
      put(c, "phase", "done")
      c.commit()
    catch
      case scala.util.control.NonFatal(e) =>
        c.rollback()
        throw e

    end try

  end migrate

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
      case scala.util.control.NonFatal(e) =>
        c.rollback()
        throw e

  private def makeSessionSnapshot(output: String, sessions: String): Unit =
    val temp = Paths.get("/tmp").toRealPath()
    val path = Paths.get(output).toAbsolutePath.normalize()
    require(path.getParent.toRealPath().startsWith(temp) && !Files.exists(path), "new snapshot under /tmp required")
    val adapter = new nebflow.core.search.chat.ChatSearchAdapter(os.Path(sessions))
    val all = adapter.sessionIdsForReconcile.get.sorted
    val selected = all
    println(s"eligible_sessions=${all.size} selected_sessions=${selected.size}")
    val c = DriverManager.getConnection(s"jdbc:sqlite:$path")
    c.setAutoCommit(false)
    try
      exec(c, "CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT)")
      exec(c, "INSERT INTO meta VALUES('index_generation','snapshot')")
      exec(c, "CREATE TABLE docs(sid TEXT,kind TEXT,ts INTEGER,text TEXT,extra TEXT,ord INTEGER)")
      val p = c.prepareStatement("INSERT INTO docs(sid,kind,ts,text,extra,ord) VALUES(?,?,?,?,?,?)")
      var count = 0
      try
        selected.foreach { sid =>
          val before = adapter.sessionFingerprint(sid)
          val docs = adapter.extractSession(sid, true).getOrElse(Nil)
          require(before == adapter.sessionFingerprint(sid), "session changed during extraction")
          docs.foreach { d =>
            p.setString(1, d.sid); p.setString(2, d.kind); p.setLong(3, d.ts); p.setString(4, d.text);
            p.setString(5, d.extra); p.setInt(6, d.ord); p.executeUpdate()
            count += 1
          }
          c.commit()
          if Files.size(path) > 100000000 then throw new IllegalStateException("snapshot exceeds 100 MB safety budget")
        }
      finally p.close()
      println(s"snapshot_docs=$count snapshot_bytes=${Files.size(path)}")
    finally c.close()

    end try

  end makeSessionSnapshot

  def main(args: Array[String]): Unit =
    if args.length == 3 && args(0) == "--snapshot" then
      makeSessionSnapshot(args(1), args(2))
      return
    require(
      args.length == 2 || (args.length == 3 && Set("--halt-50", "--halt-before-commit-50").contains(args(2))),
      "usage: SearchIndexMigration /tmp/<output>.sqlite /path/to/source.sqlite [--halt-50]"
    )
    val temp = Paths.get("/tmp").toRealPath()
    val path = Paths.get(args(0)).toAbsolutePath.normalize()
    require(path.getParent.toRealPath().startsWith(temp), "output must be under /tmp")
    val srcPath = Paths.get(args(1)).toRealPath()
    require(path != srcPath, "source and destination must differ")
    val source = DriverManager.getConnection(s"jdbc:sqlite:file:$srcPath?mode=ro")
    val c = DriverManager.getConnection(s"jdbc:sqlite:$path")
    val started = System.nanoTime()
    try
      migrate(
        c,
        source,
        batchSize = 20,
        maxRows = Int.MaxValue,
        afterBatch = n => if args.length == 3 && args(2) == "--halt-50" && n == 50 then Runtime.getRuntime.halt(1),
        beforeBatchCommit =
          n => if args.length == 3 && args(2) == "--halt-before-commit-50" && n == 50 then Runtime.getRuntime.halt(1)
      )
      println(f"elapsed_seconds=${(System.nanoTime() - started) / 1e9}%.3f")
    finally
      c.close()
      source.close()
  end main
end SearchIndexMigration
