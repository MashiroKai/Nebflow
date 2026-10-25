# Windows test6 全量失败分诊台账 + 机械类修复批

- 日期：2026-10-25
- 现场：KAI（Windows）`sbt test`（test6，挂死修复后首个完整跑）：Total 7217 / Passed 6807 / **Failed 361** / Skipped 49 / Ignored 14，失败散布 **134 套件**。日志 `KAI:/c/Users/Kai/stand-win-test6.log`（剥色版 `stand-win-test6.clean.log`；逐条失败事件摘要 `failed-events.txt`；按套件聚合 `x-per-suite.txt`）。
- 对照基线：Mac CI（main）7295 总量 31 失败 = 预存腐烂（H 类）。**基线 31 的套件级清单未随任务下发**——H 类判定以「平台无关必同败 + Mac 对照探针实测」为准，逐条标注"需基线核对"。
- 分诊方式：8 个并行读码分诊组（secrets/social/neblink/gateway/tools/project/misc/silent38）逐套件取证（KAI 日志栈帧 × 双端源码），本报告为归并台账。

## 一、日志工件质量结论（分诊前提）

1. **Windows 日志有丢块（run-level 输出通道异常）**：`+` 通过行仅 4838 < Passed 6807（缺 ~2000 行）；`==> X` 事件 249 < 361（缺 ~112 条）。**38 个套件在日志中零痕迹**。triage-silent 取证：38 件在失败清单中恰为**严格字典序前缀**（#1–38），#39 起与日志完成顺序逐一吻合 ⇒ 38 件全部完成于首条可见失败（14:27:40）之前的 **11 分钟零字节静默窗**（14:16:33–14:27:35）；日志头缺 sbt 正常 welcome/loading 行、以 **sbt 命名管道锁失败**开场（`Could not create lock for \\.\pipe\sbt-load…_lock, error 5`）。Mac 同栈实验证明构造期失败必有可见 `initializationError` 事件 ⇒ 零痕迹 ≠ 套件自杀，= **该时间窗内输出未达日志文件**。源码总审：38 件中 0 件含 System.exit/halt/退出钩子模式。
2. Mac 对照探针（本 worktree，37 静默套件 `testOnly`，2026-10-25 15:34）：**34/37 Mac 全绿**；仅 3 套件 5 例失败（CliRouterSpec A4/C1、SingleInstanceGuardSpec ×2、UserTextGateSpec ×2）→ H 嫌疑实锤组。
3. **日志中文名乱码（C 类观测线索）**：部分 `==> X` 行中文呈 GBK 双重编码（SavedPathLandedReadbackSpec 用例名、ExplorerDotDir 的「客户端没有所需的特权」）——reporter/异常链路有一处走平台默认码页。属观测性缺陷，另案不入本批。

## 二、分类统计（134 套件）

| 处置 | 套件数 | 说明 |
|---|---|---|
| **已修·预期转绿或 skip** | 66 | A/B/C/D/F 机械修（详见 §四），Mac 回归全绿为闸 |
| **已修·部分（G/H 腿仍红）** | 7 | SecretsRoutesSpec、SecretsStoreSpec、NfPathEncodingProbeSpec、NfTicketRoutesSpec、DataRootPlaceholderSpec、MultiEditToolSpec、ChainArchivedEventSpec |
| **G 立案不修（引擎专项）** | 8 | §五 G-1…G-7（7 个独立根因，8 套件） |
| **H 不动（腐烂治理批）** | 11 | §六（含 ProviderCommandSpec 品牌迁移） |
| **F-pending 专项/观察** | 5 | §七（NodeSeatCwd、DeviceProjectMount、NodeBgReclaim、NoderptVerifyProbe、NodeAcceptance） |
| **治理批（CRLF renormalize）** | 1 | DocIndexConsumerSpec（字节保真 roundtrip × CRLF 检出） |
| **S 待 KAI 单跑取证** | 36 | 静默窗零痕迹套件中未预修者（§三.9），单跑后按实际失败内容二次归类 |

（交叉核对：66+7+8+11+5+1+36 = 134 ✓；按类分布 A≈22、B≈12、C≈2、D≈45、F≈14、G≈37 例、H≈13、S≈112 例——以台账行=A/B/C/D/F/G/H/S 主类为准。）

## 三、逐套件台账（134 行）

格式：套件 | 类(例数) | 证据（异常+文件:行） | 处置。✅=本批已修；🟥=G 立案；⬛=H 不动；⏳=待 KAI 取证/专项。

### 3.1 secrets 簇（triage-secrets）

| 套件 | 类(例) | 证据 | 处置 |
|---|---|---|---|
| SecretsCatalogSpec | G(12) | `store.create().isRight` 全假；`SecretsStore.fileIsTight`(SecretsStore.scala:568-572) Windows 无 POSIX 视图 `catch Throwable => false` → postVerify(:521) 删档拒存 | 🟥 G-1 |
| SecretsRoutesSpec | G(13)+D(5) | 13 例夹具 POST 得 403=SecretMode→Forbidden(SecretsRoutes.scala:160-161) 同 fileIsTight；D2/D3/D4/V2/V3 夹具 `os.perms.set`→UOE(:274/:307/:331/:528/:561) | ✅D(fixtureTight 7 处收口)；🟥G-1 |
| SecretsStoreSpec | G(4)+D(1) | P1/P3/P4/V2 同上(:378/:403/:411/:534)；N4 夹具 UOE(:479) | ✅D；🟥G-1 |
| AskUserSecretSinkSpec | G(8) | `AskUserSecretSink.fileIsTight`(:234-238) 同型复刻件（writeThrough 为 private 有意复制） | 🟥 G-1（两份同步改） |
| SecretInjectionSpec | D(4) | 夹具 writeSecret(:48) `os.perms.set`→UOE(Files.java:2169) | ✅ |

### 3.2 social 簇（triage-social）

