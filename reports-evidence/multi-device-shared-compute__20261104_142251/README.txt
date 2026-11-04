# 20261104_multi-device-shared-compute — 多设备共享算力设计件取证

配套 vault 设计件: /Users/kaiyu/.stand/projects/stand/.stand/docs/20261104_142000_multi-device-shared-compute__delegate-nebflow-01df9dc9.md
采集会话: delegate-nebflow-01df9dc9, 2026-11-04 14:04–14:19 CST
零源码改动: 本目录只有 readings/*.txt 文本读数 + 本 README。

## readings/
- local-device.txt       本机 OS/CPU/内存/盘/工具链/worktree 数/load/重 JVM（live probe 14:16）
- kai-device.txt         KAI 同项（Bash device=KAI live probe 14:06–14:08；工具链 ABSENT 项为实测 command not found）
- presence-dial-today.txt stand.log 今日严格过滤读数（行首 'ts [thread]' 才算真引擎行）+ 分时 + relay 隧道事件（14:17 采集）
- relay-latency-samples.txt 引擎自测 elapsedMs：device=KAI vs device=local 同形命令，relay 附加时延 ≈1.5s（源 tools/2026-11-04.jsonl 本会话 61 条）
- sbt-gate-today.txt     sbt-gate 闸日志 55 次 release 记账的时长分布/按 worktree 汇总/SBT_GATE=off 旁路 7 次
- memsample-window.txt   memsample 12 列窗口（13:59–14:18）+ 今日极值（compressed 峰 7120.1MB、load1 峰 20.02）

## 引用的既有批（未复制，按路径引用）
- reports-evidence/20261104_kai-probe-tax/{red,green}/  relay RTT 43ms、冷开 5051→1663.5ms、5MiB 搬运 4.58×、拨号占 io-compute-blocker 实录
- reports-evidence/20261104_nearpath-fire/red/FINDINGS.md  日志回显污染教训（§6.1）、
- reports-evidence/20261104_memory-mitigations/green/M1/  sbt 空转地板 3s、闸自测
- vault: 20261104_140600_presence-dial-overhaul.md（在飞未合入，main 无 PresenceDialPool.scala）
- vault: 20261104_130303_memory-audit-and-management.md（28s 增量编译、120 次 sbt/6 worktree）
