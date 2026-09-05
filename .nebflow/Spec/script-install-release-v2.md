# 脚本安装发布方案 v2·规划文档

> 状态：**draft v1**（2026-09-06，待作者拍板；拍板后按 §三 分批派发，逐批勾销）
> 版本记录：v1 = 2026-09-06 初稿。文件名中 v2 = **方案世代**（脚本安装线第二版），非文档版本族；本文档修订一律原地更新并在本头部登记，禁止 -v2/-v3 副本（Spec/README.md 归并规范）。
> 输入基线：① **作者 2026-09-06 01:40 裁定**——桌面端（dmg/msi 双击安装）因软件证书未申报、安装风险提示劝退用户，发布形态回退「脚本安装」保可用；桌面端代码与发布线**封存不废**，证书申报到位后恢复（作者明示「把这些证书申请到之后还是要使用」）。② **盘点节点 n-e6f38a7b《脚本安装发布现状·只读盘点证据报告》**（下称「盘点」）——本文现状结论全部引之（文件:行号/URL 可溯），不重查；其标注的缺口如实沿用为缺口。
> 关联文档：`.nebflow/Spec/desktop-trust-and-icp-filing-plan.md`（draft v2，证书/备案并行线唯一基线，下称「信任链 spec」）
> 边界：本文档**零代码改动、零发布动作、零 COS 上传、不动官网仓（nebflow-website，沙箱外）、不 push**——只做规划与拍板清单。

---

## 〇、总览

**一句话方案**：发布形态回退「脚本安装保可用」——v2 = 脚本链修致命缺陷 + 依赖全量切 COS + PowerShell 乱码根治 + 品牌横幅 + 官网入口改造；桌面端打包 CI 冻结封存（证书到位按信任链 spec 恢复），证书申报并行线**不停**。

**目标设计 → 节 → 决策点对照**：

| 作者需求 | 节 | 关联拍板点 |
|---|---|---|
| ① 跨平台安装脚本 v2（单脚本体验、全依赖自动检测+补装） | §2.1 | D5 |
| ② 依赖全量切腾讯云 COS（映射表） | §2.2 | D2、D3、D4 |
| ③ PowerShell 中文乱码修复 | §2.3 | D5 |
| ④ 脚本美化：横幅 logo #07C160 + 双语标语 | §2.4 | D5、D6 |
| ⑤ 安装后形态推荐（zip 便携 vs JVM+jar） | §2.5 | D1 |
| ⑥ 官网下载页改造口径（只出文案） | §2.6 | D9、D10、D11 |
| ⑦ beta 发布流调整（beta.55 起） | §2.7 | D7、D8 |
| ⑧ 桌面端封存边界（停用 vs 并行线） | §2.8 | D8、D9 |

**三个前置阻塞**（盘点「给下游规划节点的一句话」，本文已全部收编进批次设计）：
1. main 版 install.sh wrapper heredoc 缺陷必须先修（盘点 §1.4，本文批 0）；
2. 定「脚本唯一真源 + 部署通道」——现网旧版含死兜底，main 新版从未下发（盘点 §1.1，本文批 6 + D11）；
3. 官网安装文档页重写——桌面死链/虚构 snap → 脚本一条线命令为主入口（盘点 §五，本文批 6）。

**批次速览**：批 0 前置修复 → 批 1 脚本 v2 主体（依赖检测+补装）→ 批 2 依赖全量 COS → 批 3 乱码根治 → 批 4 品牌横幅 → 批 5 CI 封存 → 批 6 官网改造（nebflow-website 派单）→ 批 7 存量 msi 用户出路。最小可用闭环 = 批 0-2（+批 6 的最小线命令）。

---

## 一、现状盘点（证据引自盘点 n-e6f38a7b）

### 1.1 脚本双版本事实与部署缺口

- 主仓 `release/` 共 4 脚本：install.sh（17,089B）、install.ps1（22,532B）、uninstall.sh/ps1。主仓版有品牌块、COS 单源（`release/install.sh:14-18` 注释引 #29）、区域检测（`install.sh:199-244`）、语音模型（:382-414）、JDK 17.0.19_10。
- **线上 nebflow.space 部署的是旧一代**（byte-compare 实证）：GitHub API 版本解析 + GH Release 下载兜底（#29 后已死）、JDK 17.0.13_11、无品牌块/区域检测/语音模型、rg 走官网自托管 `https://nebflow.space/tools/rg-windows.zip`。
- **部署缺口根因**：主仓无任何 workflow 上传脚本至 nebflow.space（CI 只传 COS，盘点 §二.2）；网站属 nebflow-website 仓（沙箱外），「谁在何时把旧版脚本放上站点」无法从本仓取证（盘点·未决项 1）。
- `nebflow update` / RemoteUpdateAction 每次拉的就是**线上旧版**（`brand.conf:12` installUrl → `Branding.scala:87,137-138`）→ 真源下发后 update 机制自动受益。

