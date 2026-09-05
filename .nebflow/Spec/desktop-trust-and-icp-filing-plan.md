# Nebflow 备案与桌面软件信任链方案调研报告

> 状态：draft（作者委托调研，2026-09-05 17:53）｜ 检索日期：**2026-09-05**（除标注外，所有来源均为本轮实测抓取/可达性验证）
> 基线：[.nebflow/Spec/icp-beian-action-plan.md](icp-beian-action-plan.md)（2026-08-17 调研，19 条来源；本报告以其为基更新，备案部分继承其已核实结论并标注增量）
> 范围：nebflow.space 主域（Vercel 海外）+ neblink.space（beta 下载页）+ 拟迁腾讯云国内轻量服务器；**个人主体**；Nebflow 安装包 CI 自动打包（beta.54 现状，下一版 beta.55 起）

---

## 一、结论速览表

按建议执行顺序排列（依赖关系见第四节行动清单）：

| # | 做什么 | 为什么 | 主体要求 | 成本 | 周期 | 前置依赖 |
|---|--------|--------|----------|------|------|----------|
| 1 | 腾讯云大陆轻量服务器（2C2G4M ≥3 个月）+ neblink.space 确认注册商（境外则转入腾讯云） | 备案硬门槛三件套之二（服务器境内包月 ≥3 月 + 域名境内注册商） | 个人实名 | ¥300-450（服务器 3 个月）+ 域名续费 1 年约 ¥40-100 | 域名境内：0 天；境外：转入 3-7 工作日 + 实名冷却 3 天 | 腾讯云账号个人实名 |
| 2 | 提交个人 ICP 备案（腾讯云小程序，网站名用个人风格命名） | 域名要挂到大陆服务器就必须备案；合规底座 | 个人，人脸核身 | ¥0 | 顺利 7-15 天，保守 4 周 | #1 完成（服务器+域名就绪） |
| 3 | ICP 通过后 30 日内公安备案 | 法定义务（开通后 30 日） | 个人 | ¥0 | ~1 周 | #2 下发备案号 |
| 4 | Apple Developer Program 注册 + CI 公证管道（Developer ID 签名 + notarytool） | macOS 未公证 dmg/Gatekeeper 直接拦截（「已损坏」），损失 100% macOS 新用户——三平台中优先级最高 | 个人（自然人可注册） | $99/年 | 注册 1-2 天 + CI 管道 1-3 天 | 无（可与 1-3 并行） |
| 5 | Windows 代码签名证书采购（推荐 Certum Open Source 云签名 €49/年起）+ GitHub Actions 签名集成 | 未签名 msi 触发 SmartScreen「未知发布者」拦截；签名后声誉随下载量从零积累 | 个人可办（开源开发者证书）；EV 需组织实体 | €49/年起 | 采购+身份验证 1-2 周 + CI 集成 2-5 天 | 无（可与 1-4 并行） |
| 6 | 软件著作权登记送审 | 国内分发平台上架的前置凭证（多个平台硬要求）+ 维权凭证 | 个人可办 | ¥0（登记费 2017 年起停征） | 法规口径受理后 60 日内审结，实际常见 30-60 工作日 | 软件源码+说明文档（现成） |
| 7 | （观察项）Microsoft Store 上架评估 | 微软代签名，绕开自建签名链；一次注册费长期有效 | 个人 $19 / 公司 $99（一次性，历史口径，待注册页复核） | $19 | 审核数天 | #4/#5 可选其一做基础 |

**首年总账 ≈ ¥1,500-1,800 等值**（服务器 ¥300-450 + 域名续费 ¥40-100 + Apple $99 + Windows 证书 €49-69；备案/公安/软著全免费）。

