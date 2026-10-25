# Windows(test6) social 簇 25 例失败分诊（7 套件）

数据源：KAI 机 /c/Users/Kai/stand-win-test6.clean.log、failed-events.txt、x-per-suite.txt；源码参照 worktree delegate-nebflow-5e24b2f7。只分类不修码。无 H 类：7 套件 25 例均为 Windows-only，不在 Mac 基线 31 例预存腐烂之列。

## 逐套件

WeixinIlinkScanBind | A | 9 例（WSB-1/3, BT-2/3/4/6/7/8/9）全 TimeoutException:10s 于 waitFor(done/failed)；done 腿 SocialChannels.save:534→probeOf:316→resolveRootRef 抛 InvalidPathException（paths.scala:291），persistAndFinish（WeixinIlinkScanBind.scala:237-259）只映射 Either、thrown 异常逃出 fiber，状态停在 qr_ready/polling | 修法：paths.scala:287 tokenPrefixes 各元素 .replace('\\','/') 后再 startsWith（单点修全簇）；另建议 persistAndFinish 兜 attempt 防 wedge

FeishuChannel | G(测试侧) | 5 例全 UnsupportedOperationException: 'posix:permissions' not supported as initial attribute；测试夹具 os.write 带 POSIX perm 属性：FeishuChannelSpec.scala:175/225/249/269/283（栈顶 WindowsSecurityDescriptor.fromAttribute）| 缺陷：夹具用 POSIX-only 写法且未套本套件已有的 Windows 跳过模式（aclReading 组就是 assume 跳过）；修=5 处按 isWindows 去 perm 或加 assume

WeixinIlinkBridge | A | 4 例（WI-A1/B1/B3/B4）直接 InvalidPathException: Illegal char <:>，双路径 <root>\nb-xxx/C:/Users/...；栈：WeixinIlinkBridgeSpec.scala:455→SocialChannels.save:534→probeOf:316→resolveRef:305→PathUtil.resolveRootRef(paths.scala:291) | 修法：同 paths.scala:287 归一化，引擎单点一处

FeishuScanBind | A | 3 例（SB-1/3/8）TimeoutException:10s；FeishuScanBind.scala:267 persistAndFinish→SocialChannels.save→probeOf→resolveRootRef 同根抛出、fiber 死；恰好仅"过持久化腿"的 3 例超时，失败族 SB-4/5/6（不落 save）全绿——结构与根因完全吻合 | 修法：同 paths.scala:287

WeixinOutbound | A+G | WO-K1：测试 WeixinOutboundSpec.scala:180 Files.getPosixFilePermissions 抛 UnsupportedOperationException(null)（ensureToken 本身成功，测试无 posixOnly 守卫）；WO-W10：保底通知带全路径 [文件「C:\...\bundle.zip」发送失败]，WeixinIlinkBridgePlugin.scala:1636 split('/').last 对反斜杠路径不切 | 修法：K1 套用仓内现成模式 posixOnly() assume（WeixinIlinkScanBindSpec.scala:90-94）；W10 于 1636 先 replace('\\','/') 再 split

SocialChannelsBootSeal | A | A3 InvalidPathException 同款双路径（nb-social-bootseal-…/C:/…）；saved()（SocialChannelsBootSealSpec.scala:75）→save:534→probeOf→resolveRootRef(paths.scala:291) | 修法：同 paths.scala:287 归一化

InboundParse | B | IP-8 contains("原文：\n原文内容") 假；KAI 检出的 InboundParse.scala 为 CRLF（od 实测），三引号字面量 :253-257 编译含 \r\n（已验：target/scala-3.5.2/classes/.../InboundParse$.class 常量池含「原文：\r」），2.13.14 stripMargin 经 linesWithSeparators 原样保留分隔符 | 修法：仓根加 .gitattributes（*.scala text eol=lf）并重规范化 Windows 检出；或引擎 :253 改显式 "\n" 拼接

## 簇归并

簇A·双路径/持久化超时 | 17 例：WeixinIlinkScanBind(9)+FeishuScanBind(3)+WeixinIlinkBridge(4)+SocialChannelsBootSeal(1) | 根因文件：引擎侧 nebflow/shared/paths.scala:280-291 resolveRootRef（比对侧未归一化；触发器：SocialChannels.scala:504 renderRoot 存 native 反斜杠 ref；放大器：WeixinIlinkScanBind.scala:237 / FeishuScanBind.scala:267 persistAndFinish 对 thrown 异常无兜底） | 修法模式：单点归一化——resolveRootRef 内 tokenPrefixes 与 ref 同规则 .replace('\\','/') 再比对（同时让绝对判定认 C:/ 形态），一处修复四套件 17 例；顺带给 persistAndFinish 补 attempt 兜底防状态机 wedge

簇B·CRLF 编译污染 | 1 例：InboundParse(1) | 根因：仓侧无 .gitattributes（检出策略缺位）× Windows autocrlf 检出 CRLF × 引擎三引号字面量（InboundParse.scala:253-257） | 修法模式：.gitattributes 强制 eol=lf + Windows 侧重规范化检出（一行仓配置）；防复发可在 IP-8 断言改换行不敏感

簇G·POSIX-only API | 6 例：FeishuChannel(5)+WeixinOutbound-WO-K1(1) | 根因：测试侧（FeishuChannelSpec.scala:175/225/249/269/283 带 perm 的 os.write；WeixinOutboundSpec.scala:180 getPosixFilePermissions）——引擎侧 CredentialFileAcl 已有 Windows 分支，非引擎缺陷 | 修法模式：复用仓内既有 isWindows assume（WeixinIlinkScanBindSpec.scala:90），Windows 下跳过 POSIX 断言或走 ACL 等价校验

WO-W10 单例 | A | 引擎侧 WeixinIlinkBridgePlugin.scala:1636 split('/') 提名失败 | 修法模式：split 前归一化分隔符（一处一行）

## 关键证据行号

- paths.scala:280-291 resolveRootRef：normalized=ref.replace('\\','/')，tokenPrefixes 未归一化（renderDataRootValue→root.toString 原生分隔符）；:290 startsWith("/") 不认 "C:/"；:291 兜底拼接 root.toString+"/"+normalized → InvalidPathException。
- SocialChannels.scala:504 存储 ref 用 renderRoot(root)（Windows 原生反斜杠）；:534 save 尾部 probeOf；:305-316 resolveRef/probeOf。
- WeixinIlinkScanBind.scala:237-259 / FeishuScanBind.scala:267 persistAndFinish：IO.blocking(save).flatMap 只处理 Right/Left（Either），probeOf 的 thrown 异常使 IO 整体失败且无状态写入。
- KAI 实测：/c/Users/Kai/stand/src/main/scala/nebflow/social/InboundParse.scala 字节为 CRLF；编译产物 InboundParse$.class 常量池含 原文：\r 各 1 处。
- Scala 3.5.2 parser 对多行字面量保留 CR（CharArrayReader nextRawChar）；scala-library 2.13.14 stripMargin 经 linesWithSeparators 原样保留分隔符——两者都不洗 \r。
