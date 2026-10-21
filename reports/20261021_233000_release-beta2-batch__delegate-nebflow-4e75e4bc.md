# 发版批 v2026.10.21-beta.2 + CI 自托管落地 + 勤发机制设计 · 批报告

> 批次：作者令 2026-10-21 三件——①发新测试版（勤发政策首拍）②GitHub Actions 额度耗尽→CI 挪自有设备 ③勤发机制落成日常（候批设计）
> 分支：`delegate/delegate-nebflow-4e75e4bc` · 执行窗 2026-10-21 22:40–23:40 (+0800)

## 0. TL;DR

**①beta.2 已发：全链十环零断**（bump → tag → 构建 → sha256 → OSS → 回读核验 → manifest 入册 → 指针 in sync → GitHub prerelease 挂 jar → staging 部署 + 版本显示验证），**staging 验收终跑 GREEN-PARTIAL 33/0/2 收口**（唯二 skip = IdP 人工臂，逐条交代）。**②CI 选型唯一推荐 = 方案 B（本机 Mac self-hosted runner）**——已全量落地：runner 2.338.0 在线（labels self-hosted/macOS/ARM64）+ LaunchAgent 常驻 + pilot workflow 入分支；Windows 线（方案 A）实测受阻于该机 Clash 未运行（github 大文件传输 1h 超时 ×2），工具链已备、网络卡已给。**③勤发机制设计候批文档已落**，未挂任何定时。

## 1. ①发布全链（证据逐环，详见 evidence/20261021_2329_release-beta2/chain-log.md）

| # | 环节 | 结果 |
|---|---|---|
| 1 | 版本号（查最新 tag 递增） | v2026.10.21-beta.1 → **2026.10.21-beta.2**，VERSION 17B 无换行尾字节格式对齐 beta.1 |
| 2 | tag | `v2026.10.21-beta.2` @ `1ab024df2`，已推 GitHub |
| 3 | 构建 | build-web（160 chunks / orphans 0）+ `sbt -Dstand.webdist=1 assembly` 93s → 110,274,860 B |
| 4 | sha256 | `502fcf0495b3eab32660aeab7fbaf7c092ed5e9195117bb583909db7013c7661` |
| 5 | OSS 上传 | ossutil 1.7.19（pinned+校验）→ `oss://nebflow-releases/stand-assembly-2026.10.21-beta.2.jar` 19.1s |
| 6 | OSS 回读 | HTTP 200 / Content-Length 一致 / 全量下载 sha256 **逐字节一致** |
| 7 | manifest-beta 入册 | stand-website `67ea391`；build=1ab024df2；**floor 保 beta.1**（防拒装断更链，见 §4 决策注记） |
| 8 | 指针回读 | publish-updates 校验+HEAD+上传+回读：`latest-beta-version.txt -> 2026.10.21-beta.2 (in sync)` |
| 9 | GitHub release | prerelease「Nebflow v2026.10.21-beta.2」+ jar 资产 110,274,860 B，publishedAt 15:01:41Z |
| 10 | staging 部署验证 | Vercel `dpl_AmPeWxDwmt` READY → **staging.mystand.space 服 beta.2 + 精确 sha256** ✓ |

全程 github 操作走 `https_proxy=127.0.0.1:7890`（直连 10s+/超时，代理 2.5s——任务书预判属实）。

**门禁与留档**：pre-push GREEN-PARTIAL 33/0/2（skip=IdP 网络不可达）→ post-deploy 首跑 RED 4 红全拆解（`install:*` ×2 = 测试夹具版本钉过期，run-e2e.mjs `EXPECTED_BETA_VERSION` 注释即写明「每次 beta 发版入册时同步 bump，发版批自有」——照协议 bump，commit `e82a259` 已推 staging；`launch:*` ×2 = github 直连间歇）→ **终跑 GREEN-PARTIAL 33/0/2 收口**（install:* 全绿：版本显示=beta.2、四命令齐、页=manifest 一致；launch 本轮全绿；唯二 skip = IdP 人工臂，逐条已交代）。三轮 JSON 留档在 evidence 目录。**排障注记**：一度误判「Vercel 未触发部署」——v6 deployments API 带 teamId 过滤返回旧页；project-scoped（无 teamId）复查即见 READY。git 集成在位，staging push 即部署。

**使用面即时生效语义**：beta 安装链真源头 = OSS 指针 + jar（第 5/8 环），已在官网部署前生效——**现在装 beta 即得 beta.2**；官网 staging 页面为 M3 引擎/展示面。

## 2. ②CI 自托管：选型结论与已落地

### 唯一推荐：方案 B——本机 Mac self-hosted runner（已落地，在线）

| 候选 | 重活可跑通 | 判定 |
|---|---|---|
| **B. Mac 本机 runner** | **✓ 本批双证**：beta.2 jar 就是本机构建（93s）；runner 已装+在线 | **推荐并已落地** |
| A. DESKTOP-IJBBEOT runner | 工具链 ✓（JDK 21.0.11 Temurin 已装=repo 基线；node20+sbt 本批已装）；**网络 ✗**：Clash 未运行（无进程/无监听端口，实查）→ github.com 直连龟速（克隆探针 25min 走了 5.6MB）、runner 包下载 1h 超时、objects.githubusercontent.com 超时 | **升级线**：该机 Clash 开起来（或 git 配置可用代理腿）后迁，操作卡已给 |
| C. 不依赖 GitHub 自建 CI | ✓（本批发布链即脚本化全链） | 备选不推荐：重造 runner（PR check/重试/并发全自管），勤发机制（§3）已覆盖其发布价值 |