**本轮调研推翻/新增的三个关键结论**（相对基线）：
1. **Azure Trusted Signing 已更名 Artifact Signing，且对中国主体不可用**——2026 现行口径：Public Trust 证书组织仅限美/加/欧盟/英/澳/新/日/韩/新加坡/瑞士/挪威/以色列 12 国/地区，**个人开发者仅限美国或加拿大居民**（微软 Learn quickstart 原文，检索日期 2026-09-05）。国内个人开发者的云签名路线只剩第三方 CA（Certum/SSL.com 等）。
2. **CA/B Forum 硬件强制已是既成事实且进一步收紧**：代码签名私钥必须保存在 FIPS 140-2 Level 2（或同等）硬件模块——2021-06-01 起从 EV 扩展到**所有**代码签名证书（CSBR 原文）；叠加 **2026-02-27 起单张证书有效期上限 459 天**新规（Certum CA 官方公告）。传统「.pfx 文件扔进 CI secrets」已不可行，CI 签名必须走云签名服务或本地 token。
3. **桌面软件不在移动 App 备案新规射程内**（依据见二.6，含未决标注）。

---

## 二、网页合规（ICP + 公安备案）

> 基线文档（2026-08-17）已对腾讯云官方文档逐条核实，本轮 2026-09-05 对其中关键 URL 做可达性抽查：243/18908（备案云资源）、243/37402（首次备案小程序端）、243/39038（如何快速备案）全部 HTTP 200，内容口径无变化迹象。**以下流程/材料/周期结论继承基线，不再重复展开**，仅列决策相关要点与增量。

### 1. 「要不要备案」的边界——Vercel 海外部署现状不触发备案义务

- 备案的法定对象是**在境内**提供非经营性互联网信息服务：服务器/接入在境内才强制 ICP 备案。nebflow.space 当前全量部署在 Vercel（海外边缘），**不解析到大陆服务器就不需要备案**，这是当前零合规成本的现状（基线 §4「备案期间」同口径）。
- 决策含义：**nebflow.space 可以永久留在 Vercel 不备案**；只有 neblink.space（或任何域）要解析到腾讯云大陆服务器时才进入备案流程。这与基线方案 A（neblink.space 整域迁腾讯云）一致。
- 时效标注：境内接入才强制的边界由《互联网信息服务管理办法》（非经营性 ICP 备案制度）确立，2026 现行有效，无修法动向的公开信息。

### 2. 个人 ICP 备案：流程 / 材料 / 周期（继承基线 §2-§3，速查）

- **硬前置三件套**：① 腾讯云大陆地域包月 ≥3 个月服务器（提交时剩余有效期 ≥1 个月）② 域名后缀在工信部批复列表（.space ✅，2018-12 批复）+ 注册商在境内（境外注册商必须先转入）+ 域名实名所有者=备案本人 + 实名冷却满 3 日 ③ 本人 +86 手机号微信人脸核验。（来源：腾讯云备案云资源/备案域名/首次备案文档，URL 见基线来源 1/2/5/6；本轮 200 抽查通过）
- **材料**：身份证正反面、本人手机号（=腾讯云账号绑定号）、邮箱、域名实名信息截图；建议直接选身份证省份备案以规避居住证要求。
- **周期**：腾讯云初审 1-2 工作日 → 12381 短信核验（**24 小时内**完成，超时退回）→ 管局终审 ≤20 工作日；合计顺利 7-15 天，保守 4 周。
- **费用**：备案、公安备案全程 ¥0。

### 3. 网站命名（个人备案驳回高发点）

- 「NebLink 官网」「星络官网」这类措辞是典型驳回原因（个人备案=非经营性，名称须体现个人性质）；规范示例：「星络研发日志」「个人技术分享（设备互联）」。（来源：腾讯云网站命名建议 https://cloud.tencent.com/document/product/243/11740 ，基线来源 8）
- 服务内容勾「个人博客/技术分享」类；页面不得出现购买/价格/订单/收费/会员等经营性元素——**beta 下载页放在已备案域时注意不要带商业化措辞**；开源软件下载与 GitHub 链接属个人作品分发，普遍可过。

### 4. 备案号页脚义务与公安备案

- **页脚**：ICP 备案号须悬挂在网站页脚并超链至 `https://beian.miit.gov.cn`，管局不定期核查，未悬挂可处 5000-10000 元罚款。（来源：腾讯云 243/76865，基线来源 10）
- **公安备案**：网站开通后 **30 日内**在全国互联网安全管理服务平台（beian.mps.gov.cn）办理，免费。落地页一旦挂出（哪怕极简）即应办理；纯 API 子域有豁免空间但本方案不做纯 API 形态。（来源：腾讯云 243/37402，基线来源 6）

