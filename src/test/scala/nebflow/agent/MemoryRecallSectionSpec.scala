package nebflow.agent

import munit.FunSuite

/**
 * 记忆检索库指引段 spec（2026-10-29 作者令「让主 agent 知道如何用 CLI 检索
 * 历史记忆」批）：renderMemoryBlock 的 # Memory 头部固定携带 stand-recall 检索
 * 指引——2026-10-21 记忆改制后记忆主库 = 对话检索库（`~/.stand/memory-db/`），
 * 检索 CLI = `tools/stand-recall`（仓根执行；cwd 漂移回仓根/绝对路径）；
 * detail files 机制已退役（detail-demote-retire 批 2026-10-30，作者 15:58 裁定）——
 * 不再教读/新建，记忆块头部不再携带 →id 指引行。
 *
 * 纯渲染直测（不触文件系统）。断言=机制锚点（contains），不做逐字节快照——
 * 措辞微调不应碎测试；CLI 名 / 库路径 / 命令形态 / cwd 纪律才是锚。
 */
class MemoryRecallSectionSpec extends FunSuite:

  private val block =
    ContextRefresher.renderMemoryBlock(Some("- 名字：星尘"), Some("- 称呼：Kai"), (false, false))

  test("recall: 检索指引段随记忆块渲染（机制锚点：段标题 / CLI 名 / 调用形态 / 库路径）"):
    assert(block.contains("## Recall archive"), block)
    assert(block.contains("tools/stand-recall"), "CLI 名在场")
    assert(block.contains("./tools/stand-recall"), "调用形态（仓根相对路径）在场")
    assert(block.contains("~/.stand/memory-db/"), "检索库路径在场")

  test("recall: cwd 漂移纪律 + 先检索再行动 + 输出形态锚点在场"):
    assert(block.contains("repo root"), "仓根执行纪律（cwd 漂移坑）在场")
    assert(block.contains("absolute path"), "绝对路径逃生口在场")
    assert(block.contains("search FIRST"), "先检索再行动在场")
    assert(block.contains("session anchor"), "输出带会话锚在场")
    assert(block.contains("commit hashes"), "穷尽自检索（近义词/英文/提交号）在场")

  test("recall: →id detail-file 指引行已摘除（机制退役，不再教读新建）"):
    assert(!block.contains("→id"), block)
    assert(!block.contains("~/.stand/memory/{id}.md"), block)

  test("recall: 指引段在记忆主体之前（头部指引先于内容）"):
    val iRecall = block.indexOf("## Recall archive")
    val iSoul = block.indexOf("## Soul")
    assert(iRecall >= 0 && iSoul > iRecall, s"指引段先于 Soul 块: $block")

  test("recall: 主体缺省 ⇒ 整块不渲染（既有空态语义不变，指引不单独成块）"):
    assertEquals(ContextRefresher.renderMemoryBlock(None, None, (false, false)), "")

end MemoryRecallSectionSpec
