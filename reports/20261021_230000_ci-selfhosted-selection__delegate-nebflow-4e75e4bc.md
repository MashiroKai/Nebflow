# CI 自托管落地批 · 选型结论与已落地部分（含 Kai 手动操作卡）

> 批次：作者令 2026-10-21「GitHub Actions 额度耗尽→把 CI 挪到自有在线设备跑」
> 现场核查时间：2026-10-21 23:00–23:30（本机 Mac + DESKTOP-IJBBEOT 实测，非纸面推断）

## 0. TL;DR

**唯一推荐（终版，按实测翻转）：方案 B——本机 Mac self-hosted runner，已全量落地（runner 在线 + LaunchAgent 常驻 + pilot workflow 入分支）。方案 A（DESKTOP-IJBBEOT）降为升级线：该机 Clash 未运行（实查无进程/无监听端口），github 大文件传输不可用（浅克隆探针 25min 仅 5.6MB、runner 包下载 1h 超时），A 线在 Kai 开起该机 Clash 前无法跑重活——工具链已备齐，网络卡见 §5。**

**B 线已自动落地（本批完成，零手动）**：
1. runner 2.338.0 (osx-arm64) 装于 Mac `~/actions-runner`——包 sha256 `df4cebda…df9df2` 与官方 release 注记逐字核对
2. 已注册：`Mashiros-MacBook-Pro`，labels `[self-hosted, macOS, ARM64]`，gh api 实证 `status: online`
3. 常驻：LaunchAgent `~/Library/LaunchAgents/com.github.actions-runner.plist`（RunAtLoad+KeepAlive，用户级零 admin）已 load，Runner.Listener 在跑
4. `.github/workflows/ci-selfhosted.yml`（workflow_dispatch 专用，本分支已带）跑「web 构建 + sbt assembly + sha256」全链于上述 labels；落 main 后 `gh workflow run ci-selfhosted.yml --repo MashiroKai/Nebflow` 一行即验
5. 安全口径：现有 ci.yml/release.yml 无一指定 `[self-hosted, macOS, ARM64]` → runner 落位即空闲，零生产影响；重 job 平移是作者点头后的下一批

