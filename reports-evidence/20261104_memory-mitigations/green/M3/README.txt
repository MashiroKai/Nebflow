# M3 GREEN（改后）— sbt testOnly ShellOutputSpillSpec，2026-11-04 13:31:5x

改后实现: `OutputSpillBuffer`（内存只留首 64KB + 尾 64KB，全量溢写 `<dataRoot>/shell-spill/`）。

munit 实测（同一 100MB 合成输出）:
  [M3-evidence] heapBefore=297263KB heapAfter=297763KB heapDelta=499KB outLen=131270 spilledBytes=104857600
  → heapDelta = 499KB ≤ 1024KB（绿）；可见内容 131270B ≈ 128KB；溢写 104857600B = 100MB 全量落盘

其余断言全绿:
  - 8MB 输出: 可见 ≤256KB + 截断注记 + 溢写文件存在
  - 小输出（<64KB）: 原文不变、不落盘（零副作用）

回归: ShellSessionCharsetProbeSpec + BashToolSpec + ShellOutputSpillSpec 三 spec 共 19 用例全绿。
