# Windows ShellSession 探针阻塞修复（KAI 全量测试挂死根因）

- 日期：2026-10-25
- 现场：Windows / KAI 机，sbt 全量 `test`（test5），跑 ~10 分钟后整 JVM 焊死
- 证据：KAI 机 `/c/Users/Kai/stand/reports-kai/test5-hang-jstack-20261025.txt`（jstack 全文，2026-10-25 13:29:36 采样）
- 影响面：仅 Windows；Mac 因 `isWindows` 门跳过探测，四天零暴露

## 一、根因链（jstack 实锤）

```
sbtx-<n> 线程 × 2（stdout / stderr 各一）：
  java.io.FileInputStream.read(FileInputStream.java:287)      ← stdout 线程
  java.io.BufferedInputStream.read(BufferedInputStream.java:399) ← stderr 线程
  java.io.PushbackInputStream.read(PushbackInputStream.java:197)
  nebflow.core.tools.ShellSession.probeCharset(shell.scala:1230)   ← 埋死点
  nebflow.core.tools.ShellSession.readStream(shell.scala:1172)
  nebflow.core.tools.ShellSession.$anonfun$14(shell.scala:654)     ← stdout 读线程
  nebflow.core.tools.ShellSession.$anonfun$15(shell.scala:670)     ← stderr 读线程
  cats.effect.IOFiber.runLoop … WorkerThread.run
```

- `probeCharset`（GBK 检测，中文 Windows ANSI 码页支持新加）第一行
  `pushback.read(probe)` 是**裸阻塞读**：等满 4096 字节或管道 EOF 才返回。
- 触发条件：Windows 上「输出总量 < 4096 字节 ∧ 进程存活期长」——sbt 测试里
  spawn 的引擎场景全部命中：读线程永久埋死在探测行 → 会话永不完成 → 测试挂死。
- 该函数加入后未在 Windows 长会话下验证过；`isWindows` 门让 Mac 永不走进该
  路径，故 Mac 全量四天全绿、零暴露。

## 二、修法（零超时纪律：数据驱动的轮询窗，不设 wall-clock 判死）

`probeCharset` 改为**非阻塞轮询探测**（`src/main/scala/nebflow/core/tools/shell.scala`）：

1. **available() 驱动 + 25ms 轮询**（与 readStream 主体同款哲学）：有数据立即读，
   每个突发后重置静默时钟；首批字节后再等 **50ms 静默窗**确认突发完整，随后
   unread 回推原始字节、定码页。正常命令首批字节毫秒级到达 ⇒ 总探测成本 ≈ 一个
   静默窗，零新增时延。
2. **预算窗 500ms**：是「等数据的轮询上限」，不是超时杀——数据一到立即走；
   窗内零字节 → 按 UTF-8 放行兜底（Git Bash / PYTHONUTF8 场景本就是 UTF-8，
   静默长活进程不亏；读线程随后回 readStream 主循环继续轮询，数据来了照读）。
3. **isProcessExited() 纳入判据**：进程退了立即收尾定案，覆盖孤儿占管道类
   （旧实现里这类会话埋死到孤儿关管道为止，可能永不返回）。
4. **IOException 同款处理**：available()/read 抛 IOException（进程被
   destroyForcibly 关流等）时带着已攒字节定案，不炸穿读线程。
5. **边界（切半尾字）**：探测字节切片可能把多字节字符切一半——回推**原始字节**，
   调用方 InputStreamReader 从头解码拼整；另加**切半尾字容忍**：严格 UTF-8 解码
   在「尾部不完整序列」上报错时，识别尾部半字长度（末 3 字节内 lead byte 声明的
   序列长 > 实有字节数）回退重判，重判通过仍判 UTF-8（旧实现此处直接误判 GBK ⇒
   整流乱码）。GBK 误判风险（切片恰好全 ASCII ⇒ 判 UTF-8）知情接受：ASCII ⊂ GBK，
   两种码页解码逐字节相同，无害——其余知情接受项见源码 `probeCharset` 注释。
6. **可测性改造**：`isWindows` 门做成可注入开关
   `ShellSession.charsetProbeGateOverride`（companion `private[tools]`
   AtomicReference，None=跟随真实平台）；`probeCharset` / `readStream` 提为
   `private[tools]`。spec 在 **Mac 上**注入 `Some(true)` 即可跑真探测逻辑。

