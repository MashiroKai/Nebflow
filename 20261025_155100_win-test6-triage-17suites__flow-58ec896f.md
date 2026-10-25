# Windows(KAI) test6 失败分诊 · 任务 17 套件（25 失败）

证据源: KAI `/c/Users/Kai/stand-win-test6.clean.log` + `/c/Users/Kai/failed-events.txt`；源码本地 worktree `delegate-nebflow-5e24b2f7`（与 KAI 检出同版，抽查逐行一致）。只分类不修码。

## 每套件一行

MemoryRecallDaemon | nebflow.core.MemoryRecallDaemonSpec | 4条`IOException: Cannot run program "python3" CreateProcess error=2`（节流窗/脏探测/排除面+auto端到端60s超时全Throttled dbExists=false, MemoryRecallDaemon.scala:153 ProcessBuilder硬编码python3）；1条测试:315 `toString.endsWith("tools/stand-recall")` 对`C:\...`必假 | F:引擎python3加Windows回退(py -3/python)或环境装python3；A:测试:315改按`p.last`比较或归一分隔符

HealthGate | nebflow.core.hotrestart.HealthGateSpec | 3条`assert(jar.endsWith("versions/…/stand-assembly-….jar"))`失败（测试253/287/324；jarIn返回String路径=直接toString，Windows反斜杠） | A:测试侧endsWith前`replace('\\','/')`或比对os.Path段

CompactionAbandonFace | nebflow.agent.CompactionAbandonFaceSpec | 3条引擎自建reminder不被isCompactReminder识别（66/80/93 "not recognized: Left(<system-reminder>Context compaction required…"）；dropScratch不摘 | B:KAI整树CRLF检出（autocrlf）×CompactService.scala:53硬编码"\n"签名失配→恒false；仓根加.gitattributes eol=lf+重检出，或签名判据容\r

FullCompact | nebflow.core.compact.FullCompactSpec | preservedRounds obtained 2 expected 1（测试:195）；尾部reminder(buildCompactReminder产物,CRLF污染)未被FullCompact.scala:222 dropWhile摘除而计入保留轮 | B:同CompactionAbandonFace簇（CRLF×\n签名），同一修法

CompactUtilsOversized | nebflow.core.compact.CompactUtilsOversizedSpec | 测试:59 `contains("tool-results/sess-b1/call-1.txt")`失败；引擎CompactUtils.scala:268占位符用os.Path.toString拼→反斜杠 | A:引擎侧占位符路径统一'/'（模型可见文案，Windows兼容正斜杠）

UsageAggCache | nebflow.core.UsageAggCacheSpec | `UnsupportedOperationException` at WindowsFileSystemProvider.readAttributes（测试:352 Files.getPosixFilePermissions） | F:测试侧POSIX perms API Windows不支持；改DOS attr/ACL或非Windows跳过

LlmLogWriterStream | nebflow.core.LlmLogWriterStreamSpec | T2测试:122 `done.isAfter(mid)`失败（事件2→3无间隔，同毫秒时戳同格；LlmLogWriter.scala:322 Instant.now） | F:计时/批刷时序敏感（回归闸本意=防批刷时戳同格）；Done前加sleep或放宽为!isBefore；Mac绿=时序差异

TokenCommand | nebflow.cli.TokenCommandSpec | `UnsupportedOperationException` at WindowsFileSystemProvider.readAttributes（测试:92 Files.getPosixFilePermissions；引擎写文件已成功） | F:测试侧POSIX权限断言Windows不支持；非Windows跳过该断言

StatusCommand | nebflow.cli.StatusCommandSpec | expected exit 0 obtained 4（测试:165）；SingleInstanceGuard.scala:173白名单`Set("stand","stand",lowerName)`丢legacy"nebflow"→body product=nebflow判foreign | H:品牌迁移把legacy项覆写成重复"stand"（注释自称仍识别legacy），纯逻辑Mac同版必同败，需基线核对

PidMeta | nebflow.cli.PidMetaSpec | 测试:90 `cmdlineSha256.nonEmpty`失败；PidMeta.scala:170依赖ProcessHandle.info.commandLine，Windows(Temurin)恒空 | G:引擎已载此洞并建CIM兜底（WindowsProcs.scala:9-16,45）但PidMeta未接——selfCommandLine补WindowsProcs.commandLineViaCim(currentPid)

ConfigEditHeadless | nebflow.cli.ConfigEditHeadlessSpec | obtained文案`'stand config set'` vs 测试:16钉`'nebflow config set'`（引擎ConfigCommand.scala:180已改stand） | H:品牌迁移文案漂移，平台无关，需基线核对