| 套件 | 类(例) | 证据 | 处置 |
|---|---|---|---|
| WeixinIlinkScanBindSpec | A(9) | 全例 TimeoutException:10s；done 腿 SocialChannels.save:534→probeOf:316→resolveRootRef 抛 InvalidPathException（paths.scala:291 兜底拼出 `root/C:/…` 双路径；:287 tokenPrefixes 未归一、:290 只认 `/` 开头） | ✅ 引擎单点修 paths.scala:287-291（归一比对+盘符绝对判定+SubPath 构造） |
| WeixinIlinkBridgeSpec | A(4) | 同上双路径 InvalidPathException（:455→save:534） | ✅ 同上单点 |
| FeishuScanBindSpec | A(3) | 同上（persistAndFinish:267 同链路） | ✅ 同上单点 |
| SocialChannelsBootSealSpec | A(1) | saved()(:75)→save:534 同链路 | ✅ 同上单点 |
| FeishuChannelSpec | G(5) | 夹具 os.write 带 POSIX perm→UOE（:175/:225/:249/:269/:283）；引擎 CredentialFileAcl 已有 Windows 分支非引擎缺陷 | ✅ posixPerms() helper 6 处收口 |
| WeixinOutboundSpec | A(1)+G(1) | WO-K1:180 `Files.getPosixFilePermissions` 无守卫；WO-W10: WeixinIlinkBridgePlugin.scala:1636 `split('/')` 对反斜杠不切 | ✅K1 assume；✅W10 引擎归一后 split |
| InboundParseSpec | B(1) | IP-8: KAI 检出 InboundParse.scala 为 CRLF，三引号字面量(:253-257)编译含 `\r\n`（常量池实测），消费方 `\n` 契约失配 | ✅ 引擎 :253 改显式 `"\n"` 拼接 |

### 3.3 neblink/bridge/shared 簇（triage-neblink）

| 套件 | 类(例) | 证据 | 处置 |
|---|---|---|---|
| DeviceFaceHardeningSpec | D(7) | 全例 beforeEach UOE（DeviceFaceHardeningSpec.scala:49 setPosixFilePermissions）；A6 腿另有 POSIX 读回 | ✅ beforeEach 守卫 + posixOnly() 门 3 条 A6 腿 |
| NeblinkSecuritySpec | A(5) | helper(:134-135) `Paths.get().normalize.toString` 出反斜杠、`/etc/passwd` Windows 非 absolute | ✅ helper 归一 + 前导 `/` 契约判 |
| NeblinkPresenceDialBudgetSpec | F(5) | 黑洞夹具=127.0.0.2/.3（:114），Windows 环回全段转 ECONNREFUSED ⇒ 轴漂移 | ✅ T1/T2/T3 timeout 轴组加 Windows 平台门（assume→ignored；TEST-NET-1 方案 Mac 实测每拍 +1s 撑爆预算断言，已回退） |
| EgressWhitelistSpec | A(1) | 夹具(:66-69) `os.Path` 反斜杠串裸插 JSON → `\U` 非法转义 → circe 解析失败 → 引擎宽容解码 Nil | ✅ 路径归一后插值 |
| DataRootPlaceholderSpec | A(2)+F/H(1) | :38 `os.Path("/tmp/…")` 解析成 `C:\tmp\…`；:183 data.sh 反斜杠值裸拼 JSON；:226 期望 8080 实得 6138=NEBFLOW_GATEWAY_PORT（KAI 环境带新舰队默认） | ✅:38 os.temp.dir、✅:183 转义；⬛:226 腿 H（环境依赖断言，不动） |
| BridgeOriginSpec | B(1) | :43 `startsWith("<system-reminder>\n")` 失败；引擎 marker=BridgePlugin.scala:189 stripMargin 字面量 CRLF 编入 | ✅ 引擎 marker 显式 `\n` 拼接 |
| DeviceProvenanceSpec | B(2) | :136/:142 同型（DeviceProvenance.scala:147） | ✅ 同上 |

### 3.4 dropbox/gateway 簇（triage-gateway）

| 套件 | 类(例) | 证据 | 处置 |
|---|---|---|---|
| SavedPathLandedReadbackSpec | G(7) | 全例 TARGET_DIR_INVALID——TargetDirGuard.scala:168 契约词法层拒 Windows 盘符/UNC（**按设计**）⇒ offer 全拒 | 🟥 G-2（契约 Windows 臂） |
| TargetDirNoSideEffectSpec | G(3) | DropboxService.scala:2064 `landingDirFor` 仅认 `startsWith("/")` → Windows 一律回落 downloadsDir | 🟥 G-2 同簇 |
| TargetDirGuardSpec | F(1) | beforeAll(:36) setPosixFilePermissions UOE（:40 symlink 同险） | ✅ posixViewAvailable 守卫 + #15/#17 assume |
| NfPathEncodingProbeSpec | A(6)+G(1)+F(1) | A-F 腿 `URI.create: Illegal character in query`（raw 反斜杠/空格进 query，:128 等）；G 腿=引擎 inode 快照空（G-3）；H 腿=symlink 特权 | ✅A(forms/wirePath 归一)；🟥G-3；✅H assumeSymlinks |
| NfServeGateSpec | A(4) | `Uri.unsafeFromString` ParseFailure——path 未编码（:72 fileUrl） | ✅ 归一正斜杠 |
| DaemonPanelRoutesSpec | G(4) | F2/F4/F5/F9 PUT 得 400——DaemonPanelStore.scala:257-270 dirIsTight/fileIsTight 要求精确 POSIX 0700/0600，NTFS 合成权限永不满足 ⇒ fail-closed 拒写 | 🟥 G-1 |
| ExplorerDotDirDanglingSymlinkSpec | F(7) | `FileSystemException …客户端没有所需的特权`（ERROR_1314）@scaffoldNebflowDir:39/:139 | ✅ SymlinkProbe 能力门 |
| ProjectAgentFileRoutesSpec | F(3) | 同特权 @mkRelativeAgentsSymlink:92 | ✅ 同上 |
| NfTicketRoutesSpec | F(1)+G(1) | A9 特权 @:247；A10:265 期望 403 credential-hardlink 得 200——NfFilePolicy.scala:215 `inodeKey` 依赖 `fileKey()`，NTFS 恒 null ⇒ R2 硬链闸失效 | ✅A9；🟥G-3 |
| MovePathSpec | F(1) | 特权 @:82 | ✅ |
| PasteAttachmentGuardSpec | B(1) | :58 源码 indexOf `\n` 判据 × CRLF 检出（`then\r\n` 不匹配） | ✅ 读源后归一 LF |
| HealthRoutesSpec | H(1) | :185 期望 port=8080 得 6138=Defaults.GatewayPort（Defaults.scala:1579）——断言钉死旧默认，平台无关 | ⬛ H（需基线核对） |
| BgRemoteOutputRoutesSpec | H(1) | :187 RemoteExecutor.current 进程级单例（RemoteExecutor.scala:1537）被先行套件污染 ⇒ 依赖 suite 顺序 | ⬛ H |
| PickerBrowseFrameSpec | A(1) | :250 `home+"/Downloads"` 斜杠拼接 vs 原生分隔符 | ✅ 分隔符无关比较 |

