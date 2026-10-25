# Windows(KAI) sbt test6 失败分诊 — nebflow.core.tools 套件族（19 套件 / 30 败）

KAI 运行修订 = a952953a（main）；本地核对源码树 = worktree delegate-nebflow-5e24b2f7 @ 4ca8c950（a952953a 为其祖先；本分诊涉及的 AskUserQuestionTool(engine+spec)/MailTool/MailModelRetiredSpec/MultiEditTool(engine+spec) 两修订间零漂移，源码结论对 KAI 运行直接有效）。KAI 工作树实测：各 Spec 文件均为 CRLF（AskUserQuestionToolSpec 323 CR 行、MailModelRetiredSpec 397、MultiEditToolSpec 265、BashToolSpec 170），MailTool.scala 现读 LF（疑事后归一）；Scala 3.5.2；日志证实该机无 python3（memory-recall 每秒报 CreateProcess error=2）。任务清单合计 3×4+2×3+1×12 = 30 起，与逐条事件数一致（背景注 31 为约数）。

## 逐套件（<套件短名> | 类 | 证据 | 修法/缺陷说明）

BashToolSpec | A | FailException:125 错误文本缺"/tmp/nb-t2-cwd-missing-root-…"(raw 已被 os.Path 规范化为 C:\tmp\…);:148 stdout 含 MSYS 形态"/tmp/nb-…"无 realDir;:164 pwd -W 得"C:/Users/Kai/stand"≠user.dir"C:\Users\Kai\stand" | 修:测试侧用 Files.createTempDirectory 真路径+断言前 replace('\\','/')(BashToolSpec.scala:113-115/138-140/148/164;引擎 shell.scala:206-211 pwd -W 本身无错)

MultiEditToolSpec | A(主;含1起F) | :243 "Path must be absolute, got: \tmp\nb-multiedit-no-such-file-xyz.txt"(/tmp 字面 Windows 非绝对,绝对闸先触发,期望 cannot create);:261 summarize 得"\tmp\spec\…";:160 externallyModified=false(见簇5) | 修::243 测试 fixture 换真绝对路径;:261 测试归一或引擎 summarize 输出正斜杠化(MultiEditToolSpec.scala:243/261);:160 测试强制 mtime 位移或引擎 mtime 相等时仍比内容(MultiEditTool.scala:257-265)

ToolLoaderSpec | G | ComparisonFail:218/228 得"C:UsersKaistandtargettest-tool-loadertools"反斜杠全被吞——load 期把 Windows 路径内插进 sh -c 命令文本(ToolLoader.scala:180),经 Win32 命令行引号边界后 bash 把 \U 等当转义;:342 得混合分隔符"…tools\issue/issue.sh" | G 缺陷:反斜杠路径内插 shell 命令文本在 Windows 必被吞(真 bug);修:内插前 sourceDir.toString.replace('\\','/') 或 TOOL_DIR 只走 env 不进命令文本;:342 引擎 join 串统一'/'(ScriptTool.scala:24 相关)

AskUserQuestionToolSpec | B | ComparisonFail:262/279/302 munit 判"仅空白/ANSI 差异"且只有多行用例挂——CRLF 检出的 stripMargin 逐字保留 \r(linesWithSeparators),期望串含 \r\n,引擎 formatAnswer mkString("\n") 纯 LF(AskUserQuestionTool.scala:602/604) | 修:检出统一 LF(.gitattributes)或期望串归一 replace("\r\n","\n")(AskUserQuestionToolSpec.scala:257-302;单行用例不受影响全绿=旁证)

BashBackgroundHardTimeoutSpec | F | :77 BackgroundJobHealth(false,…,python3 -c 'while True: pass') isAlive=false;:43 expected timeout failure got success——fixture 命令 python3 在 KAI 不存在(日志每秒 Cannot run program "python3": CreateProcess error=2)→任务秒退:B-2 判被杀/B-3 自然完成出 Right | 修:fixture 换平台可用 busy 命令(bash -c 'while :; do :; done')(BashBackgroundHardTimeoutSpec.scala:67-96);附注:引擎 ps 版 CPU 兜底在 Windows 编译关闭(shell.scala:552)

AskUserDualModeToolSpec | F | :283 附件① /dev/null 在 Windows 非字符设备,探针落 ASKUSER_ATTACH_UNREADABLE"0 bytes"(≠期望 NOT_REGULAR);:370 Files.createSymbolicLink 抛 FileSystemException"客户端没有所需的特权"(建链需管理员/开发者模式) | 修:测试按平台选 fixture+assumeTrue(平台可建符号链)(AskUserDualModeToolSpec.scala:343/370)

