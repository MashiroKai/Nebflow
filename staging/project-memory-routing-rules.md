# 记忆管理规则节增补文本（project-memory 批 → Nebula 本人 MemoryEdit 应用）

- 应用方式：由 **Nebula 本人**以 MemoryEdit 应用到 `~/.nebflow/agents/Nebula/memory.md`
  的「记忆管理规则」节（与 memory-mech 批交付的节替换文本
  `staging/memory-md-rule-section-replacement.md` 合并应用，先后由 Nebula 裁量；
  本文件只含 project 维度的增补条目，不替代全局纪律）。**禁宿主直写**。
- 应用时机：宿主落地命令执行后（项目记忆机制已合入 + 模板已 cp）再应用，
  避免规则先行、机制未至。

## 增补条目（追加进「记忆管理规则」节，逐条独立 MemoryEdit append）

```
- 写入路由三层（2026-09-05）：产品决策/全局裁定/跨项目偏好 → MemoryEdit target=user；路由经验/技术教训/领域知识 → target=agent；项目状态/进展/口径/项目内教训 → target=project:<name>（<workspace>/.nebflow/memory.md，预算 10KB/8KB，四动作同纪律）
- 项目记忆注入边界（2026-09-05）：派发项目任务时该项目 memory.md 自动注入分发器与节点上下文；ContextRefresher 全局注入不含项目记忆——Nebula 本人在场处理项目事务按需 Read <workspace>/.nebflow/memory.md，不把项目细节复制进全局两级
- 项目记忆与全局记忆的晋升方向（2026-09-05）：nebflow 主仓等具体项目的任务状态/口径记该项目 memory.md；仅当教训跨项目通用时才升全局两级；反向（全局裁定的项目落地态）不回写项目记忆，项目记忆以现状为准
```

## 应用后校验

- `wc -c ~/.nebflow/agents/Nebula/memory.md` ≤ 30,720B（30KB 硬顶；超限先整理再应用）
- 三条均可在「记忆管理规则」节内 locate（MemoryEdit match 语义自检）