### 5. 与 nebflow 网络拓扑的衔接现状

- `nebflow.space`：主域，Vercel 海外，产品官网 + 登录跳转（`src/main/resources/web/js/brand.js:28` domain fallback；`neblink.js:3,139-150` 登录/connect 跳转链）——**留海外，不备案**。
- `neblink.space`：设备配对 profile 域（`brand.js:50` PROFILE_URL_FALLBACK）+ 计划承载 beta 下载页 + neblink-server 迁腾讯云——**走备案链**（方案 A：整域迁腾讯云，官网静态页 + Rust API + WS relay 同机 compose）。
- 备案期间纪律：取得备案号前 neblink.space 不得解析到大陆服务器开 web（会被拦截）；Vercel/Vultr 海外解析不受影响。

### 6. 移动 App 备案新规是否波及桌面软件——**不适用**（含证据状态标注）

- 论断：工信部 2023 年《关于开展移动互联网应用程序备案工作的通知》确立的 App 备案制度，其适用对象是**移动互联网应用程序**——预装或下载安装在**移动智能终端**（智能手机、平板）上运行、经应用商店分发的 App（含小程序、快应用）。Nebflow 的 Windows msi / macOS dmg / Linux deb 是桌面平台安装包，不经移动应用商店分发，**不属于 App 备案对象，无需 App 备案**；网站侧合规由 ICP 备案覆盖。
- 证据状态（诚实标注）：通知原文页（miit.gov.cn / gov.cn）本轮自动化抓取被拒（gov.cn HTTP 403、miit.gov.cn 反爬），**原文文本未直接验证**；论断依据为①通知名称与「移动互联网应用程序/移动智能终端」的适用范围定义（工信部官方口径）②各省通管局及接入商（阿里云/腾讯云）App 备案指引的适用对象描述均为 iOS/Android 应用商店分发场景。**建议作者人工点开复核**：https://beian.miit.gov.cn（备案系统首页通知公告栏）。
- 桌面软件在国内的对应监管抓手是「应用分发平台的上架审核」（要软著等凭证，见三.7）与产品内容合规，不是 App 备案。

---

## 三、桌面软件「不受信任」提示（重点）

### 0. nebflow 打包现状锚点（举证）

| 事实 | 锚点 |
|---|---|
| CI 三平台自动打包：macOS dmg（arm64/x64 双矩阵）、Windows msi（x64）、Linux deb + app-image，全部 jlink + jpackage | `.github/workflows/release.yml:82-202` |
| 发布产物 5 类收集（jar/dmg/msi/deb/app-image） | `.github/workflows/release.yml:226-239` |
| GitHub Release（prerelease）+ 腾讯云 COS 中国镜像 + latest-beta-version.txt 版本指针 | `.github/workflows/release.yml:248-270` |
| **全流程零签名/零公证**（无 signtool / codesign / notarytool 步骤） | `release.yml` 全文；`packaging/build-dmg.sh:9`「No signing — jpackage ad-hoc signs automatically」；`packaging/build-msi.sh:15`「Unsigned msi triggers SmartScreen 'unknown publisher' on first install」 |
| 版本基线：当前 1.4.1-beta.54，报告建议动作从 beta.55 起生效 | `VERSION:1` |

### 1. 三平台根因

