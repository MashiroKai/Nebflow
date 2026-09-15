package nebflow.neblink

import nebflow.core.tools.ToolError

/**
 * 好友名册共用纯函数（好友消息改造批 ⑩，方案
 * `20260912_011320_好友消息改造批方案` §4.5「与 SendMessage 复用同一份好友来源与
 * 解析逻辑」同步点 ③ 定稿）。
 *
 * 存在理由：候选文案与解析顺序此前在 `FriendMessageTool.resolveFriend` 与
 * `TransferFileTool.resolveFriend`（2026-09-14 已随 #145 退役）各有一份。本批把
 * **好友消息支**的那一份抽到这里，
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
 * `TransferFileTool` 的候选文案曾是**显式登记的第二处偏差**（⑦-D7）；该工具已随
 * #145 退役（2026-09-14），其设备面字面量迁入 `FriendMessageTool`（正交信任域，
 * 好友面单点不变），允许清单由 `FriendRosterSinglePointSpec` 同批修订。
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

  // ===== 群面（gmsgsend 批，2026-09-15 · 补充卡 §6.2–§6.4）=====
  //
  // 为什么与好友面**同住在**本文件：群目标解析与好友目标解析是**同一件事的两种目标
  // 域**——「把用户给的一个串解析成一个明确的目标，歧义/零命中一律给候选列表让模型
  // 自行纠错」。若群面另起一处实现，模型会在同一个参数 `to` 上看到**两套候选词表**
  // （正是本文件存在理由所要消灭的形态，见类头）。
  //
  // 与好友面的**同构**（逐条对齐 `resolve`）：同一「每级恰 1 命中才成功，否则落下一级」
  // 不变量；同一「多命中 ⇒ 硬报错并列候选 / 零命中 ⇒ 报 not-found + 可用表」收口；
  // 同一候选渲染法（`<名> (<唯一 id>)`）。
  //
  // 与好友面的**刻意不同**（两条，均来自补充卡 §6.2）：
  //  ① **无 L4 级**：好友面的 L4 是「邮箱 α」（一次上游 `{uid}:search` 回落 + 按
  //     `userId` 回映射）——群**没有邮箱等价物**（群只有 id 与 title）。🔴 禁给群面
  //     造一个「上游搜索」回落：那会让一次失败寻址变成一个额外的上游探测面。
  //  ② 匹配键值域 = 调用方传入的群表（= 本用户所属、**未解散**群会话；服务端
  //     `GET /api/groups` 契约「Disbanded groups never appear」）。解散态因此在
  //     解析层**结构上不可命中**：已解散的群在该表里根本不存在 ⇒ 报 not-found + 可用表，
  //     而不是让用户拿到一个「找得到但发不出去」的目标。终态判定的权威仍是服务端
  //     （404 `group_not_found` / 403 `group_disbanded`，见 `FriendService.doSendGroup`）。

  /** 单条群名册 / 候选行：`<title> (<groupId>)`。
    *
    * 🔴 两键都出、且**必须可区分**：`groupId` 是全局唯一（服务端 `grp-` + UUIDv4，
    * 与 user id 命名空间不相交），`title` 可重名（服务端只校验非空且 ≤64 字符，
    * 无唯一性约束）⇒ 候选行必须带 id，模型才有可用的消歧手段（与好友面
    * `candidateLine` 带 username 同一理由）。 */
  def groupCandidateLine(g: GroupSummary): String =
    s"${g.title} (${g.groupId})"

  /** 多条候选行（逗号分隔，与 `groupCandidateLine` 同词表）。 */
  def groupCandidates(gs: List[GroupSummary]): String =
    gs.map(groupCandidateLine).mkString(", ")

  /** 「可用群」提示句：**空表与有名册分别报告**（与 `availableHint` 同纪律）——
    * 空表必须说清是「你没有群」，不得含糊成「找不到这个群」，也不得把「读不到」
    * 混进来（读不到由取数层显式报错，见 `FriendService.listGroups`）。 */
  def availableGroupsHint(gs: List[GroupSummary]): String =
    if gs.isEmpty then "The group list is empty (you are not a member of any group)."
    else s"Available groups: ${groupCandidates(gs)}"

  /** 群解析链：**L1 `groupId` 精确**（大小写不敏感；`grp-` 前缀形态）→ **L2 `title`
    * 精确**（大小写不敏感，与好友面 L1 username 同口径）→ **L3 `title` 唯一前缀**；
    * 多命中 / 零命中一律返回带候选列表的 `ToolError`。
    *
    * `query` 为空 ⇒ 与好友面逐字同款的 `'to' is empty.` 收口（`to` 是
    * `SendMessage` 的参数名）——**正常路径到不了这里**（`group:` 后为空由
    * `parseToKind` 先拦），本分支是防御性的同词表兜底。
    */
  def resolveGroup(query: String, groups: List[GroupSummary]): Either[ToolError, GroupSummary] =
    val q              = query.trim
    val candidatesHint = availableGroupsHint(groups)

    if q.isEmpty then Left(ToolError(s"'to' is empty. $candidatesHint"))
    else
      // L1：群 id（全局唯一键；精确且大小写不敏感——id 由服务端生成，大小写不敏感
      // 只为容忍人工转写，不改变「唯一命中才成功」不变量）。
      val byId = groups.filter(_.groupId.equalsIgnoreCase(q))
      byId match
        case single :: Nil => Right(single)
        case _ =>
          // L2：群名精确。
          val byTitle = groups.filter(_.title.equalsIgnoreCase(q))
          byTitle match
            case single :: Nil => Right(single)
            case multi =>
              // L3：群名唯一前缀（与好友面 L3 同形：候选集 = 精确命中 ∪ 前缀命中，
              // `distinct` 去重后仍需恰 1 命中才成功）。
              val byPrefix = groups.filter(_.title.toLowerCase.startsWith(q.toLowerCase))
              val hits     = (multi ++ byPrefix).distinct
              hits match
                case single :: Nil => Right(single)
                case many =>
                  Left(
                    ToolError(
                      if many.isEmpty then s"Group '$q' not found. $candidatesHint"
                      else
                        s"Group '$q' is ambiguous (${many.size} matches). Candidates: ${groupCandidates(many)} — use the exact group id."
                    )
                  )
  end resolveGroup

end FriendRoster