### 3.5 tools 簇（triage-tools）

| 套件 | 类(例) | 证据 | 处置 |
|---|---|---|---|
| BashToolSpec | A(3) | :125/:148/:164 /tmp 字面规范化 + MSYS pwd 形态（`/c/…` vs `C:\…`）进断言 | ✅ normPath 归一比较 + tmpdir 绝对 |
| MultiEditToolSpec | A(2)+F(1) | :243 /tmp 非绝对触发绝对闸、:261 summarize 反斜杠；:160 externallyModified=false 系 Windows mtime 粒度内双写碰撞 | ✅:243/:261；⏳:160 KAI 复跑钉 |
| ToolLoaderSpec | G(2) | 引擎把 Windows 路径内插 `sh -c` 命令文本（ToolLoader.scala:180），bash 把 `\` 当转义全吞；:342 混合分隔符（轻） | 🟥 G-5 |
| AskUserQuestionToolSpec | B(3) | :262/:279/:302 CRLF 检出 stripMargin 期望串带 `\r` vs 引擎显式 `\n` | ✅ lf() 归一 |
| BashBackgroundHardTimeoutSpec | F(2) | :68/:91 命令 `python3` 在 KAI 不存在（CreateProcess error=2） | ✅ pythonCmd 回退（python3→python→py） |
| AskUserDualModeToolSpec | F(3) | /dev/null 非字符设备（Windows 分支不同）+ createSymbolicLink 特权 | ✅ /dev/null 腿 POSIX 门 + symlink 门 |
| PopToolFileRefSpec | A(2) | FileRefs.describe(:348) normalize.toString 反斜杠 vs 测试 `/` 拼（:104/:117） | ✅ 引擎 describe 归一 + 测试期望归一 |
| TaskListHistorySpec | F(1) | :84 助手 `os.proc("shasum")` macOS 专有 | ✅ MessageDigest 平台无关实现 |
| ShellSessionCharsetProbeSpec | C(1) | D2 腿：gate None 在 Windows ⇒ 探测开启 ⇒ GBK 正确解码，测试写死 Mac「必乱码」形态 | ✅ D2 腿 POSIX 平台门（Windows 原生即 D1 语义） |
| SendMessageAttachSpec | A(1) | `/tmp/a.bin` Windows 非绝对 ⇒ 绝对路径闸先于「服务缺席」报错 | ✅ tmpdir 绝对路径 |
| RgHelperSpec | A(1) | :190 Windows rg 输出 `.\` 前缀+反斜杠，测试只 strip `./` | ✅ 双前缀剥除+归一 |
| PopToolSpec | A(1) | :330 手拼 JSON 串未转义反斜杠 ⇒ 非法 JSON ⇒ guard 拒 | ✅ jsonEsc 转义 |
| PointerPickupSpec | A(1) | :46 f.toString 反斜杠 vs contains("uploads/pickups/…") | ✅ 归一 contains |
| MailModelRetiredSpec | B(1) | MailTool.scala:539-545 字面子串投影 fail-closed：base CRLF × 显式 `\n` 段常量失配 ⇒ kernel 段泄漏 dispatcher 面 | ✅ 引擎投影 LF 归一副本（段未命中仍 fail-closed 原文） |
| GrepToolRankedSpec | A(1) | :79 盘符 `C:` 冒号触雷「纯路径行不得含:」 | ✅ 归一+剥盘符后断言 |
| FileRefsWhitelistSpec | A(1) | 夹具 root=`Paths.get("/tmp/…")` 有根非绝对 ⇒ startsWith 根组件失配 ⇒ 种子层死 | ✅ createTempDirectory 真绝对 |
| FileDiffToolLayerSpec | A(1) | FileChangeReport.relativize(:408) 缺归一（引擎展示层） | ✅ 引擎 relativize 归一 |
| DelegateDeviceRoutingSpec | A(1) | :46 endsWith("/general") vs 反斜杠 | ✅ 归一 |
| BashActivityBridgeSpec | F(1) | :148 python3 缺失 ⇒ 活性桥永不置戳 | ✅ pythonCmd 回退 |

### 3.6 project/delegate 簇（triage-project + silent 交叉）

| 套件 | 类(例) | 证据 | 处置 |
|---|---|---|---|
| DocIndexConsumerSpec | B(6) | 三引号字面量 `\r` ⇒ `BlockHeadingRe=^###\s+.*$` 不匹配（C③ 纯函数也败=铁证）；伴生 os.read GBK 风险 | ⏳ 治理批（.gitattributes eol=lf + renormalize；字节保真 roundtrip 不宜引擎侧容错） |
| PluginMcpManagerSpec | F(6) | fixture server 用 `python3`，KAI 无 | ✅ pythonCmd 回退 |
| DelegateWorkspaceWorktreeLegSpec | D(4) | mkRepo `os.remove.all` 撞上一轮只读 `.git/objects`（git 444 对象，Windows 删前须清属性） | ✅ ForceRemove 全簇 |
| GitWorktreesSpec | D(5) | 同上 | ✅ |
| NodeSeatCwdSpec | A(2) | MSYS bash `pwd` 输出 `/c/...`，断言期望 `C:\...` 逐字 | ⏳ F-pending（shell cwd 形态契约需真机定版） |
| NodeBgReclaimSpec | F(2) | `echo $$` 落 MSYS pid，`ProcessHandle.of` 查无此进程 ⇒ 探活恒 false | ⏳ 专项（探活改 kill -0 引擎面） |
| NoderptVerifyProbeSpec | F(2) | 同上 | ⏳ 同上 |
| DeviceProjectMountSpec | F(2) | 「宿主非 Windows」前提不成立：winPath 本机真实存在故挂载成功 | ⏳ F-pending（前提重构） |
| ChainArchivedEventSpec | B(1)+D(1) | TtlTick=CRLF 字面量同簇；容错例 setPosixFilePermissions UOE | ✅容错腿门；⏳TtlTick 腿归治理批 |
| WorktreeParamSpec | F(2) | symlink 特权 | ✅（ForceRemove 簇外单独——见 §四 文件表） |
| TaskStoreAtomicWriteSpec | D(2) | AtomicJson.scala:41 Files.move 并发句柄 AccessDenied | ✅ 引擎 move 退避重试（AccessDenied 3 次 50/150ms） |
| NodeBgCompletionGateSpec | G(1) | MergeWindowCare.scala:54 actorSystem=null 无守卫（残留队列态触发） | 🟥 G-6 |
| NodeChainAttributionSpec | B(1) | `replace("---\n")` 未命中（字面量实为 `\r\n`） | ✅ replaceAll CRLF 两拼 |
| NodeSchemaSlimSpec | D(1) | git-fixture 簇（构造期 remove.all 撞只读残骸） | ✅ ForceRemove |
| PluginInjectionSpec | F(1) | NTFS 大小写不敏感，`Plugin.md→plugin.md` 仅大小写改名不成立 | ✅ assume 平台门 |
| FileChangeReportSpec | A(1) | 引擎 relativize 输出 `sub\two.txt` 未归一 | ✅ 引擎归一 |
| MergeQueueVisibilitySpec | D(1) | initializationError AccessDenied `.git/objects` | ✅ ForceRemove |
| AbandonDetachSpec | D(1) | 同上 | ✅ |
| NodeAcceptanceSpec | D(1) | DirectoryNotEmptyException logs/router（句柄残留） | ✅ ForceRemove（若仍红=残留点另查） |
| MergeQueuePositionSpec | D(1) | 构造器崩溃无事件（同簇推断） | ✅ ForceRemove（KAI 单跑实证） |
| MergeAcceptanceSpec | D(1) | initializationError 同簇 | ✅ |
| DelegateAutoMergeSpec | D(1) | 同上 | ✅ |
| DelegateTerminalSpec | D(1) | 同上 | ✅ |
| MergeQueueRestartReminderSpec | D(1) | 同上 | ✅ |
| NodeMergeSpec | D(1) | 同上（AccessDenied ws-s1/.git/objects） | ✅ |
| WatchdogSelfMonitorSpec | H(1) | scan→halt.tryGet 无等待 L1 竞态，平台无关 | ⬛ H（需基线核对） |

