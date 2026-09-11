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
 * 工具层词汇；方案 §4.5 ③ 明定）。零 IO、零副作用、零模型字段新增。
 *
 * `TransferFileTool` 本批**零改动**（其候选文案对齐属 ⑦-D7 待作者拍板，不在 ⑩ 单）。
 */
object FriendRoster:

  /** 单条名册 / 候选行：`<displayName> (<username>)` + 可选备注 + 可选拉黑标记。
    *
    * - `displayName` / `username` 两键必出（`NeblinkModel.scala:611-618` 的
    *   `FriendSummary`：`username` 即 NL 号 = `resolve` 的 L1 匹配键）。
    * - `blocked`：`FriendSummary.blocked` 为 `Some(true)` 时追加 ` [blocked]`
    *   （`NeblinkModel.scala:605-606`：仅 `GET /api/friends` 的 friends 数组携带，
    *   absent = 未拉黑）。
    * - `remark`：**本批永远为 `None`** —— ⑦（备注 / 邮箱批）待作者拍板未落地，
    *   `FriendSummary` 无 `remark` 字段，本批禁加（方案 §4.5 红线：不得为 remark
    *   改 Decoder/Encoder/REST 面）。形参是**预留扩展点**：⑦ 落地后由数据面供值，
    *   本函数与两个调用点的形状不变（L4① 待 ⑦ 落地，见结果登记）。
    *
    * 逐字契约：`blocked` 缺席（`None`/`Some(false)`）且 `remark = None` 时输出
    * 恒等于 `s"${f.displayName} (${f.username})"` —— 即 `SendMessage` 失败候选的
    * 既有字面（`FriendMessageTool` 改动前的 `:71/:93`）。
    */
  def candidateLine(f: FriendSummary, remark: Option[String] = None): String =
    val base = s"${f.displayName} (${f.username})"
    val withRemark = remark.filter(_.nonEmpty).fold(base)(r => s"$base [remark: $r]")
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

  /** 三级解析：`username` 精确（大小写不敏感）→ `displayName` 精确 →
    * `displayName` 唯一前缀；多命中 / 零命中一律返回带候选列表的 `ToolError`
    * 让模型在同 turn 内自行纠错。
    *
    * **本函数是 `FriendMessageTool.resolveFriend` 的逐字搬迁**（原 `:67-96`）：
    * 对外文案（含 `'to' is empty` 的措辞——`to` 是 `SendMessage` 的参数名，本函数
    * 由 `SendMessage` 消费，故保留原文）、L1–L3 顺序、候选拼接方式均零变更。
    */
  def resolve(query: String, friends: List[FriendSummary]): Either[ToolError, FriendSummary] =
    val q = query.trim
    val candidatesHint = availableHint(friends)

    if q.isEmpty then Left(ToolError(s"'to' is empty. $candidatesHint"))
    else
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