| 平台 | 用户看到什么 | 根因 |
|---|---|---|
| Windows 10/11 | SmartScreen「Windows 已保护你的电脑 / 未知发布者」，需「更多信息→仍要运行」 | msi/exe 无 Authenticode 签名，或签名证书无下载声誉；SmartScreen 按文件哈希+签名+下载来源做声誉判定（来源：MS Learn SmartScreen overview，检索 2026-09-05：防护对象明确含「the downloading of potentially malicious files」，机制为动态列表+声誉） |
| macOS 10.15+ | Gatekeeper 拦截：「无法打开，因为无法验证开发者」或更糟的「已损坏，移到废纸篓」 | Apple 2019-06-01 后构建并经 Developer ID 分发的软件**必须公证**（notarized）；未签名/ad-hoc 签名（nebflow 现状，`build-dmg.sh:9`）在较新 macOS 上直接拒开（来源：Apple notarization 官方文档，检索 2026-09-05：「Beginning in macOS 10.15, all software built after June 1, 2019, and distributed with Developer ID must be notarized」） |
| Chrome/Edge（下载侧） | 「xxx.msi 可能有害 / 通常不会被下载」黄色横幅 | 浏览器下载保护（Safe Browsing 等）对无声誉二进制的独立拦截层，与 SmartScreen 同源逻辑：无签名+无下载量=低信任 |
| Linux | 无等价拦截 | deb/rpm 无 Gatekeeper/SmartScreen 类机制，信任由包源与 GPG 签名承载（可选做，非拦截性） |

**macOS 是三平台中唯一「无法绕过」的**：Windows 用户可点「仍要运行」，macOS 未公证 dmg 在新系统上是死路（右键打开的旧绕过已被收紧）——这决定了优先级。

### 2. Windows：签名方案对比（2026 现行，逐项核实）

**监管/技术前提（先于选型）**：
- CA/B Forum 代码签名基线要求（CSBR）：私钥必须存于 FIPS 140-2 Level 2（或同等）硬件加密模块——2021-06-01 起 EV 与非 EV（OV/IV）一体适用。原文：「For EV Code Signing Certificates, Signing Services shall protect Private Keys in a FIPS 140-2 level 2 (or equivalent) crypto module. After 2021-06-01, the same protection requirements SHALL apply to Non EV Code Signing Certificates.」（来源：CA/B Forum CSBR，https://github.com/cabforum/code-signing/blob/main/docs/CSBR.md ，检索 2026-09-05）
- **对 CI 的直接冲击**：签名私钥不在磁盘上，`.pfx` 入 secrets 的老办法已死。主流两条路：① 云签名服务（私钥在 CA/云厂商 HSM，CI 经其 CLI/action 调用）② 本地 USB token + 自托管 runner（个人非规模场景不划算）。
- **有效期新规**：2026-02-27 起单张代码签名证书最长 459 天；多年度购买产品中途需免费重签（来源：Certum 商店官方公告，检索 2026-09-05：「Starting from February 27, 2026, a single Code Signing certificate may be valid for a maximum of 459 days」）——CI 凭证管理按「年度轮换」设计，不要写死长期有效假设。

| 方案 | 资格门槛 | 价格（检索 2026-09-05） | CI 集成 | SmartScreen 效果 | 结论 |
|---|---|---|---|---|---|
| **Certum Open Source Code Signing（云）** | **个人/开源开发者可办**（CA 明示面向 free/open source 分发者） | **€49 起/年**（SimplySign 云方案，免物理卡+读卡器） | 无官方 GitHub Action；经 SimplySign 云签名网关调用，集成工作量中 | OV/IV 级：签名后声誉从零积累 | **推荐首选**：价格最低+个人友好+云签名兼容 CI |
| SSL.com IV/OV Code Signing（+eSigner 云签名） | IV 个人可办；OV 需组织实体 | IV 证书约 $119-239/年区间 + eSigner 云签名月费（页内标价动态渲染未取到实价，以官网为准） | **官方 GitHub Action（sslcom/codesigner）+ CodeSignTool CLI，集成最顺** | 同上 OV 级 | 次选/替代：贵于 Certum 但 CI 官方支持最好 |
| EV Code Signing | **需组织实体**（组织验证；EV 证书按 CSBR 仅限 Organization） | €379 起/年（Certum） | 云签名 | 历史口径 EV 声誉积累更快 | **个人主体不可办，排除**（SSL.com 有「Sole Proprietor EV」特例，仍需个体经营实体+更贵，不适用） |
| Azure Trusted Signing（**2026 已更名 Artifact Signing**） | **Public Trust：个人仅美/加居民；组织仅限 12 国/地区白名单——中国个人与组织均不在列** | Basic $9.99/月/5,000 签名（历史刊例；2026 定价页动态渲染，结构核实：Basic 5,000 / Premium 100,000 签名每月） | **官方最顺**（原生 GitHub Action azure/trusted-signing-action，FIPS 140-3 L3 HSM） | 同公网信任签名 | **对中国主体不可用，排除**（来源：MS Learn quickstart/overview，检索 2026-09-05） |

