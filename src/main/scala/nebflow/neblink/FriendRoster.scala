package nebflow.neblink

import nebflow.core.tools.ToolError

/**
 * 好友名册共用纯函数（好友消息改造批 ⑩，方案
 * `20260912_011320_好友消息改造批方案` §4.5「与 SendMessage 复用同一份好友来源与
 * 解析逻辑」同步点 ③ 定稿）。
 *
 * 存在理由：候选文案与解析顺序此前在 `FriendMessageTool.resolveFriend` 与
 * `TransferFileTool.resolveFriend` 各有一份（`TransferFileTool.scala:205-209` 的注释
 * 自陈当初内联正是为躲开跨支改名耦合）。本批把**好友消息支**的那一份抽到这里，
 * `SendMessage` 改为委托；`ListFriends` 的名册行与失败候选行同用 `candidateLine` ⇒
 * 模型在成功路径与失败路径看到**同一套词表**（方案 §4.5 ③）。
 *
 * 归属：`nebflow.neblink` 包（**不放** `core.tools`——候选文案是好友域词汇，不是
 * 工具层词汇；方案 §4.5 ③ 明定）。零 IO、零副作用。
 *
 * 2026-09-12（⑦ 备注 / 邮箱批）：解析链**前置 L0 备注层**（`⑦-D5`，见 `resolve`），
 * 候选行按 `FriendSummary.remark` 渲染备注（⑦-D6，`candidateLine`）。**本文件仍是
 * 好友面解析与候选文案的唯一实现点**——L4 邮箱层**不在这里**：它是数据面回落
 * （`FriendMessageTool` 里经 `FriendService.searchUser` 取 `userId` 回映射），
 * 不引入第二条本地匹配口径。
 *
 * `TransferFileTool` 的候选文案**本批仍不对齐**（⑦-D7：只加偏差注释、行为零变更），
 * 其显式偏差由 `FriendRosterSinglePointSpec` 的允许清单登记。
 */
