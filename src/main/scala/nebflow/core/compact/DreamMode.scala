package nebflow.core.compact

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Dream-mode extraction helpers — fact-line parsing and entry identity.
 *
 * The Dream-mode mechanism is retired: the engine no longer creates, updates or
 * targets the stable `## Dream Extract` section. Removed with it: the section
 * header constant (`DreamSectionHeader`), the merge core
 * (`mergeFactsIntoSection` — whose no-section branch was the single place where
 * the engine created that section by itself), the T3 lifecycle core
 * (`t3Evolve` / `promotedTexts` / `TtlMs` / `MaxEntries` / the
 * `.dream-timestamps.json` sidecar), the section renderer (`renderSection`) and
 * the idle extraction prompt/response parser (`DreamPrompt` / `parseResponse`).
 *
 * Consequences carried by that removal (as registered, not mitigated):
 *   - an existing `## Dream Extract` section in `~/.nebflow/User.md` keeps its
 *     content but has no receiver — no migration, no receiver track; it is
 *     ordinary file content the consolidation track may edit like any other;
 *   - the T3 fallback eviction (14-day TTL / 60-entry FIFO) is gone with the
 *     lifecycle core, so nothing ages that section automatically;
 *   - no queue note is tagged with a section name any more (the pre-compaction
 *     fact pipeline appends at file tail, `section = None`).
 *
 * What remains is what the pre-compaction fact pipeline still needs:
 *   - [[parseFact]] / `FactPattern` — parse a `FACT n: [CATEGORY] text` line;
 *   - [[entryHash]] — stable sha256 of a normalized entry text, used as the
 *     entry identity in the hook's route records.
 *
 * The `MemoryQueue` `section` field itself is unchanged (it is a locator;
 * `None` = file tail). Do not reintroduce a section constant here: the whole
 * point of the removal is that no named section is engine-owned.
 */
object DreamMode:
  private val FactPattern = """FACT\s*\d+\s*:\s*\[([A-Za-z_]+)\]\s*(.*)""".r

  /** Parse a single "FACT N: [CATEGORY] text" line → (category, text). */
  def parseFact(line: String): Option[(String, String)] =
    line.trim match
      case FactPattern(cat, text) if text.trim.nonEmpty => Some((cat.toUpperCase, text.trim))
      case _ => None

  /** sha256 of the normalized entry text — stable entry identity (rename-stable). */
  def entryHash(text: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(text.trim.getBytes(StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString

end DreamMode