**SmartScreen 声誉累积机制（OV 签名后警告何时消失）**：
- 机制：SmartScreen 按文件哈希与数字签名综合判定；签名建立的是「已验证发布者」身份，**下载声誉从零随安装量积累**（来源：MS Learn SmartScreen overview；Certum CA 官方产品页明示卖点为「Elimination of the 'Unknown publisher' message」+「Building a Microsoft SmartScreen Filter reputation」，检索 2026-09-05）。
- 时间预期：微软未公布确切阈值。社区与 CA 经验口径：签名后数天至数周、下载量达到数百至数千级后警告逐步消失。**诚实标注**：微软旧版「app reputation eligibility」专页已下线（本轮 404 实测），EV「即时声誉」的说法微软近年公开文档不再承诺——**不要把「签名=立即无警告」写进预期**；时间戳签名（/tr）必须启用，否则证书过期后签名失效会反伤声誉。
- 加速手段：COS 中国镜像 + GitHub Release 双下载源累积同一签名文件的下载基数；保持二进制更新节奏稳定（频繁全量换哈希会重置文件级声誉，建议安装器内做增量更新而非永远换新 msi——长期项）。

### 3. macOS：Developer ID + notarytool 公证全流程

- **账号**：Apple Developer Program，**$99 USD 每会员年**（来源：官方 enroll 页原文「The Apple Developer Program is 99 USD per membership year」，https://developer.apple.com/programs/enroll/ ，检索 2026-09-05）；个人（自然人）可注册。
- **证书**：Developer ID Application 证书（用于 App Store 外分发）；jpackage 产物需 **Hardened Runtime**（`--options runtime`）——公证前置要求（来源：Apple notarization 文档「Enable code-signing for all...」 protections 列表，检索 2026-09-05）。
- **公证范围**：官方文档明列 macOS apps、非 app bundle（含 kext）、**disk images (UDIF/dmg)**、flat installer packages——dmg 直接可公证（来源同上，检索 2026-09-05）。
- **流程（notarytool，替代旧 altool）**：
  1. `xcrun notarytool store-credentials --apple-id ... --team-id ...`（或用 App Store Connect API key 配 profile，CI 推荐 API key 方式免双因素干扰）
  2. `codesign --sign "Developer ID Application: <NAME> (TEAMID)" --options runtime --timestamp deep` 签 `.app`
  3. 打 dmg（nebflow 现有 `packaging/build-dmg.sh` 产出）
  4. `xcrun notarytool submit <file.dmg> --keychain-profile <PROFILE> --wait`
  5. `xcrun stapler staple <file.dmg>`（票据钉到文件，离线首次启动也能过 Gatekeeper；Apple 网络票据为在线查询兜底）
  （流程子页 customizing-the-notarization-workflow 存在于官方文档树：upload + staple 两节；子页 JSON 本轮抓取受限，命令口径为 Apple 文档公开稳定 API，来源 URL：https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution/customizing-the-notarization-workflow ）
- **CI 集成**：GitHub Actions macOS runner 原生支持 codesign/notarytool； secrets 放 App Store Connect API key（issuer id/key id/.p8）；公证是云服务调用，**不涉及私钥硬件要求**，与 Windows 不同——这是 macOS 链路更简单的原因。
- **收益**：Gatekeeper「已验证开发者」直过；未公证 dmg 的「已损坏」死路彻底消除。**三平台性价比最高的一步。**

### 4. jpackage 产物逐项落地路径（结合 `packaging/*.sh` 现状）