### 1.2 PowerShell 中文乱码根因（P1-P6）

三层叠加：**P5 分发链路解码**（`irm …/install.ps1 | iex`，Vercel 静态默认 `application/octet-stream` 无 charset，PS 5.1 按非 UTF-8 兜底解码，乱码发生在字符串解码阶段、早于脚本执行）⊕ **P1 文件编码**（install.ps1 UTF-8 无 BOM，PS 5.1 按系统 ANSI 代码页 GBK/CP936 解码）⊕ **P2/P3 非 ASCII 内容**（盲文横幅 6 行 `release/install.ps1:66-71`、中文注释、em-dash）。P4：全 release/ 零编码处理语句（Grep 无匹配）。P6：PS 7 不受影响，Windows「Open PowerShell」默认即 5.1。

**关键结论：文件侧加 BOM 治不了线上主路径**（irm|iex 时文件尚不存在）；内容 ASCII 化是唯一覆盖全路径的根治（盘点 §1.3）。

### 1.3 wrapper heredoc 致命缺陷（main 版，已模拟复现）

`release/install.sh:337-346`：`cat > "${wrapper}" << 'WRAPPER'` **引号包裹分隔符** = 不展开变量 → 生成的 wrapper 内 `${LOWER_NAME}`/`${PRODUCT_NAME}` 为字面量 → 运行时 glob 变 `-assembly-*.jar` 永不匹配 → 报错退出（exit=1，/tmp 同构复现）。**main 版一旦下发，所有新装 macOS/Linux 用户的 `nebflow` 命令直接不可用**；线上旧版无此问题（heredoc 内硬编码）。

### 1.4 依赖×源底稿（v2 COS 映射的依据）

| 依赖 | 平台 | 现源（main 版锚点） | 国内可达性 |
|---|---|---|---|
| JDK 17 msi（win） | win x64 | tuna `mirrors.tuna.tsinghua.edu.cn/Adoptium/17/...17.0.19_10.msi`（`install.ps1:136`）+ huaweicloud 17.0.2 备选（:137，**不同发行构建**）+ adoptium.net 手动兜底（:158,:168） | ✅ / ✅（异版） |
| Git for Windows 2.55.0.3 | win x64 | npmmirror（:249 ✅）→ ghproxy（:250）→ github（:251）三级 | ✅ |
| rg 14.1.1 | win x64 | github（:360）→ ghproxy（:362），**无国内直连镜像** | ❌ 头号候选 |
| rg 14.1.1 | mac arm64/x64、linux x64 musl | github（`install.sh:300-306`）→ ghproxy（:315）；**linux arm64 无对应包** | ❌ |
| JDK 21（mac） | mac | brew（:94,:115）；Homebrew 本体 `raw.githubusercontent.com/.../install.sh`（:109） | ❌ 海外需梯子 |
| JDK 21（linux） | linux | apt/dnf/yum/apk 发行版源（:128-143）✅；adoptium api 兜底（:154） | ✅ / ❌ 兜底 |
| Nebflow jar | 全平台 | COS（`install.sh:14,:59`、`install.ps1:14,:62-63`） | ✅（实测 200） |
| Whisper base | 全平台 | hf-mirror（`install.ps1:399` ✅）/ huggingface（:401） | ✅（hf-mirror） |

**结论**（盘点 §三）：真正海外-only 的缺口 = ①rg 各平台包 ②Homebrew bootstrap ③adoptium api 兜底。JDK/Git 已有国内源；jar/指针已在 COS。

### 1.5 COS 资产先例（beta.52/.53 实证）

- 桶 `nebflow-releases-1411212853`（`brand.conf:19` 唯一事实源）；双端点：客户端 `cos.ap-nanjing.myqcloud.com`、CI 上传 `cos.accelerate.myqcloud.com`（`release.yml:262`）。
- **纯扁平根，无目录层级**（`release.yml:263-266`）；命名规律：小写=jar/通用（`nebflow-assembly-<version>.jar`）、大写 ProductName 开头=桌面产物（`Nebflow-<version>-arm64.dmg` 等）；指针 txt：`latest-version.txt`（=1.2.0，stable 久未真发）、`latest-beta-version.txt`（=1.4.1-beta.54）。
- 桶 listing 关闭（根 GET = AccessDenied XML，单对象公读可下）。

### 1.6 桌面端产物与 CI 现状

