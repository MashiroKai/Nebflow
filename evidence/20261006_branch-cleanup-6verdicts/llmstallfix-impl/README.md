# 快照锚：llmstallfix-impl

- 快照时间：2026-10-06 16:28 CST（复制 + 双端逐文件核对完成于同刻）
- 快照原因：作者六裁清算令 —— 分支清算执行批，阶段 1 快照（先于一切删除动作）
- 来源分支：llmstallfix-impl
- worktree 路径：/Users/kaiyu/Claude code/Nebflow/.nebflow/worktrees/llmstallfix-impl
- HEAD sha：dc6c853d7f8ef2d6b409c09dfbc0f1c9367da780
- 脏件构成：9 个已修改（M）+ 3 个未跟踪（??），共 12 个文件（338 行旧中间体即此批未提交改动）

## 原文件相对路径清单（12 个，均相对 worktree 根）

| 相对路径 | 状态 | 字节数（快照 = 源，cmp OK） |
|---|---|---|
| src/main/resources/web/js/sidebar.js | M | 164298 |
| src/main/scala/nebflow/core/LlmLogWriter.scala | M | 47484 |
| src/main/scala/nebflow/gateway/RestApiRoutes.scala | M | 263848 |
| src/main/scala/nebflow/llm/config.scala | M | 16796 |
| src/main/scala/nebflow/llm/fallback.scala | M | 18061 |
| src/main/scala/nebflow/llm/interface.scala | M | 75508 |
| src/main/scala/nebflow/llm/registry.scala | M | 12981 |
| src/main/scala/nebflow/shared/Defaults.scala | M | 61572 |
| src/test/scala/nebflow/llm/FormatErrorNoEvictSpec.scala | M | 10380 |
| src/test/scala/nebflow/core/LlmHttpErrorLogSpec.scala | ?? | 4921 |
| src/test/scala/nebflow/llm/AllCandidatesFormatLoopSpec.scala | ?? | 8028 |
| src/test/scala/nebflow/llm/PerModelContextClampSpec.scala | ?? | 4773 |

## 核对记录

- 逐文件 `cmp` 对照源 worktree 文件，12 组比对全部 OK，字节数两端一致。
