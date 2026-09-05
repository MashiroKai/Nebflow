# Nebflow（Scala 3 + Pekko，46MB fat jar）的 jpackage 原生分发：构建机制、CI 流水线、零预算签名与自研更新检查

综合研究报告 · 2026-08-15。本报告合并构建侧（Track A）与分发侧（Track B）两轨结论，并已按独立验证报告应用修正：验证者重取了几乎全部承重来源、经 GitHub/JBS API 复核元数据，并在相同环境（macOS arm64，Temurin 23.0.1 + 11.0.23）**独立复现了 Track A 的全部本地实验**。除明确标注"未核验/社区报告/综合建议"外，所有断言均经核验。

## 落地状态（2026-08-25 更新）

本报告已从调研态进入落地态，以下为已实施部分（feat/desktop-packaging，commit cf6b7701）：

**已落地（仓库 packaging/ + CI）**：
- `packaging/build-dmg.sh`（macOS，既有）→ 产物 `build/dist/${PRODUCT_NAME}-${VERSION}-${ARCH}.dmg`（ARCH=arm64/x64）
- `packaging/build-msi.sh`（Windows，既有）→ `build/dist/${PRODUCT_NAME}-${VERSION}-x64.msi`
- `packaging/build-linux.sh`（新增）→ `build/dist/${PRODUCT_NAME}-${VERSION}-${ARCH}.deb` + `-app-image.tar.gz`（共享 jlink runtime；deb 硬依赖 dpkg/fakeroot，须 Linux 构建——§5 核验一致）
- `packaging/upload-release-assets.sh`（新增）→ `gh release upload` 挂载宿主产物到既有 Release（按固定命名规范匹配；release-stable flow 只 stage 不执行——发版需用户窗口）
- `.github/workflows/auto-release.yml`：三平台矩阵（§6 推荐矩阵落地）——package-macos（arm64+x64 dmg）+ package-windows（x64 msi）+ **package-linux（新增，ubuntu-24.04 deb+app-image）**，release job 收集 jar+dmg+msi+deb+app-image 全量资产挂 GitHub Release + COS 镜像
- 品牌参数化：全部脚本从 `brand.conf` 读 productName/lowerName（L2 rebrand 单点改）；修复了 BSD sed `[[:space:]]#` 单字符匹配导致的值尾随空格 bug（镜像 CI 双 sed 形式 + 尾 trim）
- 命名规范固定：`${PRODUCT_NAME}-${VERSION}-<arch>.{dmg,msi,deb}` + `-app-image.tar.gz`（跨平台一致）

**已落地（release-stable flow，定义层 ~/.nebflow @1519d38）**：
- flow DAG 插入 `packager` 节点（coder → packager → reviewer）：打包 host 原生安装器 → 验可安装（macOS hdiutil 挂载 + .app bundle）→ 确认 CI 三平台矩阵 → stage 资产上传命令 → FlowReport pass/ready/blocked
- reviewer Phase 2 清单增 §6 Desktop Artifacts 校验；coder 节点逻辑零改动

**已实跑验证（macOS arm64 本机）**：
- `sbt assembly`（46MB jar）→ `build-dmg.sh` → `Nebflow-1.4.1-beta.51-arm64.dmg`（77M）
- hdiutil attach 挂载成功（/Volumes/Nebflow）→ .app bundle 结构完整（Contents/MacOS/Nebflow + Info.plist + icns）→ 二进制直跑 CLI 冒烟（`nebflow v1.4.1-beta.51` + usage，exit 0）
- 当前品牌仍为 Nebflow（rebrand 未应用）——脚本全 brand.conf 驱动，更名后零脚本改动

**待用户窗口实跑**：Windows msi 安装、Linux deb/app-image 安装、GitHub Release 资产挂载发布（push release 分支触发 CI 全矩阵）——脚本+CI 已就绪，本机（macOS）无法实跑 Windows/Linux 产物安装。

## Executive summary

对零预算的 Nebflow（Scala 3 + Pekko、sbt 构建、46MB fat jar）而言，完整可行方案是：**sbt-assembly 出 fat jar → 在 GitHub Actions 三平台矩阵上裸调 jpackage CLI**（macOS 出 dmg、Windows 出 msi、Linux 出 deb + app-image tar.gz），runtime 用 jpackage 默认模块集并显式补 `jdk.unsupported` 与 `--add-opens`，更新检查走自研（"sparkless"）路线——比对 GitHub `releases/latest`。全程零现金成本的前提是公开仓库 + 标准 runner（避开 `-large`/`-xlarge` 标签）。深层约束有三个：(1) jlink 从根本上拒收 Scala/Pekko 的 automatic module（已双重复现），深度模块化裁剪不成立，且 sbt 生态至今没有 jpackage 插件（请求 issue 已挂 5 年多），裸调 CLI 是唯一现实路径；(2) macOS 不签名不公证在 jpackage 层面完全可行且可后置，代价是 macOS 15 Sequoia 起用户必须在「系统设置 > 隐私与安全性」手动放行，Apple 公证是 $99/年才能解锁的唯一体验项；(3) Windows 侧 JDK 版本与 WiX 工具链必须绑定决策——JDK 21 只能用已归档（EOL）的 WiX 3.14，JDK 24+ 支持维护中的 WiX 4/5（WiX 5/6 兼容 bug 已在 JDK 25 修复，JDK 24 仍受影响），无签名 msi 初期必吃 SmartScreen 警告，可用 Certum €49/年开源证书缓解。

## 1. 构建主路径：sbt-assembly + 裸调 jpackage CLI（唯一现实路线）