- jpackage 打包矩阵完整（msi 捆绑 MinGit+rg、dmg 双架构、deb/app-image），**全流程零签名零公证**（`build-dmg.sh:9`、`build-msi.sh:15-16` 注释自证；CI 全文无 codesign/notarytool/signtool）。
- CI 停/留对象（盘点 §二.3 全表）：`release.yml` 停 package-macos/windows/linux 三 job + 资产收集模式中 dmg/msi/deb/app-image 四类、留 build-jar/tag/COS jar+指针；`auto-release.yml` 同构停三 job、留 build-jar/COS/版本回写、**dry_run 模式保留**（未来打包矩阵验证器）；`ci.yml` 全留；`packaging/*.sh`+`jlink-modules.txt`+`gen-icons.py`+icons **冻结保留**。
- 运行时耦合点：`InstallLayout.scala:31-45`（bundled bash/rg 探测 + msi marker——jar 安装下天然 no-op，保留向后兼容）；`RemoteUpdateAction.scala:26-33`（**msi 安装拒绝 in-app 更新并指向下载页——封存期存量 msi 用户更新出路 = 必答题**）；`AutoStartService.scala` 与 jpackage launcher 耦合未深挖（盘点·未决项 4）。

### 1.7 官网下载页现状（线上 GET 快照 2026-09-06 01:55-01:58 UTC）