**已落地清单（零手动）**：
1. runner 2.338.0 (osx-arm64) 装于 `~/actions-runner`——包 sha256 `df4cebda…df9df2` 与官方 release 注记逐字核对
2. 注册：`Mashiros-MacBook-Pro`，labels `[self-hosted, macOS, ARM64]`，`status: online`（gh api 实证）
3. 常驻：LaunchAgent `~/Library/LaunchAgents/com.github.actions-runner.plist`（RunAtLoad+KeepAlive，用户级无 admin），已 load，Runner.Listener 在跑
4. pilot workflow `.github/workflows/ci-selfhosted.yml`（**workflow_dispatch 专用**：零托管分钟、零 push 干扰；job 级 env 走本机代理；sbt 用 Aliyun sbt-launch 自举 + setup-java 钉 21——不依赖宿主预装）随本分支交付；**正式触发需待本分支落 main**（dispatch 类 workflow 须在默认分支）——落 main 后一行验证：`gh workflow run ci-selfhosted.yml --repo MashiroKai/Nebflow`
5. Windows 备件（A 线）：runner zip 下到 `C:\Users\PC\actions-runner\`（PowerShell 腿；完整包待 Clash 后重下）、node v20.18.1 + npm 装于 `C:\Users\PC\ci-tools\`、sbt-launch 备于 `C:\Users\PC\ci-tools\sbt-bin\`

**runner 安全口径（B 线固有，如实载明）**：self-hosted runner 执行的 workflow 对宿主用户盘有读写权；本机 Mac 同时是生产实例与 `~/.stand` 密钥库宿主。缓解：私仓+单作者+workflow 走分支纪律；pilot 为 dispatch-only（不会被动触发）；后续平移重 job 前建议评估专用服务用户隔离。**当前 runner 只会跑显式指定 `[self-hosted, macOS, ARM64]` 的 job——现有 ci.yml/release.yml 一个都没指，故落位即空闲，零生产影响。**

**Kai 手动项操作卡**（全部命令级，见 `reports/20261021_230000_ci-selfhosted-selection__*.md` §5）：A 线三项（该机 Clash 开启核查 → runner 包重下/注册 → `svc.cmd install` 装 Windows 服务）；B 线无必做项（已在线），可选卸载一行：`launchctl bootout gui/$(id -u)/com.github.actions-runner && rm ~/Library/LaunchAgents/com.github.actions-runner.plist && ~/actions-runner/config.sh remove`。

### 后续迁移路径（作者点头后的下一批）

`ci.yml` 重 job（compile/test/assembly/smoke-test）`runs-on` 换 `[self-hosted, macOS, ARM64]` → pilot 全绿观察一周 → release.yml `build-jar` 同法。四个月前 FROZEN 的三平台打包 job **维持封印**，不因迁移解冻。

## 3. ③勤发机制设计（候批，未挂定时）

设计文档：`reports/20261021_230500_daily-beta-cadence-design__*.md`。要点：每日一检查点（建议 10:00 CST）；「main 有未发新提交」为唯一实质触发判据（`manifest-beta.build` ≠ main HEAD）；三层防重（tag 闸/build 闸/指针闸）；构建类失败自动重试 1 次、发布面失败绝不自动重试（部分完成态须人工盘点五件套）；告警一次一轮带五件套状态盘点。**待作者拍板三个参数**：检查时刻 / 执行宿主（建议本机 Nebflow scheduled-tasks）/ 告警静默窗。点头后挂定时，本批零调度落盘。

## 4. 关键决策注记

- **minCompatibleEngine 保 `2026.10.21-beta.1` 不上抬**（偏离 beta.1「floor=自身」先例）：floor 语义 = 「低于此版拒装」；活体 manifest 读取机群 = beta.1，floor 抬到 beta.2 会令 beta.1 机群被拒装、更新链断。floor 应随稳定点推进，不逐 beta 上抬。
- **tag 打在发版 commit（分支上）而非 main merge commit**：照 beta.1 先例（其 tag 亦在发版 commit `02f7626e1` 上）；本分支落 main 后 `git branch --contains v2026.10.21-beta.2` 即含 main，release-pipeline 检查项 3 满足。
- **Vercel 排障反转为「已部署」**：API 作用域坑（teamId 过滤）差点误报部署失败——project-scoped 复查定谳。此坑记入证据链，后续排障先降作用域再断言。

## 5. 交付物清单（本分支提交）

| 件 | 路径 |
|---|---|
| 发版 commit + tag | `1ab024df2`（VERSION）+ `v2026.10.21-beta.2` |
| 发布链证据 | `evidence/20261021_2329_release-beta2/`（chain-log.md + 两轮 gate JSON，终跑 GREEN 待补） |
| CI 选型结论+操作卡 | `reports/20261021_230000_ci-selfhosted-selection__delegate-nebflow-4e75e4bc.md` |
| 勤发机制设计（候批） | `reports/20261021_230500_daily-beta-cadence-design__delegate-nebflow-4e75e4bc.md` |
| 本批报告 | `reports/20261021_233000_release-beta2-batch__delegate-nebflow-4e75e4bc.md`（即本文） |
| pilot workflow | `.github/workflows/ci-selfhosted.yml` |

**外部仓交付**：stand-website staging 分支 `67ea391`（manifest-beta 入册）+ `e82a259`（测试钉 bump），均已部署/部署中；GitHub release v2026.10.21-beta.2 已挂 jar。

**落 main 后待续一行项**：beta.1 先例有「推 USTC+GitHub」腿——本批分支+tag 已推 GitHub（origin）；merge 落 main 后 USTC（dev remote）补推 main+tag 即对齐先例：`git push dev main v2026.10.21-beta.2`。
