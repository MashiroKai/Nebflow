package nebflow.core.search

import munit.CatsEffectSuite

/**
 * CjkBigram —— CJK bigram 影子列变换与 FTS5 MATCH 构造（stand-recall
 * 同款路线照抄：xerj CJKAnalyzer → bigram 仿真；datalayer-spec 批 C1）。
 *
 * 验收面：索引/查询两侧同变换（口径单点）；CJK 串→bigram 保序 phrase；
 * ASCII 词→单字符 phrase（2026-10-27 盲区修复同款：索引侧 transform 对
 * 非 CJK 逐字符交还，查询侧把词拆成单字符 phrase，两侧同变换）；
 * 非 CJK 字符（空格/连字符等）原样交还，由 unicode61 二次分词；
 * 单字 CJK → LIKE 兜底信号；口径与 tools/stand-recall cjk_bigram_text /
 * fts_query 逐字对齐。
 */
class CjkBigramSpec extends CatsEffectSuite:

  test("transform：CJK 连续串 → 交叠 bigram（空格连接）") {
    assertEquals(CjkBigram.transform("检索引擎"), "检索 索引 引擎")
    assertEquals(CjkBigram.transform("中文"), "中文")
  }

  test("transform：单字 CJK run 原样保留（xerj 同款）") {
    assertEquals(CjkBigram.transform("好"), "好")
  }

  test("transform：非 CJK 字符原样交还（空格连字符同交还，unicode61 二次分词）") {
    assertEquals(CjkBigram.transform("hello world"), "hello world")
    assertEquals(CjkBigram.transform("foo-bar"), "foo-bar")
  }

  test("transform：中英混排——CJK 出 bigram、非 CJK 串整段原样交还") {
    // transform 对非 CJK **串**（连续非 CJK 字符）整段原样交还（unicode61
    // 二次分词）；与 stand-recall cjk_bigram_text 逐字对齐。
    assertEquals(CjkBigram.transform("用hover检索"), "用hover 检索")
  }

  test("transform：与 tools/stand-recall cjk_bigram_text 口径对齐的抽样") {
    // stand-recall 实测口径（§5.4 中文查询走的就是这个变换）
    assertEquals(CjkBigram.transform("聊天记录检索"), "聊天 天记 记录 录检 检索")
  }

  test("ftsQuery：CJK 多字 → 双引号 bigram phrase（保序邻接）") {
    val (and, or, like) = CjkBigram.ftsQuery("检索")
    assertEquals(and, "\"检索\"")
    assertEquals(or, "")
    assertEquals(like, "")
  }

  test("ftsQuery：CJK 多字长串 → bigram 序列单 phrase") {
    val (and, _, _) = CjkBigram.ftsQuery("检索引擎")
    assertEquals(and, "\"检索 索引 引擎\"")
  }

  test("ftsQuery：ASCII 词 → 整词单 term（unicode61 词 token 口径，两侧同变换）") {
    val (and, _, _) = CjkBigram.ftsQuery("delegate")
    assertEquals(and, "delegate")
  }

  test("ftsQuery：多词 → AND 主式 + OR 放宽式") {
    val (and, or, _) = CjkBigram.ftsQuery("hello world")
    assertEquals(and, "hello world")
    assertEquals(or, "hello OR world")
  }

  test("ftsQuery：单字 CJK 且无其他词 → LIKE 兜底信号（索引无 unigram 谓词）") {
    val (and, or, like) = CjkBigram.ftsQuery("好")
    assertEquals(and, "")
    assertEquals(or, "")
    assertEquals(like, "%好%")
  }

  test("ftsQuery：混排（单字 CJK 丢弃 + ASCII 整词）——查询侧口径") {
    // 索引侧 transform 会把 "用" 作单字保留，但查询 phrase 以 ASCII 词为主；
    // 混排时单字 CJK 可索引面丢弃（stand-recall 同款：不拖垮整体召回）。
    val (and, _, like) = CjkBigram.ftsQuery("用delegate")
    assertEquals(and, "delegate")
    assertEquals(like, "")
  }

  test("ftsQuery：词内非 alnum 字符在查询侧剔除（unicode61 两侧同弃）") {
    val (and, _, _) = CjkBigram.ftsQuery("foo-bar")
    assertEquals(and, "foobar")
  }

  test("ftsQuery：空白查询 → 三式全空（调用方自行判空）") {
    val (and, or, like) = CjkBigram.ftsQuery("   ")
    assertEquals(and, "")
    assertEquals(or, "")
    assertEquals(like, "")
  }

end CjkBigramSpec