- 首页**无下载入口**（导航仅 /en、/zh、/login、docs、GitHub）；`/download`、`/en/download`、`/zh/download` 全 404。
- 唯一公开安装文档 `/en|/zh/docs/getting-started/installation`：GH Releases 死链（href=`https://github.com/nebflow`，错 org/repo）、桌面端为主（dmg 拖入/exe 向导/AppImage/**虚构 snap 渠道**——CI 从无 snap 产物）、**全页无一条线命令**。
- **缺口如实声明**：nebflow-website 仓在沙箱外（源码与部署流程未核对），本节全部来自线上快照；站点源码侧待 nebflow-website 侧补盘点。

### 1.8 证书线现状（并行线基线，引用即可）

信任链 spec draft v2 已给全：执行优先级 ①macOS 最优先（Apple Developer $99/年 + notarytool）②Windows（Certum Open Source €49/年起）③阿里云备案链 ④软著送审，四线可并行；Azure Trusted Signing 中国主体不可用、CSBR 硬件私钥强制、单证书 ≤459 天（spec §一）。现网 COS 分发与脚本链**不受备案进程影响**。

### 1.9 沿用缺口清单（盘点未决项收编，实施时闭环）

| # | 缺口 | 处置 |
|---|---|---|
| G1 | 官网脚本部署通道未知（谁部署、怎么部署） | 批 6 + D11 定 |
| G2 | COS 全量对象清单不可得（listing 关闭） | 铺 deps 时以 checksums.txt 建账 |
| G3 | `AutoStartService.scala` 与 jpackage launcher 耦合深度未审计 | 批 5 封存时顺带审计（预期 jar 路径 no-op） |
| G4 | rg 国内直连镜像可行性未实测（npmmirror 未见 rg 栏目） | 批 2 直接以 COS 镜像解决（本方案使该缺口消失） |
| G5 | Windows JDK 17 vs mac/linux JDK 21 版本分裂是否有硬约束（jar 字节码版本） | 批 1 核实，无硬约束则统一 21（见 §附） |
| G6 | beta.55 在途未发（VERSION=1.4.1-beta.55，COS 指针 beta.54，jar 404） | §2.7 走法 D7 |

---

## 二、目标设计（作者需求逐条落设计）

### 2.1 ① 跨平台安装脚本 v2

**「单脚本」口径**：每平台**一个脚本承载全部步骤**、一条命令 copy-paste 完成（检测→补装→下载→配置→启动引导）；**不合并** install.sh 与 install.ps1（两套运行时/平台习惯，合并徒增维护成本）。

设计要点：

1. **真源与版本化**：`release/` 为唯一真源；脚本头加 `SCRIPT_VERSION` 常量（安装时写入本地 manifest，供 `nebflow update` 与诊断）；全步骤幂等（重复执行安全，已装依赖跳过）。
2. **统一骨架与日志**：三平台统一阶段结构（环境检测 → 依赖补装 → jar 下载校验 → wrapper/启动器写入 → 配置初始化 → 启动引导）与日志格式（`[i]/[ok]/[warn]/[err]` 前缀，`--verbose` 全量）。
3. **依赖检测矩阵（缺啥装啥）**：检测顺序 = 命令存在 → 版本满足 → 不满足则按平台补装。Windows：git（含 bash）/JDK/rg/curl；macOS：xcode CLT（brew 前置）→ JDK21 → rg → curl；Linux：包管理器探测（apt/dnf/yum/apk）→ JDK21 → rg → curl。补装失败时给**手动兜底指引**（直链 URL + 手动放置路径 + 校验值），exit code 规范化。
4. **wrapper 缺陷修复（前置，批 0）**：heredoc 分隔符去引号或体内硬编码产品名（一行修，盘点 §1.3）；顺手修 install.ps1 步骤编号错乱（:77,:184,:298,:354,:389,:434,:470）与 install.sh rg 检查块重复（:285-293）。
5. **区域检测复用**：main 版既有 COS 根探测 + github favicon 探测逻辑保留（`install.sh:199-244`、`install.ps1:227-239,:316-339`），用于 whisper 模型源选择与横幅语言（§2.4）。

### 2.2 ② 依赖全量切腾讯云 COS（映射表）

**主源策略（推荐，见 D3）**：全部依赖以 COS 为主源（COS 全球可达，global 区域也走 COS）；上游原源降级为备选/末选（顺序：COS → 国内源（如有）→ 海外源/ghproxy）。单一主源 = 行为一致 + 测试矩阵减半。文件名沿用上游原始文件名（少改名少出错）。

**依赖 → COS 映射表**（桶 `nebflow-releases-1411212853.cos.ap-nanjing.myqcloud.com`，推荐 `deps/` 前缀，见 D2）：

| 依赖 | 平台/包 | 目标 COS URL（deps/ 前缀版） | 上游原源（降级备选） |
|---|---|---|---|
| ripgrep 14.1.1 | win x64 zip | `deps/ripgrep-14.1.1-x86_64-pc-windows-msvc.zip` | github → ghproxy（现状，盘点 §3.1） |
| ripgrep 14.1.1 | mac arm64 tar.gz | `deps/ripgrep-14.1.1-aarch64-apple-darwin.tar.gz` | 同上 |
| ripgrep 14.1.1 | mac x64 tar.gz | `deps/ripgrep-14.1.1-x86_64-apple-darwin.tar.gz` | 同上 |
| ripgrep 14.1.1 | linux x64 musl tar.gz | `deps/ripgrep-14.1.1-x86_64-unknown-linux-musl.tar.gz` | 同上 |
| ripgrep 14.1.1 | linux arm64 musl tar.gz | `deps/ripgrep-14.1.1-aarch64-unknown-linux-musl.tar.gz` **（v2 新增，现脚本无此包，补齐「不同环境正常」）** | github（新增直连） |
| JDK 17 msi | win x64 | `deps/OpenJDK17U-jdk_x64_windows_hotspot_17.0.19_10.msi` | tuna（保留为备选）；huaweicloud 17.0.2 异版备选**建议删除**（发行构建不一致，行为差异源） |
| Git for Windows 2.55.0.3 | win x64 exe | `deps/Git-2.55.0.3-64-bit.exe`（以批 2 实际下载文件名为准） | npmmirror（国内备选）→ ghproxy → github |
| Homebrew bootstrap | mac | `deps/homebrew-install.sh`（脚本本体镜像）+ 运行时注入 `HOMEBREW_BREW_GIT_REMOTE`/`HOMEBREW_CORE_GIT_REMOTE` 指向清华 TUNA 镜像 | raw.githubusercontent.com（海外）；**诚实标注**：镜像 installer 只解决第一步，brew 仓库克隆必须配 git remote 环境变量才国内可达——两件套组合才是完整方案，批 1 实测验证 |
| Adoptium JDK 21 tar.gz | linux x64/arm64 | `deps/OpenJDK21U-jdk_<arch>_linux_hotspot_<ver>.tar.gz`（按需，仅发行版源不可用兜底） | adoptium api（海外兜底） |
| Whisper base | 全平台 | **暂不 COS 化**：hf-mirror 已国内✅、模型体积大（存储成本）；保留 hf-mirror/huggingface 双源+区域检测 | hf-mirror / huggingface（现状） |
| checksum 账 | — | `deps/checksums.txt`（sha256，全依赖清单） | — |
| Nebflow jar / 指针 | 全平台 | 沿用现状扁平根（`nebflow-assembly-<version>.jar`、`latest-*-version.txt`），**不动**（已有先例与测试锚点 `BrandingSpec.scala:48`） | — |

**上传通道（D4）**：deps 是低频变更资产——推荐**一次性上传脚本** `scripts/upload-deps.sh`（本地/CI dispatch 手动触发，走 accelerate 端点对齐 `release.yml:262` 先例，上传时生成 checksums.txt），不进常规发布 CI。**外部依赖**：COS 凭证在本仓沙箱不可得，上传动作由作者本地执行或配 CI secrets（作者操作，批 2 内完成）。

### 2.3 ③ PowerShell 中文乱码修复（按盘点 §1.3 三分账）

| 账 | 修法 | 覆盖路径 | 批次 |
|---|---|---|---|
| **内容账（根治，必做）** | install.ps1 全文 ASCII 化：盲文横幅 → ASCII 横幅（§2.4）、中文注释移除或改英文、em-dash → `--` | **全路径**（irm|iex + 文件执行），与代码页彻底解耦 | 批 3 |
| 文件编码账 | install.ps1 / uninstall.ps1 落盘 UTF-8 **with BOM**（PS 5.1 尊重 BOM；ASCII 内容 + BOM 无副作用） | 文件执行路径 | 批 3 |
| 链路账（主路径） | 服务器端 install.ps1（含 install.sh）Content-Type 改 `text/plain; charset=utf-8`——Vercel 侧配置，**nebflow-website 仓职责**（沙箱外，列入批 6 派单依赖清单） | irm|iex 主路径（纵深防御；内容账已使其非必需） | 批 6 |
| 输出通道账 | 脚本头 `[Console]::OutputEncoding=[Text.Encoding]::UTF8` + `$OutputEncoding`（保子进程输出不二次乱码） | 辅助 | 批 3 |

验收口径：中文 Windows 默认「Open PowerShell」（5.1 + CP936）下 `irm https://nebflow.space/install.ps1 | iex` 全程零乱码；文件执行路径同样零乱码；**全文 ASCII 断言**（`grep -P '[^\x00-\x7F]'` 于 install.ps1 零匹配——中文标语例外见 D5）。

### 2.4 ④ 脚本美化：启动横幅（品牌绿 #07C160 + 双语标语）

**方案对比**：

| 方案 | 形态 | 优点 | 缺点 |
|---|---|---|---|
| **B：ASCII art 大字（推荐）** | figlet 风格 `NEBFLOW` 纯 ASCII 字符画 + ANSI 色 | 解码安全（纯 ASCII 字节，与乱码根治零冲突）；ConHost/PS5.1/全终端兼容；ANSI 色失败自动降级无色 | 视觉比块字符朴素 |
| A：块字符/盲文 art | █▀▄ 半格拼图 + truecolor | 视觉最佳 | **块字符是非 ASCII，与 §2.3 内容账直接冲突**——若坚持需先落链路账（跨项目依赖），不推荐 |
| C：纯文本品牌行 | 两行文字 + 色条 | 最简 | 品牌感弱 |

**推荐方案 B 横幅示意**（终稿以 figlet 标准字生成，色值 `\e[38;2;7;193;96m` = #07C160，降级 `\e[32m` → 无色）：

```
 _   _  _____  ____   _____  _      ___
| \ | || ____|| __ ) |  ___|| |     / _ \
|  \| ||  _|  |  _ \ | |_   | |    | | | |
| |\  || |___ | |_) ||  _|  | |___ | |_| |
|_| \_||_____||____/ |_|    |_____| \___/
 所有工作，一个入口。/ One entry. Every agent.
```

**双语标语的乱码约束（D5）**：中文标语 = CP936 下唯一保留的非 ASCII 输出。推荐**检测式输出**——bash 侧（mac/linux 全 UTF-8 环境）直接双语；PowerShell 侧检测代码页/终端（UTF-8 或 PS7）才打印中文行，PS 5.1 默认窗只出英文行 `One entry. Every agent.`。把非 ASCII 例外收敛到一个受控点。备选：全 ASCII 仅英文标语（品牌中文句只在官网/UI 出现）。

实现注意：PS 5.1 需 SetConsoleMode 启用 VT 序列后 truecolor 才生效（`$PSStyle` 仅 PS7.1+），失败降级链：truecolor → 16 色 → 无色；`NO_COLOR`/非 TTY/CI 环境自动纯文本。

### 2.5 ⑤ 安装后形态推荐

| 选项 | 形态 | 评估 |
|---|---|---|
| **B（推荐）：JVM + 预构建 jar（现行脚本形态打磨）** | 脚本自动装 JVM → COS 拉 jar → wrapper 启动 | **已在线上验证的最小可靠形态**（现网用户跑的就是它）；组件最少（JVM+jar+脚本）；**零签名需求**（脚本/jar 天然免疫 Gatekeeper/SmartScreen）；零新增打包面（与 CI 打包冻结方向一致）；缺点：需装 JDK（脚本自动化）、启动经 wrapper |
| A：脚本下载解压「桌面产物 zip（COS 镜像）」引导启动 | 便携 zip（jar + jlink 裁剪运行时，每平台/arch 一包） | **现状无此产物形态**（CI 产物是 msi/dmg/deb/app-image，无免安装 zip）→ 需新增打包任务与发布资产，与「冻结打包」方向相逆；zip 路线规避签名技术上可行（脚本 curl 下载不带 quarantine/MotW，需注意 PowerShell iwr 会打 Zone.Identifier 须用 curl.exe/Unblock-File），但多平台 zip 测试面 = 新增维护负担 |
| B2（任务文本「源码运行」字面义）：JVM + 源码构建运行 | 克隆仓库 + sbt assembly | **明确不推荐**：终端用户需 sbt/node 工具链 + 编译时长 + 巨大失败面，违背「最小可靠」判据 |

**推荐 B**：判据=「保证发布能正常使用」的最小可靠形态——B 是唯一已被现网验证、零新增资产、零签名风险的形态。A 作为 v2.x 可选演进（「便携版」），证书到位后若作者要「双击即用」，正确路径仍是恢复桌面端（信任链 spec）而非便携 zip。**若作者对「双击体验」有硬需求则选 A，需接受新增打包线**（见 D1）。

### 2.6 ⑥ 官网下载页改造口径（只给口径与文案，实施另派 nebflow-website）

**口径**：安装文档页（`/en|/zh/docs/getting-started/installation`）桌面 dmg/msi/AppImage/snap 段整体替换为**脚本安装指引**（各平台一行命令 copy-paste 式）；GH Releases 死链删除；封存期不出现桌面入口（避免用户触达未签名产物，见 D9）。

**文案建议（zh）**：

> ## 一行命令安装
>
> **macOS / Linux**（终端执行）：
> ```bash
> curl -fsSL https://nebflow.space/install.sh | bash
> ```
>
> **Windows**（PowerShell 执行）：
> ```powershell
> irm https://nebflow.space/install.ps1 | iex
> ```
>
> 脚本会自动检测并补齐全部依赖（JDK、Git、ripgrep），国内网络全程直连，无需代理。安装完成后运行 `nebflow` 即可启动。
>
> **系统要求**：macOS 12+ / Windows 10+ / 主流 Linux 发行版（Ubuntu 20.04+ 等），8GB 内存。

**文案建议（en）**：

> ## Install with one command
>
> **macOS / Linux**:
> ```bash
> curl -fsSL https://nebflow.space/install.sh | bash
> ```
> **Windows** (PowerShell):
> ```powershell
> irm https://nebflow.space/install.ps1 | iex
> ```
> The script detects and installs all dependencies (JDK, Git, ripgrep) automatically. Run `nebflow` to start.

**前置缺口（批 6 派单时一并定）**：① 脚本部署通道（G1，见 D11）；② install.ps1/install.sh 的 Content-Type charset（§2.3 链路账）；③ 首页是否加下载入口（现首页无任何入口，建议导航加 Install 项——作者定）。

### 2.7 ⑦ beta 发布流调整（beta.55 起）

**beta.55 走法（D7）**：beta.55 = Username/好友发版门（用户可达性关键版本），VERSION=1.4.1-beta.55 在途（COS 指针 beta.54，jar 404）。

- **R1（推荐）：脚本 v2 首秀随 beta.55**——批 0-4（+批 6 最小线命令）完成后再发 beta.55，好友发版即新线验收。条件：beta.55 发版时点 ≥ 脚本批次完成（约 2-3 周量级）。
- **R2：beta.55 按原计划发**（现网旧脚本线主路径 COS 优先仍可用）+ 官网**最小改**补一条线命令，脚本 v2 批次随 beta.56 完成。触发条件：beta.55 需 2 周内发出。

**CI 打包任务处置（对象清单引盘点 §二.3）**：

| 对象 | 处置 | 说明 |
|---|---|---|
| `release.yml` package-macos/windows/linux 三 job | **停**（注释禁用，不删） | dmg/msi/deb/app-image 打包 |
| `release.yml` 资产收集模式中 dmg/msi/deb/app-image 四类 | **停**（保留 jar 类收集） | 配套改动 |
| `release.yml` build-jar / tag / COS jar+指针上传 | **留** | 脚本线唯一发布依赖 |
| `auto-release.yml` 三 package job | **停**（同构） | stable 线同口径 |
| `auto-release.yml` build-jar / COS / 版本回写 | **留** | — |
| `auto-release.yml` dry_run 模式 | **留** | 证书恢复时的打包矩阵验证器 |
| `ci.yml` | **全留不动** | 无打包无发布 |
| `packaging/*.sh` + jlink-modules.txt + gen-icons.py + icons | **冻结保留**（文件头加封存注释：指向信任链 spec + 恢复条件） | 证书到位直接复用（MinGit/rg 捆绑、marker 逻辑是现成资产） |

**时点建议**：批 5 在 beta.55 发布**之后**合入（好友门用未动过的发布流最稳，jar 线先走一遍全流程验证再瘦身）。

**存量 msi 用户出路（D8）**：`RemoteUpdateAction.scala:26-33` msi 安装拒绝 in-app 更新并指向下载页——封存期下载页无 msi = 更新出路断。推荐：更新提示文案改为**脚本迁移指引**（一行命令迁到脚本线）+ COS 既有 msi **不删**（存量自救 + 恢复时资产现成）。

### 2.8 ⑧ 桌面端封存边界

**停用清单**（封存 ≠ 删除，零删代码）：
1. 发布产物：上表三 package job + 四类资产收集（批 5）；
2. 下载入口：官网安装页桌面段（批 6 改造时移除，D9）；
3. 签名流程推进：签名 CI 集成不做（本就未做）；证书采购**不停**（见并行线）。

**继续线（并行不停，引盘点 §六 / 信任链 spec）**：证书申报全链按信任链 spec §一 优先级推进——① Apple Developer $99/年 + notarytool 管道（macOS 最优先：未公证 dmg 在新 macOS 是「已损坏」死路）② Certum Open Source €49/年起 + signtool/TSA ③ 阿里云备案链（服务器+域名 → ICP → 公安）④ 软著送审；检查项 C-1（COS→OSS 迁移评估——**v2 铺 deps 前需作者就此拍板，避免铺完就迁**）、C-3、C-4 随迁。

**恢复条件（写明触发线）**：
- macOS 恢复最小集 = Apple Developer ID 证书到位 + CI notarytool/stapler 集成 + 官网恢复桌面入口；
- Windows 恢复最小集 = Certum 证书到位 + CI signtool/TSA 集成 + SmartScreen 声誉预热期沟通（声誉随下载量数周积累，非即时）。
- 恢复动作 = 解注释 package job → dry_run 验证 → 正式打包，`packaging/` 工具链原样复用。

---

## 三、分批实施建议

原则：每批一个小功能一个节点；批间依赖显式；每批可独立验收；零发布动作（COS 上传/官网变更/CI 触发涉及外部凭证或跨仓者单列外部依赖）。

| 批 | 内容 | 依赖 | 验收口径（可断言） | 外部依赖 |
|---|---|---|---|---|
| **批 0** | wrapper heredoc 缺陷修复 + 步骤编号/rg 重复块顺手修 | 无 | A0-1 /tmp 模拟：heredoc 生成 wrapper 后 jar 假体命中、`nebflow --version` 语义正确；A0-2 install.ps1 步骤编号序列断言（[1/5]…[5/5] 单调）；A0-3 rg 检查块唯一性断言 | 无 |
| **批 1** | 脚本 v2 主体：三平台依赖检测矩阵+补装+统一日志+幂等+SCRIPT_VERSION；Windows JDK 17/21 对齐核实（G5） | 批 0 | A1-1 clean 环境（linux docker + mac 本机）检测输出断言；A1-2 补装路径真装 rg/JDK 于临时目录成功；A1-3 重复执行幂等断言；A1-4 Homebrew 两件套（installer 镜像+TUNA git remote）可达性实测 | 无 |
| **批 2** | 依赖全量切 COS：映射表落地 + checksum 校验 + 区域检测接入 + `scripts/upload-deps.sh` | 批 1 | A2-1 全依赖 COS URL HEAD 200 + sha256 匹配 checksums.txt；A2-2 cn/global 两模式下载路径断言；A2-3 备选源降级链断言（主源 404 时回退） | **COS 上传凭证（作者执行 upload-deps.sh 或配 CI secrets）** |
| **批 3** | 乱码根治：install.ps1/uninstall.ps1 ASCII 化 + UTF-8 BOM + 输出通道声明 | 批 0、批 1（内容定稿） | A3-1 install.ps1 全文 ASCII 断言零匹配；A3-2 BOM 头断言（EF BB BF）；A3-3 CP936 模拟解码（iconv）无乱码关键词；A3-4 irm|iex 路径模拟（无文件落盘解码）零乱码 | 无（链路账归批 6） |
| **批 4** | 品牌横幅：ASCII art logo + #07C160 + 双语标语检测式输出 | 批 3 | A4-1 bash/PS 双环境渲染存档对照（作者视觉验收）；A4-2 非 TTY/NO_COLOR 降级断言；A4-3 PS5.1 无 VT 环境无色降级断言；A4-4 横幅含非 ASCII 时必有 UTF-8 环境守卫的静态断言 | 无 |
| **批 5** | CI 封存：release.yml/auto-release.yml 停三 package job + 资产收集瘦身 + packaging/ 冻结头注 + AutoStartService 耦合审计（G3） | 批 2（时点：beta.55 发布后，见 §2.7） | A5-1 workflow 语法 lint 过 + 打包 job 注释禁用审阅；A5-2 jar 线完整性人工审阅（build-jar/tag/COS 指针/版本回写不动）；A5-3 下次发版（beta.56）实测 jar 产物与指针正常 | 无 |
| **批 6** | 官网改造（**nebflow-website 派单**）：脚本部署通道 + Content-Type charset + 安装页按 §2.6 重写 | 批 3、批 4（脚本最终形态）；文案与验收口径本文已备 | A6-1 线上 install.sh/ps1 与主仓 release/ byte 相等；A6-2 install.ps1 响应头 `charset=utf-8` 断言；A6-3 安装页渲染 + 一条线命令 copy-paste 真装通过（隔离环境）；A6-4 桌面入口移除断言 | **nebflow-website 仓访问与部署权限** |
| **批 7** | 存量 msi 用户出路：RemoteUpdateAction 更新提示改脚本迁移指引 | 批 5、批 6 | A7-1 模拟 msi 安装环境断言提示文案含迁移命令；A7-2 jar 安装路径回归（提示不变） | 无 |

**依赖图**：批0 → 批1 → 批2 → 批3 → 批4 → 批6 → 批7；批5 在批2 后可并行（时点约束：beta.55 后）。
**最小可用闭环** = 批 0 + 批 1 + 批 2 + 批 6 最小项（部署通道 + 一条线命令）——好友能装、能用、国内直连。
**发布流红线**：所有批次零发布动作、不 push；COS 上传与官网部署为作者/跨仓动作，节点产出物=脚本与清单，动作执行另行走作者确认。

---

## 四、拍板清单

| # | 决策点 | 推荐 | 备选 | 影响 |
|---|---|---|---|---|
| **D1** | 安装后形态 | **B：JVM + jar（现行形态打磨）** | A：便携 zip 产物线 | A 需新增打包任务+发布资产+多平台测试面，与「冻结打包」相逆；B 零新增面、已验证 |
| **D2** | COS 依赖目录 | **`deps/` 前缀** | 延续扁平根 | deps/ 语义隔离、便于巡检与 C-1（OSS 迁移）评估时整体搬移；扁平则与 jar/桌面产物混居一域 |
| **D3** | 依赖主源策略 | **全区域 COS 主源**，上游源降为备选链 | cn=COS / global=上游原源（按区域分流） | 全 COS = 行为一致+测试减半；分流 = 全球下载速度更优但双倍矩阵 |
| **D4** | deps 上传通道 | **一次性上传脚本**（upload-deps.sh + checksums.txt，作者本地/CI dispatch 触发） | 纳入 release.yml 常规步骤 | deps 低频变更，常规 CI 每次跑属浪费；需作者提供凭证侧配合 |
| **D5** | 中文标语策略 | **检测式**（UTF-8 环境双语，PS5.1 默认窗英文） | 全 ASCII 仅英文 | 检测式 = 品牌双语与乱码根治兼容（唯一受控例外）；全 ASCII = 零例外纪律，中文句仅在官网/UI |
| **D6** | 横幅方案 | **B：ASCII art + ANSI 绿** | A：块字符 art（与 ASCII 化冲突，需链路账先行） | A 视觉更佳但引入跨项目前置依赖与 CP936 风险 |
| **D7** | beta.55 走法 | **R1：脚本 v2 首秀随 beta.55** | R2：beta.55 按原计划+官网最小补线命令，v2 随 beta.56 | 判据：beta.55 需 2 周内发 → R2；无硬时点 → R1（好友门=新线验收） |
| **D8** | 存量 msi 用户出路 | **in-app 更新提示改脚本迁移指引 + COS 既有 msi 不删** | COS 放「终版 msi」长期可下（半封存态） | 不处理 = 存量 msi 用户断更；指引迁移 = 引入脚本线生态 |
| **D9** | 官网桌面入口封存期处理 | **全撤**（含手动安装折叠区） | 折叠区保留「高级用户」桌面入口 | 全撤 = 未签名产物零触达；保留 = 仍有 Gatekeeper/SmartScreen 劝退与工单成本 |
| **D10** | 批 6 官网派单时点 | 批 3/4 完成验收后**一次性派**（脚本形态定稿） | 文案先行、提前并行派 | 一次性 = 返工最少；提前 = 官网侧排期前移但可能两轮改 |
| **D11** | 脚本部署同步机制（G1） | **nebflow-website 侧建通道**（手动触发 workflow 拉主仓 release/install.* 或等价物），主仓 release/ 为唯一真源 | 主仓 CI 加 publish job（需官网仓凭证进主仓 secrets） | 方向 1 保持仓权边界清晰；方向 2 自动化高但跨仓凭证管理面大 |
| **D12** | C-1 联动（COS→阿里云 OSS 迁移评估） | **先拍板 C-1 再铺 deps**（若迁 OSS，deps/ 前缀整体搬移成本最低） | 先铺 COS、后评估迁移 | 信任链 spec 开放项；不拍板就铺 = 可能铺完就迁 |

**待核实项**（实施批次内闭环，不阻塞拍板）：G3 AutoStartService 耦合（批 5）；G5 Windows JDK 17 vs 21——核 jar 字节码版本，无硬约束则三平台统一 21（批 1，若变更有额外 msi/jpackage 关联需作者知悉）；huaweicloud 17.0.2 异版备选删除（批 1 设计内，作者可否决）；rg linux arm64 包新增（批 2）。

---

## 版本记录

- **v1（2026-09-06）**：初稿。基于盘点 n-e6f38a7b 证据报告与作者 2026-09-06 01:40 回退裁定，四节全量产出（现状盘点 / 目标设计①-⑧ / 批 0-7 / D1-D12）。