SubAgentInboxMirror | nebflow.agent.SubAgentInboxMirrorSpec | 测试:360-368源码计数pin 13 vs obtained 15（本地实数AgentActor5+FinishTurn2+Frozen1+Idle4+Processing3=15） | H:源码文本计数，平台无关（引擎新增2调用点未re-pin），Mac同版必同败，需基线核对

EmptyShellNotify | nebflow.agent.EmptyShellNotifySpec | 测试:164钉`[delegate-judge] verdict: blocked`，obtained`verdict: completed.`+precheck注记 | H:引擎#566裁定blocked判词退役为completed+注记（DelegateTerminal.scala:350-357→CompletedNote），测试fixture未跟，平台无关，需基线核对

AgentActorCompaction | nebflow.agent.AgentActorCompactionSpec | 测试:860次末轮未存活；obtained尾部残留`<system-reminder>Context compaction required…`且preservedRounds=2 | B:同CompactionAbandonFace簇（CRLF×\n签名→reminder混入保留尾），同一修法

PluginInstallPathGuard | nebflow.core.plugin.PluginInstallPathGuardSpec | `FileSystemException …客户端没有所需的特权` at测试:121 Files.createSymbolicLink | F:Windows建symlink需管理员/开发者模式；测前探测能力或非Windows跳过

PluginMcpProtocol | nebflow.core.plugin.PluginMcpProtocolSpec | 测试:155 `cmd.endsWith("/bin/srv")`失败，got `C:\…\bin\srv`（startsWith(d.toString)已过） | A:测试侧正斜杠字面量；归一分隔符或比较最后一段

PluginManifestProtocol | nebflow.core.plugin.PluginManifestProtocolSpec | `FileSystemException …客户端没有所需的特权` at测试:221 os.symlink | F:同PluginInstallPathGuard（symlink特权）

## 簇归并

- B「CRLF工作树×\n字面量判据」 | CompactionAbandonFaceSpec(3), FullCompactSpec(1), AgentActorCompactionSpec(1) | 根因: 检出面（KAI全树CRLF：全局autocrlf；仓库blob实为i/lf、无.gitattributes——字节差仲裁：KAI各文件=本地LF字节数+行数）× 引擎侧判据（CompactService.scala:53 ReminderSignature硬编码\n；FullCompact.scala:222 dropWhile） | 修法: 仓根.gitattributes `*.scala text eol=lf` + KAI重检出(autocrlf=false)；可选引擎签名判据去\r容错。⚠️同机制可解释17套件之外更多失败——凡"源码字面量含换行 vs 硬编码\n"断言皆中，其余分诊节点优先排查
- A「路径分隔符」 | 测试侧断言型: HealthGateSpec(3), MemoryRecallDaemonSpec(1), PluginMcpProtocolSpec(1)；引擎侧文案型: CompactUtilsOversizedSpec(1) | 根因文件: 各Spec的endsWith/contains正斜杠字面量（测试侧）；CompactUtils.scala:268（引擎侧） | 修法: 断言/文案统一归一分隔符或按Path段比较
- F「环境/特权/计时」 | MemoryRecallDaemonSpec(4,python3缺失——引擎硬编码python3无py/python回退), TokenCommandSpec(1)+UsageAggCacheSpec(1,POSIX perms API不支持), PluginInstallPathGuardSpec(1)+PluginManifestProtocolSpec(1,symlink特权), LlmLogWriterStreamSpec(1,相邻事件同毫秒时戳) | 修法: 平台探测跳过或等价Windows手段；引擎python3加Windows回退；T2的Done前加间隔
- G「引擎Windows真缺陷」 | PidMetaSpec(1) | 根因: PidMeta.scala:170未接WindowsProcs CIM兜底（WindowsProcs.scala:9-16已载洞） | 修法: selfCommandLine补commandLineViaCim(currentPid)
- H「平台无关腐烂（需基线核对）」 | StatusCommandSpec(1)+ConfigEditHeadlessSpec(1,品牌迁移nebflow→stand: SingleInstanceGuard.scala:173 legacy白名单被覆写成重复"stand"疑迁移笔误+测试钉旧名)、EmptyShellNotifySpec(1,#566判词退役)、SubAgentInboxMirrorSpec(1,调用点pin 13→15) | 修法: 测试re-pin/更新fixture；Status引擎恢复legacy"nebflow"白名单项（与其注释自洽）

计数核对: 5+3+3+1+1+1+1+1+1+1+1+1+1+1+1+1+1 = 25 失败 / 17 套件，与任务清单一致。