### 3.7 agent/cli/compact/misc 簇（triage-misc + gateway 交叉）

| 套件 | 类(例) | 证据 | 处置 |
|---|---|---|---|
| MemoryRecallDaemonSpec | F(4)+A(1) | `Cannot run program "python3"`（MemoryRecallDaemon.scala:153 硬编码）+ :315 endsWith 反斜杠 | ✅ 引擎 pythonCmd 回退 + 断言归一 |
| HealthGateSpec | A(3) | :253/:287/:295/:324 `endsWith("versions/…")` × jarIn 原生分隔符 | ✅ 归一后 endsWith |
| CompactionAbandonFaceSpec | B(3) | KAI 整树 CRLF × CompactService.scala:53 硬编码 `\n` 签名 ⇒ isCompactReminder 恒 false ⇒ dropScratch 不摘 | ✅ 引擎签名判据 CRLF 容错（take+剥 \r） |
| FullCompactSpec | B(1) | preservedRounds 2≠1——尾部 reminder CRLF 污染未摘（FullCompact.scala:222 dropWhile） | ✅ 同上单点联动 |
| CompactUtilsOversizedSpec | A(1) | CompactUtils.scala:268 占位符 os.Path.toString 反斜杠（模型可见文案） | ✅ 引擎归一 |
| UsageAggCacheSpec | F(1) | :352 POSIX perms API Windows 不支持 | ✅ posix 视图 assume |
| LlmLogWriterStreamSpec | F(1) | T2 :122 done.isAfter(mid) 同毫秒同格（批刷语义合法） | ✅ 放宽 !isBefore（单调不回拨） |
| TokenCommandSpec | F(1) | :92 POSIX 权限读回 UOE | ✅ assume |
| StatusCommandSpec | H(1) | :165 SingleInstanceGuard.scala:173 白名单 `Set("stand","stand",…)` 覆写 legacy "nebflow"（注释自称仍识别）——平台无关必同败 | ⬛ H（**疑真回归**，建议腐烂批优先核） |
| ConfigEditHeadlessSpec | H(1) | obtained `'stand config set'` vs :16 钉 `'nebflow config set'`——品牌迁移文案漂移 | ⬛ H |
| SubAgentInboxMirrorSpec | H(1) | :360-368 源码计数 pin 13 vs 实数 15（新增 2 调用点未 re-pin） | ⬛ H |
| EmptyShellNotifySpec | H(1) | :164 钉 `verdict: blocked`，引擎 #566 已退役为 completed+注记（DelegateTerminal.scala:350-357） | ⬛ H |
| PidMetaSpec | G(1) | :90 cmdlineSha256 空——PidMeta.scala:170 依赖 ProcessHandle.info.commandLine，Windows 恒空；引擎已建 CIM 兜底（WindowsProcs）未接 | 🟥 G-7 |
| ProviderCommandSpec | H(2) | H6 两腿：期望 `nebflow provider …` 引擎输出 `stand provider …`（品牌迁移，平台无关） | ⬛ H |

