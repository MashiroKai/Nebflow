# 04 · 零模型调用（硬判据：不留开关）

判据：spawn 路径上**不存在**任何模型调用点——座位分配与模式解析都是纯函数；判官面（唯一的模型缝）
跑完全部腿后计数必须为 **0**。

## 命令原文

同 `01-` 的 sbt 命令。

## 原始输出（`raw.log`）

```
nebflow.core.delegate.DelegateWorkspaceWorktreeLegSpec:
  + W4 零模型调用：装计数判官面，跑完零分配腿 + 按名建树腿 + 模式解析后计数仍为 0 0.334s
nebflow.core.delegate.ModeParamSpec:
  + P1 纯函数形态：resolve 返回 Either（无 IO / 无端口）——零模型调用是类型级保证 0.003s
  + P2 不填 mode ⇒ 确定性 kernel（leg=default，理由里写明 zero model calls） 0.001s
  + P2b 显式 kernel/dispatcher 原样采纳（大小写/空白容忍，leg=param） 0.0s
  + P3 显式 auto ⇒ 明确报错（含引导语），绝不静默映射成 kernel 0.001s
  + P3b 值域外的词 ⇒ 显式报错（enum 单点来自 DualModeConfig.ModeWords） 0.001s
  + P4 旧双模式总闸配置已退役：nebflow.json 里怎么写都不改变解析结果 0.001s
  + P5 值域词表与 schema enum 同源（ModeWords = kernel|dispatcher，无 auto） 0.001s
```

## 三条独立的机械判据

1. **运行期计数**：`DelegateWorkspaceWorktreeLegSpec` W4 装一个计数 `DelegateJudgePort.Face`
   （`judgeTerminal` 自增），跑完 `prepareInPlace` + `createNamed` + `reuse` + `ModeParam.resolve` 后
   `counter == 0`；
2. **类型级**：`ModeParam.resolve` 返回 `Either[String, ModeDecision]`——签名里没有 `IO`、没有端口、
   没有配置读取；`ModeParamSpec` P1 用类型注解承重（改成 `IO[...]` 直接编译失败）；
3. **符号级**：`08-deletion-points/deletion-grep.txt` 证明 `DelegateModeAllocator` / `DelegateModeMachine`
   / `decideWorktree` 等已无任何代码引用（只剩注释）。

`ModeParam.AutoRemoved` 原文：

```
Delegate: mode="auto" is no longer accepted — the mode allocator has been removed, so the execution form is YOUR decision, not a model's. Pass mode="kernel" or mode="dispatcher" directly. （请直接指定 kernel 或 dispatcher，分配器已移除）(DELEGATE_MODE_AUTO_REMOVED)
```