**jpackage 基本盘**（[JDK 21 手册页](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html)、[JDK 24 手册页](https://docs.oracle.com/en/java/javase/24/docs/specs/man/jpackage.html)，原文核验）：
- `--type` 合法值全集为 {app-image, exe, msi, rpm, deb, pkg, dmg}，**没有 AppImage 目标**；无跨平台交叉编译——"Each format must be built on the platform it runs on"。
- classpath 应用模式：依赖 jar 放 `--input` 目录 + `--main-jar` 指定入口；`--java-options` 可多次传（用于注入 `--add-opens`）；`--runtime-image` 可复用预生成 runtime，或内联 jlink 并以 `--jlink-options` 传参（默认值 `--strip-native-commands --strip-debug --no-man-pages --no-header-files`）。

**sbt 生态没有 jpackage 集成**：
- sbt-native-packager 最新版 v1.11.7（2026-01-13 发布）的 main 分支源码树中不含任何 jpackage 插件文件（[仓库](https://github.com/sbt/sbt-native-packager)；验证者全量扫描确认文件数为 0）；jpackage 支持请求 [issue #1405](https://github.com/sbt/sbt-native-packager/issues/1405) 自 2021-03-16 提出至今 open。
- 其 JDKPackagerPlugin 是 Oracle JDK 8 `ant-javafx.jar`/javapackager 时代的文物，对新 JDK 不可用（[jdkpackager.rst](https://github.com/sbt/sbt-native-packager/blob/main/src/sphinx/formats/jdkpackager.rst)）；社区先例（[issue #1667](https://github.com/sbt/sbt-native-packager/issues/1667)，2025-01-15 评论原文）的结论正是改用 `sbt-assembly` + 裸调 jpackage。
- 生态对比（GitHub API 核验）：Maven 有 jpackage-maven-plugin（[Akman 版 60★](https://github.com/Akman/jpackage-maven-plugin)、[petr-panteleyev 版 47★、活跃至 2026-06](https://github.com/petr-panteleyev/jpackage-maven-plugin)），Gradle 有 [badass-jlink-plugin](https://github.com/beryx/badass-jlink-plugin)（423★，2026-08-12 仍在 push）；sbt 侧搜索仅一个 2017 年死项目（确切名为 sbt-jpackager——与"sbt-jpackage"存在细微名称偏差，验证者核对后实质结论不变：无可用等价物）。

**为什么不能走深度 jlink 模块化**（本地实测，验证者独立复现，均成立）：
- jlink 从根本上拒绝 automatic module：对 scala-library-2.13.14、scala3-library_3-3.3.4、pekko-actor_3-1.1.2（三只 jar 的 MANIFEST 均已带 `Automatic-Module-Name`，逐一解包核对）执行 `jlink --add-modules … --module-path <jar>`，在 JDK 23.0.1 与 11.0.23 上均报 "automatic module cannot be used with jlink"。jar 取自 [Maven Central](https://repo1.maven.org/maven2/org/scala-lang/scala-library/2.13.14/scala-library-2.13.14.jar)。
- 推论（官方文档佐证）：Scala/Pekko 应用唯一现实形态是 **classpath 应用**——依赖全部留在 `--input` 目录，runtime image 只含 JDK 平台模块，这正是 jpackage 对非模块化应用的默认行为（[JDK 26 指南](https://docs.oracle.com/en/java/javase/26/jpackage/packaging-overview.html)：非模块化 JAR 应用的 runtime 含与 classpath 应用相同的 JDK 模块集）。
- 更糟的边角：无 `Automatic-Module-Name` 的 jar 连模块名都无法派生（文件名中的数字段非法，直接报 "Unable to derive module descriptor"），Scala 生态实测记录见 [sbt-native-packager #1247](https://github.com/sbt/sbt-native-packager/issues/1247)（2019-07-11）。

**JlinkPlugin 的定位与边界**（[JlinkPlugin.scala 源码](https://github.com/sbt/sbt-native-packager/blob/main/src/main/scala/com/typesafe/sbt/packager/archetypes/jlink/JlinkPlugin.scala)、[jlink_plugin.rst](https://github.com/sbt/sbt-native-packager/blob/main/src/sphinx/archetypes/jlink_plugin.rst)，源码级核验）：
- 跑 `jdeps -R --multi-release <ver>` 分析 → 只把 `jdk.*`/`java.*` 平台模块 + java.base 放进 `--add-modules`；源码注释原文 "No external modules by default: see #1247"；jdeps 报缺失依赖会直接报错，需 `jlinkIgnoreMissingDependency` 豁免；必须在目标平台运行；产物是嵌 `jre/` 的 Universal/zip/deb 包，**不产出 dmg/msi 等原生安装器**。
- 已知坑（官方文档 Known issues）：显式模块依赖 automatic module 时报 `FindException: Module X not found`，需手工把缺的 jar 塞进 `jlinkModulePath`。
- 结论：JlinkPlugin 只在"还要 sbt-native-packager 的 zip/deb 顺带嵌 jre"场景有价值，与 dmg/msi/app-image 产物线正交；可经 jpackage `--runtime-image` 组合使用，但非必需。

**实操形态（综合建议）**：自写一个薄 sbt task（依赖 assembly 产物拼 jpackage 命令行），比给 JlinkPlugin 打补丁更简单可控。

## 2. Runtime 裁剪：必检项与已知坑

- **`jdk.unsupported` 是 Scala/Pekko 的静态硬依赖**（jdeps 实测 + 验证者复现）：`org.apache.pekko.actor`（及 dungeon、dispatch 包）→ `sun.misc JDK internal API (jdk.unsupported)`；`scala.runtime → sun.misc` 同样。若模块清单漏掉 jdk.unsupported，启动即 NoClassDefFoundError。**必须显式纳入。**
- **`--add-opens` 建议**：Pekko 官方 [Unsafe.java](https://github.com/apache/pekko/blob/main/actor/src/main/java/org/apache/pekko/util/Unsafe.java) 用 `MethodHandles.privateLookupIn` + `findVarHandle` 访问 `String.value`，源码注释原文 "You need `--add-opens=java.base/java.lang=ALL-NAMED` or similar to access it"（原文为 ALL-UNNAMED）；不给则优雅降级为慢速算法——静默性能损失比崩溃更难察觉。jpackage 侧经 `--java-options "--add-opens java.base/java.lang=ALL-UNNAMED"` 注入。（验证修正：Unsafe.java 本身是 VarHandle 实现而非 sun.misc.Unsafe；jdk.unsupported 依赖来自 pekko-actor 其他类，jdeps 复现已证实依赖成立，结论不受影响。）
- **JDK 25 起 runtime image 不再包含 service bindings**（[JDK 26 指南](https://docs.oracle.com/en/java/javase/26/jpackage/packaging-overview.html)原文核验）："In JDK 25 and later, the generated runtime image doesn't include service bindings. You can add them with the --jlink-options option and passing it the --bind-services"。迁移 JDK 24→25+ 时这是必检项。
- **jdeps 静态盲区**：反射、ServiceLoader、crypto provider 均不可见。典型运行时故障：漏 `jdk.crypto.ec` → TLS `SSLHandshakeException`（[社区报告](https://stackoverflow.com/questions/55439599/sslhandshakeexception-with-jlink-created-runtime)，未二次核验）；该坑的时效边界已核验——[JDK-8308602](https://bugs.openjdk.org/browse/JDK-8308602) "Move SunEC crypto provider into java.base"，fix version 22：**JDK 22 起此坑消失**。另一[社区报告](https://stackoverflow.com/questions/60507256/jre-created-via-jlink-missing-some-security-certificates-cacerts)称 jlink 会裁剪 cacerts 导致部分证书信任失败（未核验，实施前自测）。
- **jdeps 对不完整 classpath 直接罢工**（实测 + 复现，逐字一致）：缺 `com.typesafe.config` 时报错要求 `--ignore-missing-deps`——这正是 JlinkPlugin 提供 `jlinkIgnoreMissingDependency` 豁免机制的原因。
- **46MB fat jar 的 Pekko 特有要求**：fat jar 必须合并各依赖的 `reference.conf`（sbt-assembly 用 concat/first 等策略），否则运行时配置缺失报错（[Apache Pekko · Packaging 官方文档](https://pekko.apache.org/docs/pekko/current/additional/packaging.html)）。fat jar 大小对 jpackage 产物结构无任何影响（只是拷进 app/ 目录）；真正的风险在合并策略。
- **工程应对（综合建议）**：固定 runtime 模块清单并纳入集成测试（真实启动 + TLS 连接 + ActorSystem 初始化的冒烟测试），不信任任何自动推导——模块缺失类故障的共同特征是"构建期静默、运行期爆炸"。

## 3. macOS：签名/公证在构建侧完全可跳过，代价全在分发体验

**jpackage 层面机制**（全部核验）：
- `--mac-sign` 为 opt-in 开关；配套 `--mac-signing-key-user-name` / `--mac-signing-keychain` / `--mac-package-signing-prefix` / `--mac-entitlements` / `--mac-app-store` 均只在传入 `--mac-sign` 时生效（[JDK 21 手册页](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html) + [core-libs-dev 维护者邮件](https://mail.openjdk.org/pipermail/core-libs-dev/2026-February/159413.html)原文）。较新的 `--mac-app-image-sign-identity` / `--mac-installer-sign-identity` 为 25/26 时代选项——JDK 24 手册页亦无（验证者补充核对）；精确引入版本未定位。
- **jpackage 不做 notarization 与 staple**——jpackage 维护者（Alexey Semenyuk, Oracle）原文 "it doesn't notarize and staple the package"；JDK 21/24 手册页均无任何 notarization 选项（[邮件](https://mail.openjdk.org/pipermail/core-libs-dev/2026-February/159413.html)、[JDK 21](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html)/[JDK 24](https://docs.oracle.com/en/java/javase/24/docs/specs/man/jpackage.html) 手册页全文核对）。完整 macOS 签名链需 3 张证书（Developer ID Application 打 .app、Developer ID Installer 打 .pkg、标准证书签 jar）。
- 不传 `--mac-sign` 时 app bundle 仍**自动 ad-hoc 签名**（dmg/app-image/pkg 内的 .app 均如此；PKG 安装器本身不 ad-hoc 签）——JDK 19 引入 aarch64（JDK-8277493）、JDK 20 扩展 x64（JDK-8298488）（[jpackage 开发者 2023-08-11 回复](https://www.mail-archive.com/core-libs-dev@openjdk.org/msg18794.html)、[同线程](https://mail.openjdk.org/pipermail/core-ls-dev/2023-August/110310.html)）。
- **签名可后置**：jpackage 支持 `--type app-image --app-image <已有镜像> --mac-sign` 的只重签模式（该模式只允许签名相关选项）（[JDK 21 手册页](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html)）。
- 外部工具依赖很轻：仅使用 `--mac-sign` 或 `--icon` 时才需要 Xcode command line tools（[JDK 26 指南](https://docs.oracle.com/en/java/javase/26/jpackage/packaging-overview.html)）。

**零预算分发体验**（核验）：
- [Apple Developer Program](https://developer.apple.com/programs/enroll/) 会费 **99 USD/会员年**（非营利/教育/政府可申请免除）；公证基于 Developer ID 签名软件、由 notary service 出票、Gatekeeper 凭票放行（[Apple 新闻 2024-08-06](https://developer.apple.com/news/?id=saqachfa)、[公证文档](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution)）——$99 是解锁公证（及正式签名）的唯一费用项。
- **macOS 15 Sequoia 起移除 Control-点击绕过 Gatekeeper**：未正确签名/未公证软件须走「系统设置 > 隐私与安全性」审查放行（[Apple 官方新闻 saqachfa](https://developer.apple.com/news/?id=saqachfa)，原文逐字核验："users will no longer be able to Control-click to override Gatekeeper… They'll need to visit System Settings > Privacy & Security"）。macOS 14 Sonoma 及更早仍可用右键/Control-点击 > 打开的旧流程（[Apple 支持指南 mh40616](https://support.apple.com/guide/mac-help/open-a-mac-app-from-an-unidentified-developer-mh40616/mac)；按验证报告，该页 "Works in macOS Sonoma 14 or earlier" 原句未二次逐字捕获，此结论作为合理推论保留）。
- ad-hoc 签名机制（`codesign -s -`）不使用身份但受限（[codesign(1) man page](https://www.manpagez.com/man/1/codesign/)；引文未二次核对，机制本身为标准事实）。
- tar.gz 备选对 CLI 用户能否规避 Gatekeeper 弹窗：quarantine 标记的传播细节**未核验，不能下结论**，实施前需 xattr 实测（见 Confidence & gaps）。

**小结（综合）**：零预算下 dmg + ad-hoc + 不公证完全可行且不阻塞 CI（签名/公证欠账可后置或永久不做）；产品页必须提供 Sequoia+ 的图文放行引导，否则非技术用户基本流失。

## 4. Windows：WiX 硬依赖、JDK 版本决策点与 SmartScreen

**WiX 依赖**（核验）：
- `exe`/`msi` **硬性依赖 WiX**（[JDK 26 指南](https://docs.oracle.com/en/java/javase/26/jpackage/packaging-overview.html)："WiX 3.0 or later is required"）；jpackage 的 exe 是 msi 外再包一层 bootstrap（[JDK 24 手册页](https://docs.oracle.com/en/java/javase/24/docs/specs/man/jpackage.html) post-msi.wsf 资源佐证）——"只打 exe 躲开 WiX"不成立。
- JDK ≤23（含 21 LTS）：只认 WiX 3 的 `candle.exe`/`light.exe`（≥3.0）——[jdk-21-ga 的 WixTool.java](https://github.com/openjdk/jdk/blob/jdk-21-ga/src/jdk.jpackage/windows/classes/jdk/jpackage/internal/WixTool.java) 枚举为 Candle/Light（验证修正：Candle3/Light3 是现今 master 的命名，原报告混用了两个时代，实质不变）。
- JDK 24 起支持 WiX v4/v5（`wix.exe` ≥4.0.4），与 v3 并存、自动选用已安装的最新版——[JDK-8319457](https://bugs.openjdk.org/browse/JDK-8319457)（fix version 24，[commit ba67ad63ae 2024-06-12](https://github.com/openjdk/jdk/commits/master/src/jdk.jpackage/windows/classes/jdk/jpackage/internal/WixTool.java)，JBS + GitHub API 核验；[master WixTool.java](https://github.com/openjdk/jdk/blob/master/src/jdk.jpackage/windows/classes/jdk/jpackage/internal/WixTool.java) 含 Wix4 枚举）。
- [wixtoolset/wix3 仓库已归档](https://github.com/wixtoolset/wix3)（archived=true，最后 push 2025-02-14，GitHub API 核验）→ 用 JDK 21 打 msi 必须安装已 EOL 的 WiX 3.14。
- **WiX 5/6 兼容性（验证修正，替换原报告过时定性）**：[JDK-8356592](https://bugs.openjdk.org/browse/JDK-8356592) 创建于 **2025-05-04**（原报告误写 2025-01-06），已于 **2025-05-09 以 Duplicate 关闭、修复落在 JDK 25**。即：JDK 24 + WiX 5/6 组合仍有风险；JDK 25 已修复（不回填 24）。

**SmartScreen 与低成本签名**（核验）：
- SmartScreen 是**信誉机制**：文件不在"常见高频下载名单"上即弹警告；"If there's no reputation, the item is marked as a higher risk and presents a warning to the user"（[Microsoft Defender SmartScreen overview](https://learn.microsoft.com/en-us/windows/security/operating-system-security/virus-and-threat-protection/microsoft-defender-smartscreen/) 原文核验）。无签名 msi 初期必吃警告，用户须"更多信息 > 仍要运行"；信誉随下载量积累。
- [Certum「Open Source Code Signing」](https://shop.certum.eu/open-source-code-signing-on-simplysign.html) **from €49.00**：云端 SimplySign 免实体加密卡、每月 5000 次签名上限、证书主体带 "Open Source Developer" 前缀、支持 exe/msi/jar 且可在 Linux/macOS CI 使用（商店页原文核验）——开源项目消除 Unknown Publisher / 加速 SmartScreen 信誉积累的最便宜正规路径。
- 微软 [Artifact Signing（原 Trusted Signing）](https://learn.microsoft.com/en-us/azure/trusted-signing/faq)不支持免费/试用/赞助型 Azure 订阅，必须付费订阅（FAQ 原文核验）；确切月费未能核验（[第三方称 $9.99/月，二手来源](https://www.gdgsoft.com/faq/azure-trusted-signing-cost-effective-exe-msi-code-signing)，仅供参考）。

**决策建议（综合）**：打包 JDK 版本与 WiX 路线绑定决策——JDK 21 = 锁定 EOL 的 WiX 3.14（省心但停止维护）；JDK 24+ = 维护中的 WiX 4/5（JDK 24 有 WiX 5/6 兼容风险、JDK 25 已修；且 WiX 4/5 实际可用性未在真实 Windows 环境实测）。

## 5. Linux：deb + app-image tar.gz 组合，放弃 AppImage

- jpackage 在 Linux 仅支持 `deb` 与 `rpm`（`--type` 合法值核验）。deb 硬依赖 `dpkg`、`dpkg-deb`、`fakeroot`（[LinuxDebBundler.java (jdk-21-ga)](https://github.com/openjdk/jdk/blob/jdk-21-ga/src/jdk.jpackage/linux/classes/jdk/jpackage/internal/LinuxDebBundler.java) 三个 ToolValidator，精确核验）；官方指南：Red Hat 系打 rpm 需 `rpm-build`、Ubuntu 系打 deb 需 `fakeroot`（[JDK 26 指南](https://docs.oracle.com/en/java/javase/26/jpackage/packaging-overview.html)）。
- `app-image`（app/ + runtime/ 目录 + 启动脚本）是发行版无关的通用形态，自行 tar.gz 分发作兜底（[JDK 26 指南](https://docs.oracle.com/en/java/javase/26/jpackage/packaging-overview.html)目录结构）。
- **AppImage 无官方 Java 支持路径**：需第三方工具链自行组装 AppDir（如 [linuxdeploy](https://github.com/linuxdeploy/linuxdeploy)，813★、活跃维护）；无任何主流 "jpackage→AppImage" 官方工具。投入产出比低，不建议。
- deb 必须在 Linux 上构建（fakeroot/dpkg-deb 依赖），macOS 上无法出 deb——除非走 sbt-native-packager 的 jdeb 纯 Java 路线（平台无关、可在任意 OS 打 deb，[debian.rst](https://github.com/sbt/sbt-native-packager/blob/main/src/sphinx/formats/debian.rst) 核验）；但那是另一条 sbt-native-packager 产物线，不是 jpackage。
- 旁证：sbt-native-packager 的 WindowsPlugin 同样完全绑定 WiX（`wixMajorVersion` 默认 3、可设 4，调 candle/light，[windows.rst](https://github.com/sbt/sbt-native-packager/blob/main/src/sphinx/formats/windows.rst) 核验）。

## 6. CI：GitHub Actions 三平台矩阵（公开仓库零现金成本）

**Runner 选择**（2026-08 时点，[runner-images README](https://github.com/actions/runner-images/blob/main/README.md) 核验）：
- `macos-latest` = macOS 26 **arm64**（别名 `macos-26`、`macos-26-xlarge`）；Intel x64 = `macos-latest-large` / `macos-26-intel` / `macos-26-large`；`macos-15` = arm64、x64 = `macos-15-large` / `macos-15-intel`；`windows-latest` = windows-2025（另有 `windows-2022`、`windows-11-arm`）；`ubuntu-latest` = 24.04（26.04 preview，含 `-arm`）。
- 弃用线（GitHub API 核验）：macOS 14 已进弃用窗口（2026-07-06 起弃用、[2026-11-02 完全停止支持](https://github.com/actions/runner-images/issues/13518)，含 10 月两次 brownout）；[macOS 13 已停（2025-12-04）](https://github.com/actions/runner-images/issues/13046)、同期[新增 macos-15-intel 镜像](https://github.com/actions/runner-images/issues/13045)；[Windows Server 2019 已停（2025-06-30）](https://github.com/actions/runner-images/issues/12045)。
- `-latest` 标签随镜像升级在 1–2 个月内漂移 → **所有标签锁具体版本号**，防止镜像漂移破坏 jpackage 产物。

**零预算边界**（[billing 文档](https://docs.github.com/en/billing/managing-billing-for-github-actions/about-billing-and-pricing-for-github-actions)核验）：
- 公开仓库标准 runner 免费；私有仓库 GitHub Free 2,000 分钟/月 + 500MB artifact 存储。
- **larger runner（macOS `-large`/`-xlarge`、4 核+ Windows/Linux）公开仓库也始终收费**，且需绑卡、spending limit > 0（[larger runners reference](https://docs.github.com/en/actions/reference/runners/larger-runners) 另证；macOS larger 仅两档：Intel 12 核与 arm64 M2 5 核 GPU）。超额单价：Linux 2 核 $0.006/min（arm64 $0.005）、Windows $0.010/min、macOS 3/4 核 $0.062/min（≈ Linux 10 倍）。**Intel 构建用 `-intel` 标签而非 `-large`。**（未核验点：`-intel` 标签是否完全等同免费标准档——镜像表列在标准表内、Termora 公开仓库在用，但 GitHub 未逐标签明示计费，采用前建议空 job 核对账单。）

**缓存与产物**（核验）：
- [actions/setup-java](https://github.com/actions/setup-java) v5 原生 `cache: sbt`（另支持 `cache-dependency-path`、`cache-jdk` 缓存 JDK 本体），无须手写 cache 步骤。
- [actions/upload-artifact](https://github.com/actions/upload-artifact) v4：v3 已于 2024-11-30 弃用（验证者核验具体日期）；v4 产物不可变（同名多 job 上传不支持）；默认保留 90 天。缓存 key 纳入 `runner.arch`（三平台/双架构矩阵中依赖与 JDK 均按架构区分）。

**成熟模板：Termora**（JVM 桌面应用 jpackage 全家桶；**链接已按验证报告修正为默认分支 `2.x`**——原 `/blob/main/` 路径为死链，内容已在 2.x 分支逐字核对）：
- [osx.yml](https://github.com/TermoraDev/termora/blob/2.x/.github/workflows/osx.yml)：矩阵 `os: [macos-15-intel, macos-latest]`（x64+arm64）；构建链 `:jlink → :jpackage → :dist`；缓存 key 基于 `runner.os`/`runner.arch`/构建文件哈希；macOS 证书从 secrets 以 base64 导入临时 keychain、notarytool store-credentials + secret、仅发布 commit 触发公证；按架构命名上传 dmg。
- [windows.yml](https://github.com/TermoraDev/termora/blob/2.x/.github/workflows/windows.yml)：矩阵 `os: [windows-11-arm, windows-2022]`；zip/exe/msix 按架构上传。
- [linux.yml](https://github.com/TermoraDev/termora/blob/2.x/.github/workflows/linux.yml)：deb/tar.gz 上传（此文件验证者未单独逐字核对，低风险）。

**推荐矩阵（综合）**：`macos-latest`（arm64 dmg）+ `macos-15-intel`（x64 dmg）+ `windows-2022`（msi；比 windows-2025 更接近存量用户系统）+ `ubuntu-24.04`（deb + app-image tar.gz），全部锁版本标签。

## 7. 更新检查：自研（"sparkless"）路线成立

- 基础端点：`GET /repos/{owner}/{repo}/releases/latest` 公开仓库免认证，返回最新非 draft/prerelease release 及资产列表（[GitHub REST releases 文档](https://docs.github.com/en/rest/releases/releases)；验证者独立复现 cli/cli → v2.97.0）。
- 生态现状（GitHub API 核验，日期/星数精确吻合）：
  - [Sparkle](https://github.com/sparkle-project/Sparkle)（macOS 标准框架）：活跃（2026-08-13 push，9,468★）；EdDSA + Apple Code Signing 双重校验、服务端只需静态 appcast、支持增量更新——但为原生框架，JVM 应用接入成本高。
  - [WinSparkle](https://github.com/vslavik/winsparkle)（Windows 对应，MIT）：活跃（2026-08-10 push）；强制更新包签名（EdDSA/Ed25519，appcast 中 `sparkle:edSignature`，DSA 已弃用）——同样原生集成。
  - [update4j](https://github.com/update4j/update4j)（JVM 原生常被推荐）：**已 archived**（2024-03-18 最后 push）——不宜新采用。
  - [Squirrel.Windows](https://github.com/Squirrel/Squirrel.Windows)：README 置顶找维护者（2024-07-24 最后 push）——不宜新采用。
  - [GetDown](https://github.com/threerings/getdown)（JVM 启动器/更新器）：仍在维护（2026-05-12 push）——可作备选评估。
- **结论（综合）**：对 Nebflow 最务实的是 "sparkless" 自研路线：启动时（或经已有远程指令通道下发命令）调 `releases/latest` 比对 tag → 提示下载对应平台资产 URL；配合 GitHub Release 资产的稳定下载链接，无需自建服务器、无需引入更新包签名体系。注意：Nebflow 已有远程更新指令通道属内部事实，无外部来源，需团队内确认其鉴权与触发方式。

## 8. 综合推荐方案（基于已验证事实的工程综合）

1. **构建**：sbt-assembly 出 46MB fat jar（重点测试 reference.conf 合并策略）。
2. **打包**：自写薄 sbt task / CI 步骤裸调 jpackage：`--input <fat jar 目录> --main-jar <fat.jar>`，`--add-modules` 在默认模块集之上显式纳入 `jdk.unsupported`，`--java-options "--add-opens java.base/java.lang=ALL-UNNAMED"`。macOS 出 dmg（不传 `--mac-sign`，自动 ad-hoc）；Windows 出 msi（WiX 路线随 JDK 版本决策，见 §4）；Linux 出 deb + app-image tar.gz。
3. **CI**：GitHub Actions 公开仓库标准 runner；矩阵 `macos-latest + macos-15-intel + windows-2022 + ubuntu-24.04`（锁标签、避开 `-large`/`-xlarge`）；setup-java `cache: sbt` + `cache-jdk`；upload-artifact v4 按 `{os}-{arch}` 命名；正式分发走 GitHub Release 资产。
4. **质量门**：固定 runtime 模块清单 + 冒烟集成测试（真实启动 + TLS + ActorSystem 初始化）；升级 JDK 25+ 时检查 `--bind-services`。
5. **分发体验**：macOS 产品页图文引导 Sequoia+ 系统设置放行（Sonoma 14- 用户右键打开）；Windows 初期以文案引导 SmartScreen"仍要运行"，可选 €49/年 Certum OSS 签名消除 Unknown Publisher。
6. **更新**：自研 `releases/latest` 比对 + 平台资产下载引导。
7. **后置升级路径（预算出现时）**：$99/年 Apple Developer → `--mac-sign` 重签 + notarytool 公证（jpackage 出 app-image 后签名可后置，不阻塞现有流水线）；Certum → msi 签名。

## Confidence & gaps

**高置信（验证者重取来源或独立复现）**：本报告 §1–§7 中标注"核验"的全部断言，包括：jpackage 选项集与无 notarization、ad-hoc 签名行为、WiX 版本门槛与 JDK 对应关系、wix3 归档、deb 工具链硬编码、sbt-native-packager 无 jpackage 插件、jlink 拒收 automatic module / jdk.unsupported 依赖 / --ignore-missing-deps 行为（同环境独立复现）、Pekko Unsafe.java 的 add-opens 注释、JDK-8308602 (fix 22)、runner 镜像表与弃用时间线、计费边界、setup-java/upload-artifact 能力、Termora 模板内容、Apple $99 与 Sequoia Gatekeeper 变更、SmartScreen 信誉机制、Certum 条款、Artifact Signing 付费订阅要求、更新框架五个项目的活跃度、releases/latest 端点行为。

**已按验证报告修正的 4 点**：
1. **JDK-8356592**：创建日期 2025-05-04（原误 2025-01-06）；已于 2025-05-09 以 Duplicate 关闭、修复进 JDK 25 → "WiX 5/6 存在未修 bug" 改写为 "JDK 24 有风险、JDK 25 已修"。
2. **Termora workflow 链接**：`main` 分支改为 `2.x`（原为死链）。
3. **三处小偏差**：jdk-21-ga 的 WixTool 枚举名为 Candle/Light（Candle3/Light3 是 master 现名）；`--mac-package-signing-prefix` 默认值 `.` 未见于 JDK 21 手册页（已删除该断言）；Pekko Unsafe.java 实为 VarHandle 实现，jdk.unsupported 依赖来自 pekko-actor 其他类（依赖本身经复现成立）。
4. **两处引文降级**：mh40616 的 "Works in macOS Sonoma 14 or earlier" 与 codesign man page 的 ad-hoc 引文未二次逐字核对，本报告仅作间接结论/标准机制陈述，不作为直接引语。

**未核验 / 开放问题**：
- tar.gz + curl 下载能否规避 Gatekeeper（quarantine 标记传播机制未核验，实施前需 `xattr` 实测）；macOS 26 (Tahoe) 是否进一步收紧 Gatekeeper 无可核验来源。
- Artifact Signing 确切价格（$9.99/月仅二手来源）与个人准入条件；`-intel` 标签计费档位（建议公共仓库空 job 核账单）。
- JDK 17 行为未实测（jpackage 签名选项集与 21 之间可能有差异，按官方 21/24 文档外推）；`--mac-app-image-sign-identity` / `--mac-installer-sign-identity` 精确引入版本未定位（确认 25/26 时代，JDK 24 手册页亦无）。
- WiX 4/5 + JDK 24/25 组合未在真实 Windows 环境实测；linuxdeploy 打 Java AppImage 的完整流程未实测。
- jdeps 实测仅覆盖 pekko-actor_3 1.1.2 / scala3-library_3 3.3.4 / scala-library 2.13.14 三只 jar；pekko-http、pekko-stream 等其余依赖未逐一验证（预期同类：jdk.unsupported + java.base/java.net.http）。
- JlinkPlugin 在 JDK 17/21 下的 jdeps 输出解析兼容性未实测（其解析逻辑自 JDK 11 时代）。
- 次要佐证未二次核验（不作为直接引语）：jdk.crypto.ec 缺失的 TLS 故障表现与 cacerts 裁剪（社区报告；前者机制已由 JDK-8308602 覆盖）、Termora linux.yml 逐字内容、update4j 的 Maven Central 止于 1.5.9、Sparkle 文档站 EdDSA 配置页、LinuxRpmBundler 的 rpmbuild 硬编码（官方指南已独立覆盖 rpm-build 需求）。

## Sources

**Oracle 官方文档**
- [The jpackage Command (JDK 21)](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html) — used for: --type 合法值、无交叉编译、--mac-sign opt-in 与签名选项集、只重签模式、--runtime-image/--jlink-options 默认值、--java-options、--add-modules（§1、§3）
- [The jpackage Command (JDK 24)](https://docs.oracle.com/en/java/javase/24/docs/specs/man/jpackage.html) — used for: 21/24 均无 notarization 选项、JDK 24 亦无两个新身份选项、exe = msi + bootstrap、WiX v4 佐证（§3、§4）
- [Packaging Tool User's Guide (JDK 26)](https://docs.oracle.com/en/java/javase/26/jpackage/packaging-overview.html) — used for: WiX 3.0+ required、Xcode CLT 条件、rpm-build/fakeroot、JDK 25+ service bindings、非模块化应用 runtime 模块集、app-image 结构（§1、§2、§3、§4、§5）

**OpenJDK 邮件列表**
- [core-libs-dev: macOS pkg signing full chain workflow (2026-02-23, Semenyuk)](https://mail.openjdk.org/pipermail/core-libs-dev/2026-February/159413.html) — used for: jpackage 不做 notarization/staple、签名选项仅 --mac-sign 时生效、3 证书工作流、两个新身份选项存在性（§3）
- [core-libs-dev: Matveev 回复 (2023-08-11)](https://www.mail-archive.com/core-libs-dev@openjdk.org/msg18794.html) — used for: 自动 ad-hoc 签名行为、JDK-8277493/8298488（§3）
- [core-libs-dev 同线程 (2023-08)](https://mail.openjdk.org/pipermail/core-libs-dev/2023-August/110310.html) — used for: ad-hoc 签名行为补充（§3）

**OpenJDK 源码与 JBS**
- [WixTool.java (master)](https://github.com/openjdk/jdk/blob/master/src/jdk.jpackage/windows/classes/jdk/jpackage/internal/WixTool.java) — used for: Candle3/Light3/Wix4 枚举与版本门槛（§4）
- [WixTool.java 提交历史（ba67ad63ae, 2024-06-12）](https://github.com/openjdk/jdk/commits/master/src/jdk.jpackage/windows/classes/jdk/jpackage/internal/WixTool.java) — used for: WiX v4/v5 支持合入时间（§4）
- [LinuxDebBundler.java (jdk-21-ga)](https://github.com/openjdk/jdk/blob/jdk-21-ga/src/jdk.jpackage/linux/classes/jdk/jpackage/internal/LinuxDebBundler.java) — used for: deb 的 dpkg/dpkg-deb/fakeroot 硬依赖（§5）
- [JDK-8319457](https://bugs.openjdk.org/browse/JDK-8319457) — used for: WiX v4/v5 支持 fix version = 24（§4）
- [JDK-8356592](https://bugs.openjdk.org/browse/JDK-8356592) — used for: WiX 5/6 兼容问题时间线（修正后）（§4）
- [JDK-8308602](https://bugs.openjdk.org/browse/JDK-8308602) — used for: SunEC 并入 java.base（fix 22）→ jdk.crypto.ec 坑的时效边界（§2）
- [wixtoolset/wix3 (archived)](https://github.com/wixtoolset/wix3) — used for: WiX 3 停止维护（§4）

**sbt-native-packager**
- [sbt/sbt-native-packager 仓库](https://github.com/sbt/sbt-native-packager) — used for: v1.11.7 (2026-01-13)、main 分支无 jpackage 插件（§1）
- [issue #1405](https://github.com/sbt/sbt-native-packager/issues/1405) — used for: jpackage 支持请求 2021-03-16 起仍 open（§1）
- [issue #1667](https://github.com/sbt/sbt-native-packager/issues/1667) — used for: 社区先例 sbt-assembly + jpackage（§1）
- [issue #1247](https://github.com/sbt/sbt-native-packager/issues/1247) — used for: automatic module 模块名派生失败、JlinkPlugin 默认不放外部模块的背景（§1）
- [jdkpackager.rst](https://github.com/sbt/sbt-native-packager/blob/main/src/sphinx/formats/jdkpackager.rst) — used for: JDKPackagerPlugin = Oracle JDK 8 文物（§1）
- [JlinkPlugin.scala](https://github.com/sbt/sbt-native-packager/blob/main/src/main/scala/com/typesafe/sbt/packager/archetypes/jlink/JlinkPlugin.scala) — used for: jdeps 调用、只收 jdk.*/java.*、"No external modules by default"（§1）
- [jlink_plugin.rst](https://github.com/sbt/sbt-native-packager/blob/main/src/sphinx/archetypes/jlink_plugin.rst) — used for: Known issues、jlinkIgnoreMissingDependency（§1、§2）
- [windows.rst](https://github.com/sbt/sbt-native-packager/blob/main/src/sphinx/formats/windows.rst) — used for: WindowsPlugin 绑定 WiX、wixMajorVersion（§5）
- [debian.rst](https://github.com/sbt/sbt-native-packager/blob/main/src/sphinx/formats/debian.rst) — used for: native 五件套 vs jdeb 纯 Java 路线（§5）

**生态与第三方**
- [linuxdeploy/linuxdeploy](https://github.com/linuxdeploy/linuxdeploy) — used for: AppImage 第三方工具链（§5）
- [Akman/jpackage-maven-plugin](https://github.com/Akman/jpackage-maven-plugin)、[petr-panteleyev/jpackage-maven-plugin](https://github.com/petr-panteleyev/jpackage-maven-plugin)、[beryx/badass-jlink-plugin](https://github.com/beryx/badass-jlink-plugin) — used for: Maven/Gradle 生态对比（§1）
- [Apache Pekko · Packaging](https://pekko.apache.org/docs/pekko/current/additional/packaging.html) — used for: fat jar 的 reference.conf 合并要求（§2）
- [apache/pekko Unsafe.java](https://github.com/apache/pekko/blob/main/actor/src/main/java/org/apache/pekko/util/Unsafe.java) — used for: privateLookupIn/VarHandle 与 --add-opens 注释（§2）
- [Maven Central: scala-library 2.13.14](https://repo1.maven.org/maven2/org/scala-lang/scala-library/2.13.14/scala-library-2.13.14.jar) — used for: 本地实测素材来源（§1、§2 的 jlink/jdeps 实验经 Track A 与验证者双重复现）

**社区报告（未二次核验，文中已标注）**
- [Stack Overflow #55439599](https://stackoverflow.com/questions/55439599/sslhandshakeexception-with-jlink-created-runtime) — used for: jdk.crypto.ec 缺失 → TLS 故障表现（§2）
- [Stack Overflow #60507256](https://stackoverflow.com/questions/60507256/jre-created-via-jlink-missing-some-security-certificates-cacerts) — used for: cacerts 裁剪报告（§2）

**GitHub Actions / GitHub**
- [actions/runner-images README](https://github.com/actions/runner-images/blob/main/README.md) — used for: 镜像表、-latest 漂移 1–2 个月（§6）
- [runner-images #13518](https://github.com/actions/runner-images/issues/13518)、[#13046](https://github.com/actions/runner-images/issues/13046)、[#13045](https://github.com/actions/runner-images/issues/13045)、[#12045](https://github.com/actions/runner-images/issues/12045) — used for: macOS 14/13、Windows 2019 弃用时间线与 macos-15-intel 新增（§6）
- [GitHub Actions billing](https://docs.github.com/en/billing/managing-billing-for-github-actions/about-billing-and-pricing-for-github-actions) — used for: 公开仓库免费、larger runner 收费、超额单价（§6）
- [Larger runners reference](https://docs.github.com/en/actions/reference/runners/larger-runners) — used for: larger runner 档位与 spending limit（§6）
- [actions/setup-java](https://github.com/actions/setup-java) — used for: cache: sbt、cache-jdk（§6）
- [actions/upload-artifact](https://github.com/actions/upload-artifact) — used for: v3 弃用（2024-11-30）、v4 不可变、90 天保留（§6）
- [REST API: releases](https://docs.github.com/en/rest/releases/releases) — used for: releases/latest 端点（§7）

**Termora 模板（2.x 分支，链接已修正）**
- [osx.yml](https://github.com/TermoraDev/termora/blob/2.x/.github/workflows/osx.yml) — used for: macOS 双架构矩阵、jlink→jpackage→dist、证书/公证流程（§6）
- [windows.yml](https://github.com/TermoraDev/termora/blob/2.x/.github/workflows/windows.yml) — used for: Windows 双架构矩阵与产物命名（§6）
- [linux.yml](https://github.com/TermoraDev/termora/blob/2.x/.github/workflows/linux.yml) — used for: deb/tar.gz 上传（未单独逐字核对）（§6）

**Apple / macOS**
- [Apple Developer Program Enrollment](https://developer.apple.com/programs/enroll/) — used for: $99/年与费用免除（§3）
- [Apple Developer News: saqachfa (2024-08-06)](https://developer.apple.com/news/?id=saqachfa) — used for: Sequoia 移除 Control-click 绕过、公证建议（§3）
- [Notarizing macOS software before distribution](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution) — used for: 公证机制（§3）
- [mh40616: Open a Mac app from an unidentified developer](https://support.apple.com/guide/mac-help/open-a-mac-app-from-an-unidentified-developer-mh40616/mac) — used for: Sonoma 14 及更早的旧流程（原句未二次核对）（§3）
- [codesign(1) man page](https://www.manpagez.com/man/1/codesign/) — used for: ad-hoc 签名机制（引文未二次核对）（§3）

**Windows / SmartScreen / 代码签名**
- [Microsoft Defender SmartScreen overview](https://learn.microsoft.com/en-us/windows/security/operating-system-security/virus-and-threat-protection/microsoft-defender-smartscreen/) — used for: 信誉机制（§4）
- [Certum: Open Source Code Signing (SimplySign)](https://shop.certum.eu/open-source-code-signing-on-simplysign.html) — used for: €49、5000 次/月、OSS 前缀、CI 可用（§4）
- [Artifact Signing FAQ (Microsoft Learn)](https://learn.microsoft.com/en-us/azure/trusted-signing/faq) — used for: 不支持免费 Azure 订阅（§4）
- [gdgsoft 对比文（二手来源）](https://www.gdgsoft.com/faq/azure-trusted-signing-cost-effective-exe-msi-code-signing) — used for: $9.99/月参考价（未核验）（§4）

**更新框架生态**
- [Sparkle](https://github.com/sparkle-project/Sparkle) — used for: 活跃度、EdDSA + Apple Code Signing、静态 appcast（§7）
- [WinSparkle](https://github.com/vslavik/winsparkle) — used for: 活跃度、强制 EdDSA 签名（§7）
- [update4j (archived)](https://github.com/update4j/update4j) — used for: 已归档（§7）
- [Squirrel.Windows](https://github.com/Squirrel/Squirrel.Windows) — used for: 找维护者状态（§7）
- [GetDown](https://github.com/threerings/getdown) — used for: 仍在维护（§7）

**本地实验**（Track A 原始实测 + 验证者同环境独立复现；macOS arm64，Temurin OpenJDK 23.0.1 与 11.0.23，jar 取自 Maven Central，2026-08-15）— used for: jlink 拒收 automatic module、三只 jar 的 Automatic-Module-Name、jdeps 的 jdk.unsupported 输出、--ignore-missing-deps 行为（§1、§2）