### 3.8 静默 38 件（triage-silent；零痕迹=静默窗输出丢失，失败内容待单跑）

已按同型模式**预修**的 12 件（预期单跑转绿/skip）：AgentsMdInjection✅(symlink 门)、FileChangeTrackerNebflowExclude✅(posix 门)、HistoryArchiver✅、DaemonPanelStore✅(F5 夹具门)、ExecutorRegistry✅、ExternalExecutor✅、ChainsReadFace✅(C4b 门)、FileRefsServabilityScope✅(2 腿门)、PluginAssetsRoutes✅(escape 腿门)、MergeQueueBootSweep✅(ForceRemove)、MergeQueuePosition✅(ForceRemove)、NodeEditWorktreeBaseline✅(ForceRemove)。

已归类 H 的 3 件：CliRouterSpec⬛、SingleInstanceGuardSpec⬛、UserTextGateSpec⬛（Mac 探针同红，见 §一.2）。

**S 待单跑 23 件**（源码审读无 System.exit 类致命模式；「最可能脆弱点」备查）：
InjectionIntakeContract（纯逻辑铁证）、PromptSections（纯逻辑铁证）、PathUtilSpec（纯逻辑铁证）、CompactServiceSpec（纯逻辑铁证；引擎签名修复后可能已绿）、ToolFaceVariantSchema（纯逻辑）、TaskListLegacyCompat（近纯）、BatchDelete、WsBrowseDeviceLeg、FailedNotifySuppression（文本契约+引擎桥）、NfFileRoutes（票据静态面）、LocalAccelBudget（p95 计时 50ms/10ms）、SandboxSpec（真 shell 会话=probeCharset 已修点复验）、SandboxProvider（真 shell）、ToolPhaseStuckAxis（进程树+看门狗全局）、BashForegroundBudget（shell 计时 120s 门）、UpdateOrchestrator（30s+15s 等待链）、CompletionGate（240s 门+真 git）、RemoteUpdateActionStagedJar（shell 语法 Windows 分支）、ExternalLegPrepare（/bin/echo 假 CLI+真 claude 风险）、NodePluginChain（python3 stdio server 180s 门——**建议同款 pythonCmd 回退**）、DelegateFinishWorkResult?、DelegateLedgerReconcile?（delegate 簇仅读类头）、CliRouter/SingleInstanceGuard/UserTextGate（归 H）。

## 四、本批机械修复清单（69 文件）

**引擎侧（12 文件）**：

| 文件 | 修法 | 类 |
|---|---|---|
| src/main/scala/nebflow/shared/paths.scala | resolveRootRef：token 前缀归一比对 + 盘符绝对判定 + SubPath 构造（17 例单点） | A |
| src/main/scala/nebflow/bridge/BridgePlugin.scala | marker 显式 `\n` 拼接（CRLF 检出免疫） | B |
| src/main/scala/nebflow/bridge/DeviceProvenance.scala | 同上 | B |
| src/main/scala/nebflow/social/InboundParse.scala | 引用消息模板显式 `\n` | B |
| src/main/scala/nebflow/core/compact/CompactService.scala | isCompactReminder 签名 CRLF 容错（take 窗剥 \r） | B |
| src/main/scala/nebflow/core/tools/MailTool.scala | addressFaceProjection 在 LF 归一副本投影（段未命中仍 fail-closed） | B |
| src/main/scala/nebflow/core/compact/CompactUtils.scala | 占位符路径归一 `/`（模型可见文案） | A |
| src/main/scala/nebflow/core/project/FileChangeReport.scala | relativize 归一 `/` | A |
| src/main/scala/nebflow/core/tools/FileRefs.scala | describe 归一 `/` | A |
| src/main/scala/nebflow/social/WeixinIlinkBridgePlugin.scala | 通知文件名 split 前归一 | A |
| src/main/scala/nebflow/core/MemoryRecallDaemon.scala | pythonCmd 回退（python3→python→py，缓存探测；非 Windows 恒 python3） | F |
| src/main/scala/nebflow/core/AtomicJson.scala | Files.move AccessDenied 退避重试（3 次 50/150ms；Mac 零变化） | D |

**测试侧（57 文件，模式汇总）**：
- **POSIX 权限位/读回门**（assume posix 视图，Windows→ignored）：DeviceFaceHardeningSpec、SecretInjectionSpec、SecretsStoreSpec、SecretsRoutesSpec(fixtureTight)、DaemonPanelStoreSpec(F5 夹具门)、HistoryArchiverSpec、FileChangeTrackerNebflowExcludeSpec、TargetDirGuardSpec、ChainArchivedEventSpec(容错腿)、TokenCommandSpec、UsageAggCacheSpec、FeishuChannelSpec(posixPerms×6)、WeixinOutboundSpec(K1)、ExecutorRegistrySpec、ExternalExecutorSpec(假 bin 置位门)
- **symlink 特权能力门**（新共享件 `SymlinkProbe`，探测缓存 + assume→ignored）：ExplorerDotDir、ProjectAgentFileRoutes、MovePath、NfTicketRoutes(A9)、NfPathEncodingProbe(H 腿)、PluginAssetsRoutes(escape 腿+夹具条件化)、AgentsMdInjection(2 腿)、ChainsReadFace(C4b)、FileRefsServabilityScope(2 腿)、PluginInstallPathGuard、PluginManifestProtocol、AskUserDualMode(③腿)、WorktreeParam——见补充说明
- **git 只读残骸强删**（新共享件 `ForceRemove`，清 DOS 只读位再删；18 文件 57 调用点）：NodeMerge、MergeAcceptance、AbandonDetach、MergeQueueVisibility、DelegateAutoMerge、MergeQueueRestartReminder、DelegateTerminal、DelegateWorkspaceWorktreeLeg、GitWorktrees、NodeEditWorktreeBaseline、PostMergeCleanup、MergeQueuePosition、NodeAcceptance、MergeQueueBootSweep、ExternalLegPrepare、DelegateFinishWorkResult、CompletionGate、ChainsReadFace
- **路径分隔符/绝对性归一**：NeblinkSecurity、EgressWhitelist、NfServeGate、NfPathEncodingProbe(forms/wirePath)、PickerBrowseFrame、HealthGate、MemoryRecallDaemon(:315)、PluginMcpProtocol、BashTool(normPath)、MultiEdit、PopToolFileRef、SendMessageAttach、RgHelper、PopToolSpec(jsonEsc)、PointerPickup、GrepToolRanked、FileRefsWhitelist、DelegateDeviceRouting、NodeChainAttribution、SandboxFenceRemoval、BashToolSpec(e2e)、DataRootPlaceholder(:38/:183)
- **python 回退**（测试夹具侧，与引擎同口径）：PluginMcpManagerSpec、BashBackgroundHardTimeoutSpec、BashActivityBridgeSpec
- **平台无关化杂项**：TaskListHistory(MessageDigest)、AskUserQuestion(lf 归一)、LlmLogWriterStream(!isBefore)、ShellSessionCharsetProbe(D2 平台门)、PluginInjection(NTFS 大小写门)、AskUserDualMode(/dev/null POSIX 门)、NeblinkPresenceDialBudget(timeout 轴组 Windows 门)
- **新增共享件**：src/test/scala/nebflow/SymlinkProbe.scala、src/test/scala/nebflow/ForceRemove.scala

