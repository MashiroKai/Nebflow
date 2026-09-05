# memory.md「记忆管理规则」节替换文本（Nebula 本人执行项）

> 职权红线：本文件只是 staging 参考。~/.nebflow/agents/Nebula/memory.md 是 Nebula
> 记忆本体，宿主/节点禁 cp 直写——由 Nebula 本人执行下述 MemoryEdit。

## 执行指令（给 Nebula）

```
MemoryEdit(target="agent", action="replace_section",
           section="记忆管理规则",
           content=<下方新节全文>)
```

- 若报 MEMORYEDIT_NO_SECTION：错误消息会列出实际节名——按实际节名重试
  （方案 §1.2 记录该节 1,015B/4 条，标题含「记忆管理规则」字样）。
- 替换后 `wc -c ~/.nebflow/agents/Nebula/memory.md` 应下降 ~0.6KB（旧 4 条中
  3 条 stale：路径错误分层条目、projectMemory 幻觉条目、save turn 四步循环条目；
  三问准入以新形态保留）。旧的「四步循环」表述随替换清除（§6.2-2.6 验收点）。

## 新节全文（content 参数值）

## 记忆管理规则

- 写入三问准入（不满足不写）：①hard-to-obtain——一次 Read/Grep/git 可恢复的不记（commit hash、HEAD 链、worktree 路径、代码事实）；②reusable——一次性任务细节不记；③current-state-first——新状态 update 进既有条目不新开条目；新裁定推翻旧裁定时 append 与 remove 同轮成对（取代而非追加）。
- 分级标注（新条目末尾标 [T1]/[T2]，T3 由 Dream hook 管理）：[T1] 裁定/偏好/身份/教训——永久，唯一收缩路径=取代规则；[T2] 状态类（批次/待重启/在途/验收）——事件闭环即删，兜底 7 天；[T3] Dream 稳定节——14 天未晋升自动淘 + 60 条 FIFO。
- 预算（写入侧 MemoryEdit 强制，注入侧永不截断）：User.md 硬顶 50KB/软警 40KB；memory.md 硬顶 30KB/软警 24KB；超硬顶拒绝（附最大节定位），超 80% WARN——收到当轮整理。记忆分层两级：~/.nebflow/User.md（user 级）+ ~/.nebflow/agents/Nebula/memory.md（agent 级），仅此两级。
- 整理执行（2026-09-05 起）：周审计 Schedule 周日 21:30 触发 memory-consolidation flow（scanner-only）产条目级报告回投本人执行 MemoryEdit——审计节点不直写记忆；压缩后/重启后 Memory hygiene 提醒 = 顺手 T2 闭环清扫；方法论见 skills/memory-consolidation。