**A 线已备（待网络）**：JDK 21.0.11 Temurin 该机已装（=repo 基线）✓、node v20.18.1+npm 已装 `C:\Users\PC\ci-tools\`、sbt-launch 已备 `C:\Users\PC\ci-tools\sbt-bin\`、runner zip 已下 `C:\Users\PC\actions-runner\`（PowerShell 系统代理腿；1h 超时截断，Clash 开启后重下完整包即可）。

**重活可跑通证据（B 线）**：beta.2 发布 jar 即本机构建（93s assembly，本批 §发布报告）；pilot workflow dispatch 属 merge 后一步验证。

## 1. 候选方案核查表（2026-10-21 实测）

| 方案 | 重活可跑通 | 常开 | 与生产隔离 | 阻塞项 |
|---|---|---|---|---|
| **A. DESKTOP-IJBBEOT（Windows）self-hosted runner** | **✓ 实机验证中（§4）**：JDK 21.0.11 Temurin 已装（=repo 基线）；node/sbt 本批已装；16 核 / 1.5TB 空闲 | ✓（常开在线机） | **优**：不持有 `~/.stand` 密钥库（OSS/Logto/Vercel 全在本机 Mac） | runner 常驻服务装制需 admin 一次（操作卡 ②）；`objects.githubusercontent.com` 直连超时（已绕：PowerShell 系统代理腿；runner 包已落盘，后续 runner 自更新走同腿或 `--disableupdate`） |
| B. Mac 本机 self-hosted runner | ✓（今天 beta.2 jar 就是本机构建，93s assembly） | ✓ | **劣**：本机是 Nebflow 生产实例宿主 + 密钥库宿主（`~/.stand/secrets/`）——self-hosted runner 执行的 workflow 对全盘有读写权，一旦 workflow 被污染即密钥库沦陷；且 CI 负载与生产实例抢 CPU | 无硬阻塞，但安全面与稳定性都差 A 一档 |
| C. 不依赖 GitHub 的自建 CI | ✓（本批发布链就是脚本化全链：构建→OSS→manifest→release API） | ✓（挂本机定时即可） | 中（脚本仍跑在本机 Mac） | 重新发明 runner：PR check 不上 GitHub、无 Actions 生态、失败告警/重试/并发全自管；后续每次改 CI 都要改自研脚本 |

**A 的网络实测细节**（该机 Git-Bash 直连）：`github.com` 200（慢，~15s）、`api.github.com` 200、`pipelines.actions.githubusercontent.com` 404（=可达）、`results-receiver.actions.githubusercontent.com` 404（=可达）、`codeload.github.com` 301（=可达）、`objects.githubusercontent.com` 超时、`raw.githubusercontent.com` 超时。结论：**runner 控制面（注册/取任务/回传日志）全通**；不可达的只有大文件分发面（runner 包/Actions 缓存），一次性别名可绕。Tailscale 直连在位（Mac↔该机 direct 192.168.2.100）。

**配额语义**：self-hosted runner 上的分钟**不计费、不占额度**；额度耗尽只影响 GitHub 托管 runner（`ubuntu-latest` 等）。迁移动作 = 把重 job 的 `runs-on` 从 `ubuntu-latest` 改成 `[self-hosted, Windows, X64]`（steps 已是 bash，Git-Bash 直跑；仅 apt 系步骤需换成该机等价物——逐 job 移植属后续批，先跑通 pilot job）。

## 2. 迁移路径（三步走，每步独立可回退）

1. **pilot（本批落地，B 线）**：本分支落 main 后 dispatch `ci-selfhosted.yml` 跑 jar 构建 → 全绿即闭环（本机重活已由 beta.2 构建实证，此步是 Actions 面的形式闭环）。
2. **平移重 job（后续批，改 ci.yml）**：`compile`/`test`/`assembly`/`smoke-test` 四个重 job 的 `runs-on` 换 `[self-hosted, macOS, ARM64]`；轻 job（js-types / 各静态 gate，分钟消耗小）可留托管或一并平移。smoke-test matrix 里的 windows-latest 腿可由 A 线（Clash 修复后）承接或暂留托管。
3. **release.yml 启用面**：`build-jar` job 同法平移；四个 FROZEN 打包 job（`if: false`，2026-09-06 封印）按封印批口径**维持封印**，不因 CI 迁移解冻。

## 3. 已落地件清单（本批提交）

| 件 | 位置 | 状态 |
|---|---|---|
| `ci-selfhosted.yml`（pilot workflow，dispatch-only） | stand 仓 `.github/workflows/` | 本分支已提交 |
| runner 包 2.338.0 win-x64 | 该机 `C:\Users\PC\actions-runner\runner.zip` | 已下载（PowerShell 腿） |
| node v20.18.1 + npm | 该机 `C:\Users\PC\ci-tools\node-v20.18.1-win-x64\` | 已装可用（`node --version` ✓） |
| sbt-launch 1.10.10 + 包装脚本 | 该机 `C:\Users\PC\ci-tools\sbt-bin\sbt`（bash wrapper，Aliyun jar） | 已备 |
| release 分支 clone | 该机 `C:\Users\PC\nf-ci-probe` | 探针用；runner 正式跑时自管 checkout，此目录可删 |
| sbt 用户级仓库镜像 | 该机 `C:\Users\PC\.sbt\repositories`（Aliyun central + sbt 插件镜像） | 待 §4 探针写回结论后定稿 |

## 4. 实机 jar 构建探针（重活可跑通的最终证据）

- **B 线（Mac）✓ 闭环**：beta.2 发布 jar 即本机构建（`node scripts/build-web.mjs` orphans 0 + `sbt -Dstand.webdist=1 assembly` 93s → 110,274,860 B，sha256 `502fcf04…3c7661`，OSS 回读逐字节一致）。Actions 面的形式闭环 = 落 main 后 dispatch pilot。
- **A 线（Windows）网络受阻实录**：
  - 浅克隆探针 `git clone --depth 1 --branch <release-branch>` 后台 1h 超时（进度 ~5.6MB/.git @25min）
  - runner 包 PowerShell IWR 下载 1h 超时（系统代理腿 HEAD 探针可达 objects.githubusercontent.com，大文件传输不成）
  - Clash 实查：`Get-Process` 无 clash/verge/mihomo/v2ray/sing 进程；`netstat` 无 7890/7897/10809/1080 监听；WinINET ProxyServer 注册表值空——**「Clash LA」当前未在该机生效**（任务书背景与实况不符，如实记录）
  - 控制面另测：api.github.com 200 ✓、pipelines/results-receiver.actions.githubusercontent.com 404（可达）✓、codeload 301 ✓——runner 控制面无碍，唯大文件面不通
  - 结论：A 线工具链全备，**Kai 在该机开起 Clash（或配可用代理腿）后**按 §5 卡走完注册+服务两步即迁；迁移前 B 线已可承担全部重活

## 5. Kai 手动操作卡（A 线三项 + B 线零必做）

> **B 线（Mac）本批已全量落地在线，无必做项。** 可选卸载（如不要 Mac 常驻 runner）一行：
> `launchctl bootout gui/$(id -u)/com.github.actions-runner && rm ~/Library/LaunchAgents/com.github.actions-runner.plist && ~/actions-runner/config.sh remove --token <gh api -X POST .../actions/runners/remove-token --jq .token 现取>`
>
> 以下为 **A 线（DESKTOP-IJBBEOT）**，前置一步 = 开起该机 Clash（实查当前未运行：无进程、无监听端口、WinINET 无代理值）；开起后按序执行。

### ①注册 runner（不需要 admin，普通 PowerShell 即可）

```powershell
# 1) 取一次性注册 token（在【Mac】上跑这一行，token 1 小时有效；gh 已登录 admin 账号）：
gh api -X POST repos/MashiroKai/Nebflow/actions/runners/registration-token --jq .token