## 三、spec（Mac 上可跑，`src/test/scala/nebflow/core/tools/ShellSessionCharsetProbeSpec.scala`）

| # | 断言 | 实测（Mac，2026-10-25 13:48） | 变异验红点 |
|---|---|---|---|
| A1 | 永不出字流：probeCharset 预算窗内放行（UTF-8 兜底），不挂死 | 0.709s（≈预算窗 500ms + 轮询粒度） | 换回裸 read ⇒ 挂死超时 |
| A2 | 零输出+进程已退出：立即收尾（预算膨胀到 30s 仍 <5s 返回） | 0.004s | 删退出判据 ⇒ 30s 撞 deadline |
| B1 | 慢速出字(150ms) GBK：窗内到达仍判 GBK，回推字节逐字节完整 | 0.245s | 预算窗改判死 ⇒ 数据后到被弃 |
| B2 | 慢速出字(150ms) UTF-8：同上 | 0.219s | 同上 |
| B3 | 立即出字 GBK：既有检测语义回归 | 0.067s | — |
| C1 | 切片切半多字节字符（E4 BD｜A0+好）：判 UTF-8 不误判 GBK，全流回读拼出「你好」 | 0.205s | 删切半尾字容忍 ⇒ 误判 GBK |
| D1 | gate=Some(true)：Mac 上 GBK 流经 readStream 解码正确 | 0.272s | 门未接线 ⇒ 乱码 |
| D2 | gate=None（Mac 真实形态）：跳过探测，GBK 按 UTF-8 解码出 U+FFFD | 0.256s | 门失效 ⇒ 误探测 |
| D3 | readStream 端到端挂死形态（永不出字+已退出）：预算窗+grace 内返回空串 | 0.264s（≈500+250ms） | 换回裸 read ⇒ 挂死超时 |

合计 9/9 全绿（`Passed: Total 9, Failed 0`）。实现注记：A1/A2 的永不出字流
**不做 drain 断言**——该流 read() 设计为永久阻塞，readAllBytes 等 EOF 必埋死；
回归点是「探测本身在预算窗内返回」，回推完整性由 B/C 组带 EOF 的流断言承担。

## 四、KAI 侧验证步骤（Windows 重跑 test5 前的核对单）

1. **拉 bundle 重启实例**（本修复合并 main 后重打的 bundle；确认
   `nebflow/core/tools/shell.scala` 里存在 `charsetProbeGateOverride` 与
   `probeCharset` 的轮询实现，且不再有 `val n = pushback.read(probe)` 裸读行）。
2. **重跑全量**：`sbt test`（原挂死命令）。预期：不再出现 ~10 分钟整 JVM 焊死；
   `ShellSession` 相关 spec 全绿。
3. **若仍挂**：jstack 采样两份（间隔 30s）——
   `jstack <sbtJvmPid> > reports-kai/test6-hang-jstack-<ts>.txt`；
   若新栈里不再出现 `probeCharset → PushbackInputStream.read` 埋死链、而是停在别处，
   按新埋死点另立修复批（探测线已排除）。
4. **行为核对（Windows 真机，可选加验）**：Windows 上跑一条 GBK 输出命令
   （如 `cmd /c chcp 936 & echo 中文`）与一条 UTF-8 输出命令（`echo 中文`，
   Git Bash），确认两者在 Bash 工具输出里中文均不乱码——探测的非阻塞化不改变
   码页判定语义。
5. 验证通过后回填：在 KAI 机 `reports-kai/` 留一份重跑结论（test6 结果 + 若有
   jstack 附上），供主仓 `reports/` 归档。

## 五、改动清单

| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/core/tools/shell.scala` | `probeCharset` 非阻塞轮询化（+切半尾字容忍 `isStrictUtf8`/`trailingPartialLen`/`detectCharset`）；探测门接缝 `ShellSession.charsetProbeGateOverride` + `charsetProbeEnabled`；`readStream`/`probeCharset` 提为 `private[tools]`；`readStream` 探测入参补传 `isProcessExited` |
| `src/test/scala/nebflow/core/tools/ShellSessionCharsetProbeSpec.scala` | 新增 9 个 spec（合成流，Mac 可跑），见 §三 |