| 产物 | 现状 | 落地 |
|---|---|---|
| `*-*.msi`（x64） | 无签名，SmartScreen 拦截（`build-msi.sh:15` 注释自证） | CI 打包后追加 signtool 步骤：`signtool sign /fd SHA256 /tr <RFC3161 TSA> /dsc:<云签名驱动或 CLI>`（Certum SimplySign 网关或 SSL.com CodeSignTool/官方 action）；TSA 时间戳必须启用 |
| `*-*.dmg`（arm64/x64） | jpackage ad-hoc 签名（`build-dmg.sh:9`），Gatekeeper 死路 | app 层 Developer ID + hardened runtime 签名 → dmg 层 notarytool submit + stapler staple（见三.3） |
| `*-*.deb` / app-image | 无签名机制要求 | 无平台拦截；可选进阶：apt 仓库 GPG signing（自建源时） |
| `*-assembly.jar` | 裸 jar | 不适用（开发者自用通道） |

### 5. 中国补充合规：软件著作权登记

- **个人可办**：自然人主体在中国版权保护中心（CCOPYRIGHT）办理；材料=在线填报的登记申请表（打印签章页上传 pdf）+ 软件鉴别材料（源程序：前后各连续 30 页，每页 ≥50 行；文档：用户手册/设计说明书任选）+ 身份证明（来源：官方《计算机软件著作权登记指南·所需文件》，https://www.ccopyright.com.cn/index.php?optionid=1080 ，检索 2026-09-05）。
- **费用**：登记费 2017-04-01 起停征（财政/发改取消行政事业性收费），官办 ¥0；**周期**：《计算机软件著作权登记办法》口径受理后 60 日内审查决定，实际常见 30-60 工作日（官网设「办理时限」专页；**具体页 URL 未抓实，未决项 #4**）。
- **什么场景需要**：① 国内应用分发平台上架的硬凭证（多家平台要求软著证书证明权利归属）② 维权/侵权投诉时的权属凭证 ③ 各类补贴/资质申报。对 Nebflow：非上网强制项，但**是「国内分发」的钥匙**，建议与签名链并行送审（反正免费，只是排队）。

### 6. 国内分发平台要求概览（2026 现行要点）

- 国内 PC 软件分发（应用宝 PC、360 软件管家、华为/联想等 PC 应用市场）共同硬门槛：软著证书 + 开发者实名（个人可入驻的平台有限，部分仅企业）+ 安装包（部分要求加壳检测/恶意行为扫描）+ 备案信息一致性（网站/ICP 主体对齐）。
- 另一条无需软著的中国可达路径：**腾讯云 COS 镜像直链分发**（现状已具备，`release.yml:256-270`）——不做「平台入驻」时，下载页 + COS 直链是合规成本最低的国内分发形态。
- 平台个体要求差异大且频繁调整，**本节为概览级结论，入驻前以目标平台当期入驻协议为准**（未做逐平台核实，未决项 #5）。

### 7. 推荐组合拳（nebflow 现状定制）

**主体约束**：个人主体（无企业实体）→ EV、Azure Artifact Signing、企业-only 平台全部出局；预算敏感 → 选最廉合格路线。

```
macOS（第 1 优先，beta.55 起）
  Apple Developer $99/年 → Developer ID + notarytool 公证进 CI（release.yml macOS job 追加签名+公证 steps）
  → 解决 Gatekeeper 死路（100% 新用户损失 → 0）

Windows（第 2 优先，beta.55+1 起）
  Certum Open Source €49/年（SimplySign 云签名）→ msi 打包后 signtool 云签名 step
  → 「未知发布者」消除；SmartScreen 声誉随 COS+GitHub 双源下载量在数周内积累

Web（并行推进，依赖作者三件事：注册商确认/身份证省份/服务器购买）
  腾讯云服务器+域名 → ICP 备案（¥0）→ 页脚备案号 + 30 日公安备案 → neblink.space 下载页迁境内（RTT 质变）

中国分发（第 3 优先，零成本排队）
  软著送审（¥0，材料现成：源码+手册）→ 到证后评估应用宝 PC 等平台入驻
  → 观察项：Microsoft Store（微软代签名，$19 一次性个体注册，可作 Windows 链路的补充渠道）
```