PopToolFileRefSpec | A | ComparisonFail:104 得"C:\\…\\test-poprefs-missing-…\\ghost.png" vs 期望"…/ghost.png";:117 "C:\\Users\\Kai\\__poprefs_missing__\\plot.png" vs "C:\Users\Kai/__…"——引擎 resolvedPath=toAbsolutePath.normalize.toString(FileRefs.scala:348),测试串拼'/' | 修:测试侧归一分隔符,或引擎 describe 统一'/'(仓内既有惯例 NfFilePolicy.scala:358)

TaskListHistorySpec | F | IOException CreateProcess error=2 系统找不到指定的文件——测试助手 os.proc("shasum","-a","256",…)(shasum=macOS Perl 脚本,Windows 无法执行);引擎 TaskListHistory.scala 零外呼 | 修:测试换 java.security.MessageDigest SHA-256(引擎 HttpCache.scala:37 同款)(TaskListHistorySpec.scala:83-84)

ShellSessionCharsetProbeSpec | C | FailException:182 out==text——gate None ⇒ charsetProbeEnabled=isWindows(shell.scala:428-429)⇒Windows 探测门设计性开启,detectCharset 把 GBK 字节正确按 GBK 解码(shell.scala:1320-1325);测试写死 Mac 形态(UTF-8 硬解必乱码) | 修:测试用 ShellSession.charsetProbeGateOverride 固定关闭探测再断言,或按平台分支期望(ShellSessionCharsetProbeSpec.scala:175-183)

SendMessageAttachSpec | A | FailException:125 got"Attachment paths must be absolute, got: '/tmp/a.bin'"——'/tmp/a.bin' 字面在 Windows Paths.get().isAbsolute=false,friend 腿绝对路径闸先于"服务缺席"报错触发 | 修:测试 fixture 用 os.temp.dir 真绝对路径(SendMessageAttachSpec.scala:112;闸本体 FriendMessageTool.scala:685-690 无错)

RgHelperSpec | A | ComparisonFail:190 得 List(".\\one.txt",".\\sub\\two.txt") vs 期望 List("one.txt","sub/two.txt")——Windows rg 打印".\"前缀+反斜杠,测试只 stripPrefix("./");runRg 引擎直通不改写(RgHelper.scala:185-198) | 修:测试侧 strip ".\\"+replace('\\','/')(RgHelperSpec.scala:189-190)

PopToolSpec | A | FailException:347 S9 被"looks like a JSON array literal"拒绝——测试 s"""["${dir/"a.png"}",…]""" 手拼 JSON 串,Windows 路径反斜杠未转义=非法 JSON 转义→circe 解析失败→guard 拒绝(接受/拒绝纯看解析成败,PopTool.scala:353-360) | 修:测试用 circe 编码构造 quoted 串(PopToolSpec.scala:330)

PointerPickupSpec | A | FailException:46 f.toString="…\\uploads\\pickups\\<hash>\\…" 不含"uploads/pickups/<hash>"——os.Path 分隔符;同套件:62 结构化 startsWith 用例绿=旁证引擎段式构建(PointerPickup.scala:40-65)无错 | 修:测试归一分隔符或改结构化断言(PointerPickupSpec.scala:46)

MailModelRetiredSpec | B | FailException:385 dispatcher 面仍含"## Kernel continuation leg"⇒addressFaceProjection 走 fail-closed 回落全量 base(MailTool.scala:539-545)⇒indexOf(AddressFaceHeader)未命中⇔base description 物理换行为 CRLF 时与显式"\n"常量(:517-518)失配;KAI 工作树 specs 实测普遍 CRLF,MailTool.scala 现读 LF(疑事后归一) | 修:引擎投影定位前归一 \r\n→\n,或 .gitattributes eol=lf 杜绝 CRLF 检出;需按运行时检出状态复核

GrepToolRankedSpec | A | FailException:79 行含"C:\…\many.txt"——盘符冒号触雷"纯路径行不得含':'"断言(Unix 无冒号假设);ranked 管线本体(count 解析/排序/剥计数,:76-78)全绿 | 修:测试剥离盘符前缀后再查冒号,或改比路径集合(GrepToolRankedSpec.scala:79;引擎 GrepTool.scala:231-234 输出本体无错)