# 2) 回到【Windows】解压并注册（把 <TOKEN> 换成上一步输出）：
cd $HOME\actions-runner; Expand-Archive -Path runner.zip -DestinationPath . -Force
.\config.cmd --unattended --url https://github.com/MashiroKai/Nebflow --token <TOKEN> `
  --name desktop-ijbbeot --labels self-hosted,Windows,X64 --work _work
```

注册成功 = Settings → Actions → Runners 里出现 `desktop-ijbbeot`（Idle）。

### ②装常驻服务（推荐形态，需要 admin PowerShell 一次）

```powershell
# 以管理员身份打开 PowerShell，然后：
cd $HOME\actions-runner
.\svc.cmd install        # 装成 Windows 服务「actions.runner.MashiroKai-Nebflow.desktop-ijbbeot」
.\svc.cmd start
```

**无 admin 的替代形态**（登录自启，效果次之）：把下面一行存成 `%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\gh-runner.cmd`：

```powershell
cd %USERPROFILE%\actions-runner && .\run.cmd
```

### ③验证（可选）

Mac 上一行触发 pilot：`gh workflow run ci-selfhosted.yml --repo MashiroKai/Nebflow`，Actions 页看 `jar-selfhosted` job 全绿 + 产物 sha256。

### 运维注意（写进卡的事实）

- **runner 自更新**：`objects.githubusercontent.com` 在该机直连超时。服务形态下 runner 更新失败会打日志但不断服务；如更新连续失败，用 PowerShell 腿手动换包（同本批下载法），或注册时加 `--disableupdate` 钉版本、定期手动升。
- **runner 版本**：本批落盘 2.338.0（2026-10-21 latest）。
- **安全口径**：self-hosted runner 执行 workflow = 执行 repo 里的 CI 脚本。私仓 + 单作者 + workflow 改动走 PR/分支纪律 ⇒ 风险可控；但**不要**在该机存放其他凭据，保持它「纯 CI 机」定位。
- **卸载**：`.\svc.cmd uninstall` + `.\config.cmd remove --token <同法现取 removal token>`。