**首年现金支出 ≈ $99 + €49-69 + ¥340-550 ≈ ¥1,500-1,800 等值；持续年费 ≈ ¥700-900 等值。**

---

## 四、行动清单（按依赖排序）

| 步 | 行动 | 成本 | 周期 | 风险点 | 缓解 |
|---|---|---|---|---|---|
| 1 | 腾讯云个人实名 → 购轻量服务器（2C2G4M 大陆地域 ≥3 个月，建议年付）→ neblink.space 注册商确认（境外则解锁+转移码转入腾讯云+续费 1 年） | ¥300-450 + 域名续费 ¥40-100 | 境内注册商 0-1 天；境外 1-2 周 | 域名注册未满 60 天不能转出；转入后实名再等 3 天 | 先查注册商再定日程（基线 §2 前置链） |
| 2 | ICP 备案提交（腾讯云备案小程序：个人主体、个人风格网站名、「博客/技术分享」类目、人脸核身） | ¥0 | 提交半天；审核 7-15 天（保守 4 周） | ① 命名带「官网/产品名」被驳 ② 初审电话漏接驳回 ③ 12381 短信 24h 未核验退回 | 命名预案「星络研发日志」；审核期手机畅通；短信即到即办 |
| 3 | 备案号下发 → 页脚悬挂+链接 beian.miit.gov.cn → **30 日内**公安备案 → DNS 切换 neblink.space 至腾讯云（备案号下来前不得解析大陆开 web） | ¥0 | 1 周内完成 | 页脚漏挂罚款风险（5000-10000 元档） | 前端页脚模板预埋备案号占位；切流前 checklist |
| 4 | Apple Developer 注册（$99/年）→ App Store Connect API key → `release.yml` macOS job 追加 codesign+notarytool+stapler（beta.55 首版生效） | $99/年 | 注册 1-2 天 + 管道 1-3 天 | ① 双因素/协议未签导致提交失败 ② dmg 内未用 hardened runtime 被公证驳回 | CI 全用 API key 认证；jpackage 加 `--mac-sign` 与 runtime 选项联调 |
| 5 | Certum Open Source 证书采购（€49/年起）→ 个人/开源身份验证（1-2 周）→ CI 云签名集成（SimplySign 网关；如集成受阻改投 SSL.com IV+eSigner 官方 action）→ msi 签名 + TSA 时间戳 | €49/年（备选方案总价更高） | 采购验证 1-2 周 + CI 2-5 天 | ① Certum 无官方 GitHub Action，集成工作量中 ② 459 天新规下年度重签打断 CI（凭证轮换） ③ SmartScreen 声誉非即时，勿在发布说明承诺「无警告」 | CI 凭证参数化+轮换文档化；声誉用 COS 下载量数据跟踪 |
| 6 | 软著送审（CCOPYRIGHT 在线填报 + 源码前后 30 页 + 手册 + 身份证明） | ¥0 | 材料半天；审结 30-60 工作日 | 材料格式驳回率高（源码页数/行数/排版规范） | 按官网《填表说明》模板准备；驳回即补正不重新排队 |
| 7 | （观察项）Microsoft Store 个体开发者注册评估 + 首个 msi/msix 提交 | $19 一次性 | 审核数天 | Store 对 JVM 桌面应用的打包形态要求（msix 化）可能额外工程 | 先以直链分发为主，Store 作为补充渠道验证 |

**依赖关系**：1→2→3 串行（备案链）；4、5、6 相互独立，可与 1-3 并行，建议 4 立即启动（收益最大）、5 跟进、6 顺手送审。

---

## 来源清单