> 说明：WorktreeParamSpec 的 2 例为 symlink 特权（triage-project），本批未单独落门——列入 KAI 单跑清单核实后补门（同 SymlinkProbe 模式，一行改动）。

## 五、G 类立案（报主会话派专项，本批不修）

| 编号 | 根因 | 影响面 | 修法方向 |
|---|---|---|---|
| G-1 | `SecretsStore.fileIsTight`（SecretsStore.scala:568-572）+ 复刻件 `AskUserSecretSink.fileIsTight`（:234-238）+ `DaemonPanelStore dirIsTight/fileIsTight`（:257-270）Windows 无 POSIX 视图 fail-closed ⇒ **Windows 上 secrets/daemon-panel 凭据无法写入**（表现层 SecretsRoutes.scala:160-161） | secrets 簇 37 例 + DaemonPanelRoutes 4 例 | fileIsTight/dirIsTight 加 Windows 臂：ACL 读数/CredentialFileAcl ladder 证明替代 POSIX 读回（对齐 dirIsTight:566 平台声明）；三份复刻同步改；AskUserSecretSinkSpec S4a 误绿需修复后复核 |
| G-2 | AttachContract TargetDirGuard.scala:168 契约词法拒 Windows 盘符/UNC + DropboxService.scala:2064 `landingDirFor` 仅认 `startsWith("/")` ⇒ **dropbox 落盘面 Windows 整体不可用** | SavedPathLandedReadback 7 + TargetDirNoSideEffect 3 | 契约加 Windows 形态臂：`Paths.get`+`isAbsolute` 判定（NIO startsWith 包含判定本就跨平台）；landingDirFor 删前缀闸 |
| G-3 | NfFilePolicy.scala:215 `inodeKey` 依赖 `BasicFileAttributes.fileKey()`，NTFS 恒 null ⇒ R2 (dev,ino) 硬链凭据闸结构性失效（403 期待面放行 200） | NfTicketRoutes A10 + NfPathEncodingProbe G 腿 | Windows 用 NTFS FileID 替代，或显式声明 R2 不支持并跳过硬链腿（fail-visible） |
| G-5 | ToolLoader.scala:180 引擎把 Windows 路径内插 `sh -c` 文本，bash 吞 `\` ⇒ 命令面目全非 | ToolLoaderSpec 2 例 | 内插前正斜杠化或只走 env 传递 |
| G-6 | MergeWindowCare.scala:54 `actorSystem=null` 无守卫（残留队列状态触发 NPE） | NodeBgCompletionGateSpec 1 | null 守卫 + 触发路径复核 |
| G-7 | PidMeta.scala:170 依赖 `ProcessHandle.info.commandLine`（Windows 恒空）；引擎已有 CIM 兜底（WindowsProcs.scala:9-16,45）未接 | PidMetaSpec 1 | selfCommandLine 补 `commandLineViaCim(currentPid)` |
| — | MemoryRecallDaemon python3 已按 F 类机械修（回退链）；**若 KAI 无任何 python** ⇒ 4 例 e2e 保持红，需装 python 或另立引擎级降级 | MemoryRecallDaemonSpec 4 | 装机或降级策略 |

## 六、H 类清单（不动，归腐烂治理批；均平台无关、Mac 同版必同败，需基线 31 核对）

| 套件 | 证据 | 备注 |
|---|---|---|
| CliRouterSpec | A4/C1 端口/env 断言（Mac 探针同红） | 静默件+H 实锤 |
| SingleInstanceGuardSpec | R1 focus/drain 2 例（Mac 探针同红） | 同上 |
| UserTextGateSpec | 正控②/负控⑤ 2 例（Mac 探针同红） | 同上 |
| HealthRoutesSpec | :185 期望 8080 实得 Defaults.GatewayPort=6138 | 断言可改读 Defaults（治理批一行） |
| BgRemoteOutputRoutesSpec | RemoteExecutor 单例顺序污染 | 测试前置重置单例或双接受 |
| StatusCommandSpec | legacy "nebflow" 白名单项被 "stand" 覆写（SingleInstanceGuard.scala:173，注释自称仍识别） | **疑真回归**，建议优先 |
| ConfigEditHeadlessSpec | `'nebflow config set'` → `'stand config set'` 品牌迁移 | 测试 re-pin |
| SubAgentInboxMirrorSpec | 源码计数 pin 13→15 | re-pin |
| EmptyShellNotifySpec | verdict blocked→completed+#566 裁定 | fixture 跟进 |
| ProviderCommandSpec | H6 两腿 nebflow→stand 品牌迁移 | re-pin |
| WatchdogSelfMonitorSpec | scan→halt L1 竞态 | 需基线定 H/升级 G |
| DataRootPlaceholderSpec:226 腿 | NEBFLOW_GATEWAY_PORT=6138 环境依赖断言 | 断言与脚本读同一来源 |

## 七、F-pending（未修，派观察/专项）

| 套件 | 问题 | 建议方向 |
|---|---|---|
| NodeSeatCwdSpec(2) | MSYS `pwd` 打 `/c/...` vs 座椅 `C:\...` 逐字断言 | shell cwd 形态契约真机定版（`pwd -W` 或归一比较） |
| DeviceProjectMountSpec(2) | 「宿主非 Windows」前提在 Windows 主机不成立 | 前提重构（探测驱动的夹具） |
| NodeBgReclaimSpec(2) / NoderptVerifyProbeSpec(2) | `echo $$` MSYS pid 对 ProcessHandle 不可见 ⇒ 探活恒 false | 引擎探活 Windows 臂（kill -0 / tasklist），专项 |
| NodeAcceptanceSpec(1) | logs/router 句柄残留（DirectoryNotEmpty） | ForceRemove 已上；若 KAI 仍红 ⇒ 残留持有点另查 |
| MultiEditToolSpec:160 | Windows mtime 粒度内双写碰撞 externallyModified=false | KAI 复跑钉死；真修需引擎 mtime 精度策略 |

## 八、KAI 验证清单（Windows 终验，主会话发 KAI 执行）

> **纪律（丢块教训）**：`sbt "testOnly <FQCN…>"` **每批 ≤6 个套件**，逐批留日志；禁全量重跑取证。

1. **机械修复批转绿验证（66+7 部分修）**——按簇分 12 批：
   - 批1 secrets：`testOnly nebflow.core.secrets.SecretInjectionSpec nebflow.core.secrets.SecretsStoreSpec nebflow.gateway.SecretsRoutesSpec`（预期：SecretInjection 全绿；SecretsStore/SecretsRoutes 仅剩 G-1 腿红：N4/P1/P3/P4/V2 与 13 例 403）
   - 批2 social-A：`testOnly nebflow.social.WeixinIlinkScanBindSpec nebflow.social.FeishuScanBindSpec nebflow.social.WeixinIlinkBridgeSpec nebflow.social.SocialChannelsBootSealSpec nebflow.social.FeishuChannelSpec nebflow.social.WeixinOutboundSpec`（预期全绿；InboundParse IP-8 绿）
   - 批2b：`testOnly nebflow.social.InboundParseSpec`
   - 批3 neblink：`testOnly nebflow.neblink.DeviceFaceHardeningSpec nebflow.neblink.NeblinkSecuritySpec nebflow.neblink.NeblinkPresenceDialBudgetSpec nebflow.neblink.EgressWhitelistSpec`（预期：NeblinkPresenceDial 的 T1/T2/T3=3 例 ignored（timeout 轴组 Windows 门），其余全绿或 skip）
   - 批4 bridge/CLI：`testOnly nebflow.bridge.BridgeOriginSpec nebflow.bridge.DeviceProvenanceSpec nebflow.cli.TokenCommandSpec nebflow.cli.PidMetaSpec`（PidMeta 预期仍红=G-7）
   - 批5 gateway：`testOnly nebflow.gateway.PasteAttachmentGuardSpec nebflow.gateway.NfServeGateSpec nebflow.gateway.PickerBrowseFrameSpec nebflow.gateway.MovePathSpec nebflow.gateway.ProjectAgentFileRoutesSpec nebflow.gateway.ExplorerDotDirDanglingSymlinkSpec`（预期全绿或 ignored）
   - 批6 gateway-2：`testOnly nebflow.gateway.NfPathEncodingProbeSpec nebflow.gateway.NfTicketRoutesSpec nebflow.gateway.PluginAssetsRoutesSpec`（NfPathEncoding A-F 腿绿、G 腿红=G-3；NfTicket A9 绿、A10 红=G-3）
   - 批7 compact：`testOnly nebflow.core.compact.CompactServiceSpec nebflow.core.compact.FullCompactSpec nebflow.core.compact.CompactUtilsOversizedSpec nebflow.core.compact.HistoryArchiverSpec nebflow.agent.CompactionAbandonFaceSpec nebflow.agent.AgentActorCompactionSpec`（预期全绿）
   - 批8 tools-1：`testOnly nebflow.core.tools.BashToolSpec nebflow.core.tools.MultiEditToolSpec nebflow.core.tools.PopToolSpec nebflow.core.tools.PopToolFileRefSpec nebflow.core.tools.RgHelperSpec nebflow.core.tools.GrepToolRankedSpec`
   - 批9 tools-2：`testOnly nebflow.core.tools.PointerPickupSpec nebflow.core.tools.DelegateDeviceRoutingSpec nebflow.core.tools.SendMessageAttachSpec nebflow.core.tools.FileRefsWhitelistSpec nebflow.core.tools.FileDiffToolLayerSpec nebflow.core.tools.TaskListHistorySpec`
   - 批10 tools-3：`testOnly nebflow.core.tools.AskUserQuestionToolSpec nebflow.core.tools.AskUserDualModeToolSpec nebflow.core.tools.ShellSessionCharsetProbeSpec nebflow.core.tools.MailModelRetiredSpec nebflow.core.tools.BashBackgroundHardTimeoutSpec nebflow.core.tools.BashActivityBridgeSpec`
   - 批11 daemon/misc：`testOnly nebflow.core.daemon.DaemonPanelStoreSpec nebflow.core.MemoryRecallDaemonSpec nebflow.core.hotrestart.HealthGateSpec nebflow.core.UsageAggCacheSpec nebflow.core.LlmLogWriterStreamSpec nebflow.core.plugin.PluginMcpManagerSpec`
   - 批12 git-fixture 簇（ForceRemove 实证）：`testOnly nebflow.core.project.NodeMergeSpec nebflow.core.delegate.MergeAcceptanceSpec nebflow.core.project.AbandonDetachSpec nebflow.core.project.MergeQueueVisibilitySpec nebflow.core.delegate.DelegateAutoMergeSpec nebflow.agent.MergeQueueRestartReminderSpec`，随后 `testOnly nebflow.core.delegate.DelegateTerminalSpec nebflow.core.delegate.DelegateWorkspaceWorktreeLegSpec nebflow.core.GitWorktreesSpec nebflow.core.project.NodeEditWorktreeBaselineSpec nebflow.core.delegate.PostMergeCleanupSpec nebflow.core.project.PluginInjectionSpec`
   - 批13 project 残余：`testOnly nebflow.core.project.NodeChainAttributionSpec nebflow.core.task.TaskStoreAtomicWriteSpec nebflow.core.project.FileChangeReportSpec nebflow.core.tools.FileDiffToolLayerSpec nebflow.core.project.ChainArchivedEventSpec nebflow.core.project.CompletionGateSpec`
   - 批14 sandbox/tools 静默组：`testOnly nebflow.core.sandbox.SandboxSpec nebflow.core.sandbox.SandboxProviderSpec nebflow.core.sandbox.SandboxFenceRemovalSpec nebflow.core.tools.ToolPhaseStuckAxisSpec nebflow.core.tools.BashForegroundBudgetSpec nebflow.core.tools.FileRefsServabilityScopeSpec`
   - 批15 静默纯逻辑组：`testOnly nebflow.agent.InjectionIntakeContractSpec nebflow.agent.PromptSectionsSpec nebflow.core.PathUtilSpec nebflow.agent.ToolFaceVariantSchemaSpec nebflow.core.tools.TaskListLegacyCompatSpec nebflow.gateway.BatchDeleteSpec`，随后 `testOnly nebflow.gateway.WsBrowseDeviceLegSpec nebflow.gateway.NfFileRoutesSpec nebflow.gateway.LocalAccelBudgetSpec nebflow.core.hotupdate.UpdateOrchestratorSpec nebflow.core.hotupdate.RemoteUpdateActionStagedJarSpec nebflow.core.executor.ExternalLegPrepareSpec`，再 `testOnly nebflow.core.plugin.NodePluginChainSpec nebflow.core.delegate.DelegateFinishWorkResultSpec nebflow.core.delegate.DelegateLedgerReconcileSpec nebflow.agent.AgentsMdInjectionSpec nebflow.core.project.ChainsReadFaceSpec nebflow.core.executor.ExecutorRegistrySpec`，最后 `testOnly nebflow.core.executor.ExternalExecutorSpec nebflow.core.FileChangeTrackerNebflowExcludeSpec nebflow.core.project.NodeSeatCwdSpec nebflow.core.project.DeviceProjectMountSpec nebflow.core.project.NodeBgReclaimSpec nebflow.core.project.NoderptVerifyProbeSpec`
   - 批16 WorktreeParam 补门核实：`testOnly nebflow.core.WorktreeParamSpec nebflow.core.project.NodeAcceptanceSpec nebflow.core.project.MergeQueuePositionSpec nebflow.core.processor.WatchdogSelfMonitorSpec nebflow.cli.SingleInstanceGuardSpec nebflow.cli.CliRouterSpec`（后两件预期仍红=H）
2. **S 类 23 件单跑结果回填**：每件记录实际失败内容 → 二次归类（预期多数落入已修模式；纯逻辑组若绿=静默窗假红）。
3. **环境项**：`python --version` / `py --version` 可用性回传（决定 MemoryRecallDaemon 4 例与 NodePluginChain 的最终判定）；`NEBFLOW_GATEWAY_PORT` 环境值确认（H 类 HealthRoutes/DataRootPlaceholder 判据）。
4. **不动项确认**：H 类 11 套件预期仍红（属腐烂治理批）；G 类 7 根因对应腿预期仍红。

## 九、Mac 回归（防回归闸）

- 批 1（4 套件先行）：SecretInjectionSpec / SecretsStoreSpec / SecretsRoutesSpec / DeviceFaceHardeningSpec = **71/71 全绿**（2026-10-25 15:5x，EXIT=0）。
- 批 2（全量 88 套件 = §四改动面当时态）：**Total 1039, Failed 0, Errors 0, Passed 1039**，`==> X` 事件 0 条，EXIT=0（2026-10-25，`/tmp/mac-regress-full.log`）。
- 批 3（大批启动后才改的 4 套件 + 漏列件）：PopToolFileRefSpec（FileRefs.describe 归一）/ SandboxFenceRemovalSpec（哨兵归一 + NTFS 门）/ PluginMcpManagerSpec（python 回退夹具漏列）首轮 47/49 绿；NeblinkPresenceDialBudgetSpec 2 红根因=TEST-NET-1 黑洞在 Mac 每拍成本 +1s（真实路由 ARP/网关，超预算敏感断言与 30s 墙）⇒ 已回退 127.0.0.x 夹具 + T1/T2/T3 加 Windows 平台门（assume→ignored）。复跑结果见下：
  - 复跑（仅 NeblinkPresenceDialBudgetSpec）：**Total 17, Failed 0, Passed 17**，EXIT=0（2026-10-25 16:27，`/tmp/mac-regress-b3b.log`）。
- **Mac 回归总计：批 1（71/71）+ 批 2（1039/1039）+ 批 3（49 例首轮 47 绿、2 红回退夹具后复跑全绿）= 全绿，防回归闸通过。**

## 十、关键假设与开放项

1. Mac 基线 31 失败**套件级清单**未随任务下发——H 类判定基于「平台无关必同败 + Mac 探针实测」，需主会话以基线清单终核（§六 11 件）。
2. Windows 日志丢块机理未查（疑 sbt server 管道锁 error 5 后输出接线异常）——只影响取证，不影响修复；sbt 侧问题建议另案。
3. KAI 检出树行尾形态在 test6 运行时为 CRLF（字节差+编译产物常量池双向实证）；分诊期间实测已部分转 LF——**引擎侧 B 类修复已对两种形态免疫，但仓根 `.gitattributes *.scala eol=lf` + renormalize 仍为治本项**（治理批，涉及全仓 churn 与在飞分支协同，本批不动）。
4. Mac 全量回归（批 2）完成前不合并——本报告 §九 待回填。
5. WorktreeParamSpec（symlink 特权 2 例）未落门，KAI 单跑核实后按 SymlinkProbe 模式一行补齐。
