# Project Memory — <项目名>

<!--
  每项目 workspace `.nebflow/memory.md` 初始化模板（project-memory 批 2026-09-05）。
  宿主 cp 到 `<workspace>/.nebflow/memory.md` 后，将首行 `<项目名>` 替换为注册表项目名。
  git 处置=运行时层不进 git：workspace 根 .gitignore 已含 `.nebflow/`（R6），本文件
  天然排除，各项目 repo 不加 `!.nebflow/memory.md` 豁免（显式决定，理由见
  .nebflow/Spec/project-memory.md §2.1）。
-->

## 使用纪律（初始化自带，勿删）

- 单行条目：每条一行 `- ` 开头；>500B 强制拆分——摘要一行，细节进 `→<id>` 详情文件（全局 `~/.nebflow/memory/<id>.md` 机制沿用）。
- 写入前三问：hard-to-obtain（git/一次 Read 可恢复的不记——commit hash、HEAD 链、目录结构）？reusable（一次性任务细节不记——某批页数、某次 PR 状态）？current-state-first（现状 update 旧条目，不新开；新口径推翻旧口径时 append 与 remove 成对执行）？
- 生命周期：T1 口径/决策 = 永久，被取代时同轮删旧条目（取代而非追加）；T2 状态/进展 = 事件闭环即删（批次收官/验收通过/阻塞解除），兜底 7 天；分级判定归条目自身内容，不归所在节。
- 预算：硬顶 10KB / 软警 8KB（写入侧 MemoryEdit 强制——MEMORYEDIT_BUDGET 拒绝 / MEMORYEDIT_BUDGET_WARN 放行提醒）；超限注入自动降级为头部+统计。
- 梦境清理对象声明：本文件**不是** Dream 抽取对象（Dream hook 只写全局 `~/.nebflow/User.md`）；若手工把 Dream 条目搬入项目记忆，按 T2/T3 同规清理，不享 T1 待遇。
- 维护入口：MemoryEdit `target=project:<项目名>`（append/update/remove/replace_section 四动作；禁止绕过工具直写——预算闸与条目纪律都在工具侧）。
- 注入语义：本文件自动注入【本项目】分发器 prompt 与全部节点首条消息；**不进** Nebula 全局注入（ContextRefresher 不含项目记忆）。Nebula 本人在场处理项目事务时按需 Read 本文件。

## 口径与决策（T1 永久）

（空——项目级技术口径、协作约定、被裁定过的方案取舍）

## 项目状态（T2 滚动，事件闭环即清）

（空——当前批次/在途节点/待验收清单；收官即清，教训晋升下一节）

## 运维教训（永久）

（空——批次收官时从「项目状态」晋升进来的可复用教训）