**本轮一手核实（检索日期：2026-09-05，实测抓取）**
1. Microsoft Learn — What is Artifact Signing（Trusted Signing 更名口径，页面更新 2026-05-12）: https://learn.microsoft.com/en-us/azure/trusted-signing/overview
2. Microsoft Learn — Artifact Signing quickstart（Public Trust 个人仅美/加；组织 12 国/地区白名单原文）: https://learn.microsoft.com/en-us/azure/trusted-signing/quickstart
3. Azure — Trusted Signing 定价（Basic 5,000 / Premium 100,000 签名每月；单价动态渲染未取实数）: https://azure.microsoft.com/en-us/pricing/details/trusted-signing/
4. Microsoft Learn — Microsoft Defender SmartScreen overview（防护范围/机制）: https://learn.microsoft.com/en-us/windows/security/operating-system-security/virus-and-threat-protection/microsoft-defender-smartscreen/
5. CA/Browser Forum — Code Signing Baseline Requirements（CSBR，FIPS 140-2 L2 2021-06-01 起全证书适用的原文条款）: https://github.com/cabforum/code-signing/blob/main/docs/CSBR.md （经 GitHub API 实抓原文）
6. Certum（Asseco 旗下 CA）— Code Signing 产品线现价与 459 天有效期公告（Open Source €49 起 / Standard set €169 起 / EV set €379 起）: https://shop.certum.eu/data-safety/code-signing.html
7. SSL.com — 代码签名产品线（IV/OV/EV/Sole Proprietor EV/eSigner 云签名）: https://www.ssl.com/certificates/individual-code-signing/ 、 https://www.ssl.com/esigner/
8. Apple — Notarizing macOS software before distribution（公证范围含 dmg、macOS 10.15 强制口径、Gatekeeper ticket 机制；经 Apple 文档 JSON API 实抓）: https://developer.apple.com/documentation/xcode/notarizing-macos-software-before-distribution
9. Apple — Program enrollment（「99 USD per membership year」原文）: https://developer.apple.com/programs/enroll/
10. 中国版权保护中心 — 计算机软件著作权登记指南·所需文件（个人可办/材料构成/办理时限专页存在性）: https://www.ccopyright.com.cn/index.php?optionid=1080

**基线继承（2026-08-17 已核实；本轮 2026-09-05 可达性抽查 HTTP 200）**
11. 基线文档：.nebflow/Spec/icp-beian-action-plan.md（其来源 1-19：腾讯云备案产品文档 12 条、工信部平台 3 条、.space 后缀资质 2 条、购买页 2 条，逐条 URL 见基线文末）
12. 抽查通过：https://cloud.tencent.com/document/product/243/18908 ｜ 243/37402 ｜ 243/39038 （均 HTTP 200，检索 2026-09-05）

**主仓现状锚点（2026-09-05 核对）**
13. `.github/workflows/release.yml:82-202`（三平台 jpackage 打包矩阵）、`:226-239`（资产收集）、`:248-270`（Release+COS 镜像）
14. `packaging/build-dmg.sh:9`、`packaging/build-msi.sh:15`（无签名现状的自证注释）；`VERSION:1`（1.4.1-beta.54）；`src/main/resources/web/js/brand.js:28,50`（双域引用）

## 未决项（显式列出）

1. **App 备案通知原文未直接验证**（gov.cn 403 / miit 反爬）——桌面软件不适用 App 备案的结论基于适用范围定义+接入商指引交叉，建议作者人工复核 beian.miit.gov.cn 通知栏（二.6 已标注）。
2. **SmartScreen 声誉阈值无官方量化口径**——旧 eligibility 专页已下线（404 实测）；「数天-数周/数百-数千下载」为社区与 CA 经验口径，微软未公布算法。
3. **SSL.com IV 证书与 eSigner 当期实价未取到**（页面标价动态渲染）——Certum 价格已实抓为锚，SSL.com 作为备选时以官网实时价为准。
4. **软著「办理时限」专页 URL 未抓实**（ccopyright 站内菜单 JS 渲染）——60 日法规口径来自《计算机软件著作权登记办法》既有文本，实际周期以送审时官网口径为准。
5. **国内分发平台逐平台入驻要求未做逐家核实**（概览级结论）——入驻前以目标平台当期协议为准。
6. Apple notarization 流程子页（customizing-the-notarization-workflow）JSON 本轮抓取受限，命令口径（notarytool store-credentials/submit、stapler staple）为 Apple 公开稳定 CLI，实施时以文档页为准。
7. Microsoft Store 个体注册费 $19 为历史口径，注册页未本轮验证（行动清单第 7 步为观察项，低影响）。
