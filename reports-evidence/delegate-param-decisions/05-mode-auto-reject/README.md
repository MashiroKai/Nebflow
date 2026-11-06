# 05 · 显式 `mode="auto"` ⇒ 明确报错（不静默映射）

判据：`auto` 以前是缺省值（走 JeV → LLM → fail-open kernel 三态链）；现在**值域里没有 auto**，
显式传 `auto` 必须**报错**——静默映射成 kernel 会让「分配器有没有真的删掉」无法验证。

## 命令原文

同 `01-` 的 sbt 命令。

## 原始输出（`raw.log`）

```
nebflow.core.delegate.ModeParamSpec:
  + P1 纯函数形态：resolve 返回 Either（无 IO / 无端口）——零模型调用是类型级保证 0.003s
  + P2 不填 mode ⇒ 确定性 kernel（leg=default，理由里写明 zero model calls） 0.001s
  + P2b 显式 kernel/dispatcher 原样采纳（大小写/空白容忍，leg=param） 0.0s
  + P3 显式 auto ⇒ 明确报错（含引导语），绝不静默映射成 kernel 0.001s
  + P3b 值域外的词 ⇒ 显式报错（enum 单点来自 DualModeConfig.ModeWords） 0.001s
  + P4 旧双模式总闸配置已退役：nebflow.json 里怎么写都不改变解析结果 0.001s
  + P5 值域词表与 schema enum 同源（ModeWords = kernel|dispatcher，无 auto） 0.001s

nebflow.agent.DualModeGrayReleaseSpec:
  + GR1 盘上 mode 直读：没有闸门把 kernel/dispatcher 藏起来（显式选择永不被静默吞掉） 0.002s
  + GR2 不填 mode ⇒ 确定性 kernel（leg=default），auto 被显式拒绝 0.0s
  + GR3 全链形态一（dispatcher）：参数给 dispatcher ⇒ 读面 ⇒ 面 fork ⇒ Subagent 工具内拒 0.003s
  + GR4 全链形态二（kernel）：不填 mode ⇒ kernel ⇒ 编排面整体退场 0.002s
  + GR5 分配器已删除：auto 在参数解析点就报错（引导语在文案里），不存在任何模型判定兜底 0.001s
```

## 报错原文（`DualMode.scala:65` `ModeParam.AutoRemoved`）

```
Delegate: mode="auto" is no longer accepted — the mode allocator has been removed, so the execution form is YOUR decision, not a model's. Pass mode="kernel" or mode="dispatcher" directly. （请直接指定 kernel 或 dispatcher，分配器已移除）(DELEGATE_MODE_AUTO_REMOVED)
```

值域外的词（P3b）：

```
Delegate: invalid mode 'agentflow' — enum kernel|dispatcher (default kernel). There is no allocator: you pick the execution form yourself. (DELEGATE_MODE_INVALID)
```

## 附带：工具 schema 的 enum 已收缩

`Delegate` 工具 `mode` 的 `enum` 从 `["kernel","dispatcher","auto"]` 改为 `["kernel","dispatcher"]`
（`DelegateToolSpec` 的 schema 断言 + `ModeParamSpec` P5 同源断言双重钉住）。