FileRefsWhitelistSpec | A | FailException:138 deny("nebflow.json")=None——fixture root=Paths.get("/tmp/…") 在 Windows 为有根非绝对,toAbsolutePath 补盘符后 rp.startsWith(dr) 根组件不等("C:\" vs "\")→root 锚定种子层永不命中(NfFilePolicy.scala:402-407) | 修:测试 fixture 用真实绝对临时目录;引擎种子层可加 toAbsolutePath 归一(生产 dataRoot 盘符绝对不受影响)

FileDiffToolLayerSpec | A | ComparisonFail:97 得"sub\\b.txt" vs "sub/b.txt"——FileChangeReport.relativize 缺 .replace('\\','/')(FileChangeReport.scala:404-410;仓内其他 relativize 点均有,如 NfFilePolicy.scala:358) | 修:引擎 relativize 补一行归一(或测试归一)

DelegateDeviceRoutingSpec | A | FailException:46 assert(ws.endsWith("/general")) 挂——ws=os.Path.toString 反斜杠形态(DelegateTool.scala:229-234) | 修:测试归一后 endsWith,或匹配两种分隔符(DelegateDeviceRoutingSpec.scala:46)

BashActivityBridgeSpec | F | FailException:152 processActivityMs=0——fixture ProcessBuilder("bash","-c","python3 -c 'while True: pass'")+redirectInput(new File("/dev/null"));python3 缺失→bash 秒退→活性桥永不置戳(BashTool.scala:949-975) | 修:fixture 换平台可用 busy 命令+nullInput 平台化(Windows 用 NUL 或不重定向)

## [簇归并]

1. CRLF检出×字符串字面 | AskUserQuestionToolSpec, MailModelRetiredSpec | 测试侧:stripMargin 期望构造逐字保留检出 \r;引擎侧:MailTool.scala:517-545 字面子串投影 fail-closed(CRLF base 与显式 \n 常量失配) | 修法模式:检出统一 LF(.gitattributes *.scala eol=lf)+断言/投影前 replace("\r\n","\n")
2. POSIX路径字面进fixture/断言 | BashToolSpec, MultiEditToolSpec(:243/:261), SendMessageAttachSpec, PopToolFileRefSpec, PointerPickupSpec, RgHelperSpec, DelegateDeviceRoutingSpec, GrepToolRankedSpec, PopToolSpec(S9), FileRefsWhitelistSpec(fixture), FileDiffToolLayerSpec | 测试侧为主(10 件 Spec 的 /tmp 字面、'/'串拼、stripMargin 前缀、':'扫描、手拼 JSON);引擎侧仅三处展示层归一缺失:FileRefs.scala:348 describe、FileChangeReport.scala:404-410 relativize、ToolLoader 子目录 join(:342) | 修法模式:fixture 一律 Files.createTempDirectory/os.temp.dir 真路径;断言前统一 replace('\\','/');JSON 串经 circe 构造;引擎三处补分隔符归一
3. 外部命令/特权/设备文件缺失 | BashBackgroundHardTimeoutSpec, BashActivityBridgeSpec, TaskListHistorySpec, AskUserDualModeToolSpec | 测试侧 fixture:python3、shasum、/dev/null、createSymbolicLink | 修法模式:平台无关 fixture——bash 内建 busy 循环、in-JVM MessageDigest、NUL/不重定向、assumeTrue(平台能力)
4. 引擎Windows真缺陷 | ToolLoaderSpec(:218/:228 反斜杠路径内插 sh 命令文本被 Win32 引号边界+bash 转义吞=真 bug;:342 混合分隔符为轻症) | 引擎侧:ToolLoader.scala:180(+ScriptTool.scala:24 sh -c 执行面) | 修法模式:内插前正斜杠化,或 TOOL_DIR 只走 env、命令文本留 $TOOL_DIR 引用
5. 平台分支语义/计时粒度 | ShellSessionCharsetProbeSpec(C:探测门 isWindows 设计行为 vs 测试写死 Mac 形态), MultiEditToolSpec:160(F:Windows 文件时间戳粒度内双写 mtime 碰撞,FileTime 相等⇒判未修改;0.0s 用例,JDK-Windows 文件时间粒度所致,需复跑钉) | 测试侧假设为主(ShellSessionCharsetProbeSpec.scala:175-183;MultiEditToolSpec.scala:149-161) | 修法模式:测试固定 charsetProbeGateOverride;mtime 断言前强制位移或引擎 mtime 相等时仍比对内容
