# M3 RED（改前）— sbt testOnly ShellOutputSpillSpec，2026-11-04 13:26:27

改前实现: `shell.scala:510 MaxOutputSize = 10MB` + readStream 的 StringBuilder（每流 10MB）。

munit 实测（100MB 合成输出）:
  heapBefore=269629KB heapAfter=282656KB heapDelta=13027KB outLen=10485760
  → 断言 heapDelta ≤ 1024KB **失败**（红）: 堆增长 13027KB = 12.7MB

8MB 输出用例: outLen=8388608（8MB 全量驻留内存）→ 断言 ≤256KB **失败**（红）

截断语义（改前）: 超过 10MB 直接丢弃并只留 "[Output truncated due to size limit]"，
全量**不可回看**。
