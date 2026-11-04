# M3 GREEN（改后）— sbt testOnly ShellOutputSpillSpec，2026-11-04 13:44 两轮复跑

改后实现: `OutputSpillBuffer`（内存只留首 64KB + 尾 64KB，全量溢写 `<dataRoot>/shell-spill/`）。

实测（同一 100MB 合成输出，收敛读数 = 多轮 GC 取最小 + 相邻两次差 <128KB）:
  [M3-evidence] heapBefore=295473KB heapAfter=295610KB heapDelta=137KB outLen=131271 spilledBytes=104857600
  [M3-evidence] heapBefore=213187KB heapAfter=213321KB heapDelta=134KB outLen=131270 spilledBytes=104857600
  → heapDelta ≈ 134-137KB ≤ 1024KB（绿）；可见内容 131270B ≈ 128KB；溢写 104857600B = 100MB 全量落盘

读数方法（如实记）: 首版单次 System.gc() 读数被测试 JVM 内 munit / cats-effect 计算池的
持续分配带偏过两次（1110KB / 1527KB 假红）——真值数百 KB，与阈值 1MB 同量级。改法:
1MB 预热 + 多轮 GC 取最小 + 相邻收敛。改前 13027KB 与改后 137KB 差 100 倍，不受此噪声影响。

第三次（合并 main 后 3-spec 端到端回归）: heapDelta=128KB，19/19 用例全绿（见 post-merge-regression.txt）。

其余断言全绿: 8MB 输出可见 ≤256KB + 截断注记 + 溢写存在；小输出原文不变且不落盘。
回归: ShellOutputSpillSpec + ShellSessionCharsetProbeSpec + BashToolSpec 全绿。