object FriendRoster:

  /** 单条名册 / 候选行：`<displayName> (<username>)` + 可选备注 + 可选拉黑标记。
    *
    * - `displayName` / `username` 两键必出（`NeblinkModel.scala:611-618` 的
    *   `FriendSummary`：`username` 即 NL 号 = `resolve` 的 L1 匹配键）。
    * - `blocked`：`FriendSummary.blocked` 为 `Some(true)` 时追加 ` [blocked]`
    *   （`NeblinkModel.scala:605-606`：仅 `GET /api/friends` 的 friends 数组携带，
    *   absent = 未拉黑）。
    * - `remark`（2026-09-12 ⑦ 落地，⑦-D6）：形参是**覆盖**（测试/seam 用），
    *   缺省 `None` ⇒ **取 `f.remark`**（数据面 `FriendService.applyRemarks` 供值）
    *   ⇒ 两个既有调用点形状不变。备注**追加在括号后**、不顶替 `displayName`——
    *   与显示名**可区分**（方案 §4.5 L4①：模型必须能看出备注键存在，否则永远
    *   不会去试备注寻址）。空串/全空白备注不渲染（`filter(_.nonEmpty)`）。
    *
    * 逐字契约：`blocked` 缺席（`None`/`Some(false)`）且备注缺席时输出恒等于
    * `s"${f.displayName} (${f.username})"` —— 即 `SendMessage` 失败候选的既有字面。
    */
  def candidateLine(f: FriendSummary, remark: Option[String] = None): String =
    val base = s"${f.displayName} (${f.username})"
    val effective = remark.filter(_.nonEmpty).orElse(f.remark.filter(_.nonEmpty))
    val withRemark = effective.fold(base)(r => s"$base [remark: $r]")
    if f.blocked.contains(true) then s"$withRemark [blocked]" else withRemark

  /** 多条候选行（逗号分隔，与 `candidateLine` 同词表）。 */
  def candidates(fs: List[FriendSummary]): String =
    fs.map(f => candidateLine(f)).mkString(", ")

  /** 「可用好友」提示句：**空名册与有名册分别报告**——空表不得被写成「找不到此人」
    * 之外的含糊话，也不得把「读不到」混进来（读不到由取数层显式报错，见
    * `ListFriendsTool` / `FriendService.listFriends`）。
    *
    * 逐字契约：空表输出恒等于 `FriendMessageTool` 改动前的
    * `"The friend list is empty (no accepted friendships)."`。
    */
  def availableHint(fs: List[FriendSummary]): String =
    if fs.isEmpty then "The friend list is empty (no accepted friendships)."
    else s"Available friends: ${candidates(fs)}"

  /** 解析链：**L0 `remark`**（NOCASE + trim，2026-09-12 ⑦ 落地）→ L1 `username`
    * 精确（大小写不敏感）→ L2 `displayName` 精确 → L3 `displayName` 唯一前缀；
    * 多命中 / 零命中一律返回带候选列表的 `ToolError` 让模型在同 turn 内自行纠错。
    *
    * **L0 的两条分支口径（⑦-D5）**：
    *  - **恰 1 命中** ⇒ 成功（备注是用户自己设的别名，最精确的意图信号，排在 NL 号前）。
    *  - **多命中（≥2 个好友同一备注）⇒ 立即报错并列候选，不降级继续 L1**
    *    （「明明写了备注却不生效」的反直觉；继续降级可能命中某个恰好叫该串的
    *    username，把「备注撞车」静默变成一个别人的发送目标）。
    *  - **零命中 ⇒ 落下一级 L1**。注：任务书 `⑦-D5` 括注写「多命中 / 零命中」均立即
    *    报错，但同句的不变量「每级『恰好 1 命中』才成功，**否则落下一级**」以及判据
    *    「L1–L3 回归零变（既有 spec 绿）」都要求零命中继续降级——零命中若立即报错，
    *    所有没有备注的既有调用（含 `FriendMessageToolSpec` 的 L1/L2/L3 用例）会全红。
    *    本实现采「多命中 ⇒ 硬报错、零命中 ⇒ 降级」，两处口径冲突已在实施结果中登记。
    *
    * **本函数是 `FriendMessageTool.resolveFriend` 的唯一实现点**（原 `:67-96` 逐字
    * 搬迁 + 本批 L0 前置）：对外文案（含 `'to' is empty` 的措辞——`to` 是
    * `SendMessage` 的参数名，本函数由 `SendMessage` 消费，故保留原文）、L1–L3 顺序、
    * 候选拼接方式均零变更。
    */
  def resolve(query: String, friends: List[FriendSummary]): Either[ToolError, FriendSummary] =
    val q = query.trim
    val candidatesHint = availableHint(friends)

    if q.isEmpty then Left(ToolError(s"'to' is empty. $candidatesHint"))
    else
      // L0：备注（用户设的别名）。NOCASE + trim（备注存的是 trim 后值，防线在
      // 这里再 trim 一次，兼容手改 / 旧文件里的前后空白）。
      val byRemark = friends.filter(f => f.remark.exists(_.trim.equalsIgnoreCase(q)))
      byRemark match
        case single :: Nil => Right(single)
        case many if many.nonEmpty =>
          Left(
            ToolError(
              s"Friend '$q' is ambiguous (${many.size} matches). Candidates: ${candidates(many)} — use the exact username."
            )
          )
        case _ =>
          // L0 零命中 ⇒ 落 L1（原三级链，逐字未动）。
          // 契约词汇同步（NL 号 = Username）：byId 即按 username 精确匹配（值域
          // 与旧 neblinkId 字段一致，仅字段更名，语义不变）。
          val byId = friends.filter(_.username.equalsIgnoreCase(q))
          byId match
            case single :: Nil => Right(single)
            case _ =>
              val byName = friends.filter(_.displayName.equalsIgnoreCase(q))
              byName match
                case single :: Nil => Right(single)
                case multi =>
                  val byPrefix = friends.filter(_.displayName.toLowerCase.startsWith(q.toLowerCase))
                  val hits     = (multi ++ byPrefix).distinct
                  hits match
                    case single :: Nil => Right(single)
                    case many =>
                      Left(
                        ToolError(
                          if many.isEmpty then s"Friend '$q' not found. $candidatesHint"
                          else s"Friend '$q' is ambiguous (${many.size} matches). Candidates: ${candidates(many)} — use the exact username."
                        )
                      )
  end resolve

end FriendRoster
