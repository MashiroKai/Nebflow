package nebflow.db

import munit.FunSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * V1 —— 撕裂窗注入（五步写面）：rename 前后各相位边界注入崩溃（合计 ≥20 次），
 * 断言：无半档（目标恒为旧完整或新完整）、无 0 长真档、无 tmp 残留判定（启动清扫收口）。
 * 追加面的撕裂尾注入（brief 认可形态：直写半行 JSONL 模拟 kill-9）见 DbCorruptionSpec。
 */
class DbWriteFaceSpec extends FunSuite:

  private val crashPoints = List(
    WriteFace.CrashPoint.AfterTmpWrite,
    WriteFace.CrashPoint.AfterTmpForce,
    WriteFace.CrashPoint.AfterRename,
    WriteFace.CrashPoint.AfterDirForce
  )

  test("V1: five-step write survives crash injection at every phase boundary (24 injections)"):
    val dir = Files.createTempDirectory("db-v1-five-step")
    val target = dir.resolve("state.json")
    val oldContent = """{"version":1,"payload":"OLD"}"""
    val newContent = """{"version":2,"payload":"NEW-LONGER"}"""
    Files.writeString(target, oldContent)
    var injections = 0
    crashPoints.foreach { point =>
      for i <- 1 to 6 do
        val expectNew = point == WriteFace.CrashPoint.AfterRename ||
          point == WriteFace.CrashPoint.AfterDirForce
        val before = Files.readString(target)
        intercept[WriteFace.SimulatedCrash] {
          WriteFace.writeFiveStep(
            target,
            newContent.getBytes("UTF-8"),
            hook = p => if p == point then throw WriteFace.SimulatedCrash(point)
          )
        }
        injections += 1
        // 无 0 长真档 + 无半档：崩溃后目标要么旧完整要么新完整。
        assert(Files.exists(target), s"target vanished at $point#$i")
        val after = Files.readString(target)
        assert(after.nonEmpty, s"zero-length target at $point#$i")
        val expected = if expectNew then newContent else before
        assertEquals(after, expected, s"torn content at $point#$i")
    }
    assert(injections >= 20, s"injection count $injections < 20")

  test("V1: first-ever write crash never leaves a zero-length authoritative file"):
    val dir = Files.createTempDirectory("db-v1-first-write")
    val target = dir.resolve("fresh.json")
    crashPoints.foreach { point =>
      intercept[WriteFace.SimulatedCrash] {
        WriteFace.writeFiveStep(
          target,
          """{"a":1}""".getBytes,
          p => if p == point then throw WriteFace.SimulatedCrash(p)
        )
      }
      if Files.exists(target) then
        assert(Files.size(target) > 0, s"zero-length target after crash at $point")
        assertEquals(Files.readString(target), """{"a":1}""")
    }

  test("V1: startup sweep removes orphan tmp residue; non-tmp files untouched"):
    val dir = Files.createTempDirectory("db-v1-sweep")
    val tmp1 = Files.createFile(dir.resolve("events.1.jsonl.tmp-1234-abcdef01"))
    val tmp2 = Files.createFile(dir.resolve("counter.json.tmp-9999-00112233"))
    val legit = Files.createFile(dir.resolve("events.1.jsonl"))
    Files.writeString(legit, "{}\n")
    val swept = WriteFace.sweepOrphanTmp(dir)
    assertEquals(
      swept.map(_.getFileName.toString).sorted,
      List(tmp1.getFileName.toString, tmp2.getFileName.toString).sorted
    )
    assert(!Files.exists(tmp1) && !Files.exists(tmp2), "tmp residue must be gone")
    assert(Files.exists(legit), "legit file must survive the sweep")
    assert(WriteFace.sweepOrphanTmp(dir).isEmpty, "second sweep is a no-op")

  test("V1: torn tail repair truncates unacked fragment, keeps framed records intact"):
    val dir = Files.createTempDirectory("db-v1-torn")
    val f = dir.resolve("stream.jsonl")
    val good1 = """{"seq":1}"""
    val good2 = """{"seq":2}"""
    val torn = """{"seq":3,"partial"""
    Files.writeString(f, s"$good1\n$good2\n$torn") // kill-9 撕裂窗：尾行无换行
    val state = WriteFace.prepareAppend(f, _ => Right(()))
    state match
      case WriteFace.TailState.RepairedTornTail(bytes) =>
        assertEquals(bytes, torn.getBytes("UTF-8").length.toLong)
      case other => fail(s"expected RepairedTornTail, got $other")
    assertEquals(Files.readString(f), s"$good1\n$good2\n", "framed records intact, fragment gone")

  test("V1: bad framed tail (complete line, invalid content) refuses append — never corrupts further"):
    val dir = Files.createTempDirectory("db-v1-badtail")
    val f = dir.resolve("stream.jsonl")
    Files.writeString(f, s"""{"seq":1}\n{"seq":2}\nNOT-JSON-AT-ALL\n""")
    WriteFace.prepareAppend(f, line => if line.startsWith("{") then Right(()) else Left("decode failed")) match
      case WriteFace.TailState.BadTail(why) => assert(why.nonEmpty)
      case other => fail(s"expected BadTail, got $other")
    // 原文件字节不动（拒绝路径零修改）。
    assert(Files.readString(f).contains("NOT-JSON-AT-ALL"))

  test("prepareAppend handles missing empty and entirely torn files without validation"):
    val dir = Files.createTempDirectory("db-tail-empty")
    val f = dir.resolve("stream.jsonl")
    val never: String => Either[String, Unit] = _ => fail("unexpected validation")
    assertEquals(WriteFace.prepareAppend(f, never), WriteFace.TailState.Clean)
    Files.writeString(f, "")
    assertEquals(WriteFace.prepareAppend(f, never), WriteFace.TailState.Clean)
    Files.writeString(f, "torn")
    assertEquals(WriteFace.prepareAppend(f, never), WriteFace.TailState.RepairedTornTail(4L))
    assertEquals(Files.size(f), 0L)
    Files.writeString(f, "\n")
    assertEquals(WriteFace.prepareAppend(f, never), WriteFace.TailState.Clean)

  test("prepareAppend scans backwards over chunk boundaries to the last complete frame"):
    val dir = Files.createTempDirectory("db-tail-chunk")
    val f = dir.resolve("stream.jsonl")
    val frame = "a" * 8200
    Files.writeString(f, s"older\n$frame\ntorn")
    var seen = ""
    assertEquals(WriteFace.prepareAppend(f, line => { seen = line; Right(()) }), WriteFace.TailState.RepairedTornTail(4L))
    assertEquals(seen, frame)
    assertEquals(Files.readString(f), s"older\n$frame\n")

  test("prepareAppend finds the last frame beyond a multi-block torn tail"):
    val dir = Files.createTempDirectory("db-tail-long-torn")
    val f = dir.resolve("stream.jsonl")
    val torn = "x" * 8193
    Files.writeString(f, s"older\nlast-frame\n$torn")
    val seen = scala.collection.mutable.ListBuffer.empty[String]
    val state = WriteFace.prepareAppend(f, line => { seen += line; Right(()) })
    assertEquals(state, WriteFace.TailState.RepairedTornTail(8193L))
    assertEquals(seen.toList, List("last-frame"))
    assertEquals(Files.readString(f), "older\nlast-frame\n")

  test("appendDurable rejects multi-line records (framing constraint)"):
    val dir = Files.createTempDirectory("db-v1-append")
    intercept[IllegalArgumentException] {
      WriteFace.appendDurable(dir.resolve("a.jsonl"), "line1\nline2", WriteFace.FsyncTier.PerRecord)
    }
end DbWriteFaceSpec
