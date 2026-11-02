---
type: audit
status: done
related: "[[20261029_175151_plugin-repo-registry]]"
---

# USTC Git 迁移评估：CI/CD 与发版能力（2026-11-01）

> 作者令：评估 git.ustc.edu.cn（中国科大超算中心 GitLab）能否满足 stand 的 CI/CD 与发版要求，产评估报告、不动任何仓库配置。本稿全部基于 2026-11-01 实测（本机 Mac + KAI + DESKTOP-IJBBEOT 三机、git/curl/gh 实探）与官方文档核验，纸面推断逐条标注。

## 0. 结论（先行）

**能满足，CI 一项的完整形态必须走自建 runner；git、发版、自动化三面均可满足。**

- **git 主体**：已经是现实——主开发线已实际在 USTC（`dev` 远端，SSH 校内外可用实测通；GitHub `origin/main` 落后 dev/main **399 commits**）。
- **CI/CD**：shared runner 匿名不可见（需登录确认）；**自建 runner 是可行且已被 stand 自己验证过一次的路线**（2026-10-21 Mac self-hosted runner pilot 全绿，见 `reports/20261021_230000_ci-selfhosted-selection__delegate-nebflow-4e75e4bc`）。且 USTC 国内直连带来一个 GitHub 路线没有的红利：**两台 Windows 机在 GitHub 下连不上（KAI 实测 github.com=000），在 USTC 下直连 200**——Windows CI 腿反而比现在更强。
- **发版**：GitLab Release + Generic Packages Registry 均为 Free 层功能（官方文档核验），可替代 GitHub Releases；且发布下载主面本就是阿里云 OSS（install.sh/ps1 指向 OSS + mystand.space），GitHub Releases 只是次级分发面——**发版面迁移代价比预想薄**。
- **缺口**：①pull mirror 仅 Premium（Free 不可用）——不阻塞，git 直推覆盖；②外部贡献者准入（统一身份认证门槛，GitHub PR 流会断）；③站方配额/SLA/条款无公开文档（§4「需站方确认」清单）。
- **推荐**：迁。迁移路线见 §3；工作量净估 4–4.5 个工作日，其中 CI workflow 平移占大头。

## 1. 现状盘点：stand 仓对 GitHub 的依赖面

### 1.1 `.github/` 全目录清单

| 件 | 内容 |
|---|---|
| `workflows/ci.yml` | 主 CI，10 job（详见 §1.2） |
| `workflows/release.yml` | beta 发版链（push → beta 分支） |
| `workflows/auto-release.yml` | stable 发版链（push → **release 分支** + workflow_dispatch dry_run） |
| `workflows/ci-selfhosted.yml` | 自托管 jar 构建试点（workflow_dispatch，self-hosted macOS ARM64） |
| `workflows/user-knowledge-gate-wide.yml` | 全仓泄漏审计（workflow_dispatch，默认不启用） |
| `release.yml` | GitHub Release notes 分组配置（labels → 分类） |
| dependabot | **不存在**（find 实查无 `dependabot*`） |
| ISSUE_TEMPLATE / FUNDING 等 | **不存在** |

### 1.2 Actions workflows 逐条

| workflow | 触发 | 做什么 | 密钥/权限 |
|---|---|---|---|
| **ci.yml** | push/PR → main；workflow_dispatch | ①js-types：5 个 Node 静态门禁（checkJs/circular/IME/msgstyle/modellist + local-coupling/tag-format 共 7 步）②user-knowledge-gate、dead-logging-gate、jdk-baseline-gate、installer-home-gate、legacy-domain-gate（5 个 fail-fast 静态门禁）③lint（JDK23+sbt scalafmt/scalafix + scala 分层门禁）④compile（matrix ubuntu+windows）⑤test（ubuntu，-Xmx6g，90min timeout）⑥assembly（web bundle+sbt assembly+upload-artifact）⑦smoke-test（matrix，起 jar 起服打 REST）⑧frontend-smoke（Playwright chromium+真实后端）⑨frontend-specs（Playwright 全量自足 spec，continue-on-error）⑩docker-test（镜像构建+J17 复现门禁） | 无 secrets（全部 Hermetic 门禁） |
| **release.yml**（beta） | push → beta | build-jar（test+web bundle+assembly）→ tag（无 v 前缀）→ **GitHub Release（prerelease=true, softprops/action-gh-release）** → jar 上传 **OSS**（主下载面）→ latest-beta-version.txt → deps/ 镜像表重灌 + 可达性断言 | secrets: `OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET`；permissions: contents:write |
| **auto-release.yml**（stable） | push → **release 分支**；workflow_dispatch(dry_run) | 同上但 prerelease=false、写 `latest-version.txt`、末步 **gh api 把 VERSION 回写 main**（contents API PUT） | secrets: OSS 两枚 + 内置 `GITHUB_TOKEN`（回写 main 用） |
| **ci-selfhosted.yml** | workflow_dispatch | self-hosted runner（Mac）跑 web bundle+assembly+sha256 重活样板 | 无 secrets |
| **user-knowledge-gate-wide.yml** | workflow_dispatch | 全仓 `check-user-knowledge.sh --all` 审计 | 无 secrets |

### 1.3 GitHub 专有设施依赖矩阵（平移即改点）

| GitHub 设施 | 用在哪 | GitLab 对应 | 平移难度 |
|---|---|---|---|
| 托管 runner matrix（ubuntu/windows） | ci.yml compile/smoke matrix；release 全链 | 自建 runner（按 OS 挂标签） | ★★★ workflow 重写主体 |
| `actions/checkout@v4` 等 marketplace actions | 全部 workflow | GitLab 无 marketplace：checkout 自带（GIT_DEPTH），其余手写 script | ★★★ |
| upload/download-artifact（job 间产物） | assembly→smoke/docker/frontend | `artifacts:` + `needs` 传递 | ★★ |
| `actions/setup-java cache: sbt` / `setup-node cache: npm` | compile/test/lint | GitLab `cache:`（相对路径 key） | ★★ |
| `secrets.OSS_ACCESS_KEY_*` | release/auto-release | GitLab CI/CD Variables（masked） | ★ |
| `secrets.GITHUB_TOKEN` 自动 token | auto-release 回写 main、release push tag | `CI_JOB_TOKEN`（推本仓可用）或 project access token（更稳） | ★ |
| softprops/action-gh-release | 两个 release workflow | `release:` 关键字 / release-cli | ★★ |
| `gh api contents PUT`（版本回写 main） | auto-release | GitLab commits API（POST repository/commits） | ★ |
| `generate_release_notes` + `.github/release.yml` 分类 | 两个 release workflow | **无直接等价**：release notes 需脚本生成（git log 区间） | ★★ |
| `workflow_dispatch` + inputs(dry_run) | 3 个 workflow | 手动跑 pipeline + variables（等价） | ★ |
| `concurrency` group | ci-selfhosted | `resource_group`（等价） | ★ |
| Dependabot | 未使用 | —（未用即无断点；未来可用 renovate 自托管） | 无 |

### 1.4 发布链与 GitHub 面

- **下载主面 = 阿里云 OSS**（`nebflow-releases.oss-cn-hangzhou.aliyuncs.com`）：install.sh/install.ps1 版本解析（latest-beta-version.txt / latest-version.txt）与 jar 下载全部走 OSS；README 安装入口 `curl -fsSL https://mystand.space/install.sh | sh`（自有域）。**这条用户主链完全不依赖 GitHub**。
- **GitHub Releases = 次级分发面**：现 42 个 release（匿名 API 实测），2026-09 封印桌面打包后 jar-only（beta prerelease=true / stable false）；历史上有 dmg/msi/deb 资产（v1.4.1-beta.53/54）。
- **GitHub Packages：未使用**（sbt 无 publishTo 配置，workflow 无 registry push 步骤——grep 实查）。
- **GitHub Pages：未使用**（pages API 404；官网 mystand.space 独立，官网仓另有人查）。
- **自引用面**：README badge（shields.io → github.com releases 链接）×2、README Releases 链接、install.ps1 注释里的历史引用。raw.githubusercontent 引用仅 Homebrew 安装引导（第三方依赖，非本仓发布面）+ scripts/upload-deps.sh 依赖镜像表（含 ghproxy 前缀——国内补丁，指向的也是第三方依赖非本仓）。
- **种子插件外部源 = GitHub 公开仓**（[[20261029_175151_plugin-repo-registry]]：document-production、nebflow-plugin-creator、slideblocks、web-search-toolkit、visual-report、browser-use 等 MashiroKai 公开仓）。插件分发面独立于主仓，**不随主仓迁移而动**；若目标升格为「全面离开 GitHub」，这些仓是第二阶段清单（本报告范围外，点名存档）。
- **scratch 远端**（github.com/MashiroKai/nebflow-ci-verify）：CI 验证专用公开仓（绕私有仓 Actions 额度的历史产物），迁移后角色消失可归档。

### 1.5 使用度快照（gh api 实测）

| 面 | 状态 |
|---|---|
| Actions | 主 CI 每次 push main 触发全量；**最近 3 次 run 全红**（10-29/10-25/10-24）——红因是内容门禁（Lint scalafmt、Dead Logging、User Knowledge Gate、circular deps、Test），非平台配额；compile 双 OS 腿绿 |
| Releases | 42 个（jar-only 现行；历史 dmg/msi/deb 已封） |
| Packages / Pages | 均未使用 |
| PR | 48 个累计，含外部贡献者（Phenol1145、JiashengZeng）——轻度外部协作史 |
| 仓可见性 | **public**（`"private": false` 匿名 API 实测；install.ps1 注释「repo is public again」吻合） |

### 1.6 分支模型与远端拓扑

- 三支模型实名为 **main（CI 门面）/ beta（beta 发版）/ release（stable 发版）**+ `stable/*` 历史快照支。注意：**stable 发布分支名为 `release` 不是 `stable`**（auto-release.yml 触发面）。
- 远端三件：`origin`=GitHub（落后）、`dev`=USTC（**实际准源**）、`scratch`=GitHub 验证仓。
- 同步实况（2026-11-01 fetch 实测）：`origin/main` **落后 `dev/main` 399 commits**（作者口径 393，为持续提交的自然增长）；本地主仓 main 与工作树 HEAD 一致（b6ceb9d0e），领先 dev/main 9 commits（dev 还在追本周合并）。

### 1.7 CI 前史（为什么现在评估迁）

1. 2026-10-21：私有仓 Actions 免费额度耗尽 → 作者令「CI 挪到自有在线设备」→ Mac self-hosted runner 落地（LaunchAgent 常驻，pilot 全绿 4m35s）；Windows 机（DESKTOP-IJBBEOT）因 Clash 未开、GitHub 大文件面超时搁置。
2. 2026-10-24：CI 迁移批把全量 CI 写回 GitHub 托管 runner 跑（scratch 公开仓先行验证；后来主仓转 public，托管恢复可用）；但**账号 Artifact 存储配额已耗尽**（trace 上传步骤因 `Artifact storage quota has been hit` 删除，ci.yml 内注释存证）。
3. 现在：主开发线已在 USTC dev，GitHub 只是落后镜像 + 次级发布面——**「双远端分叉」本身就是当前最大风险**（393→399 的漂移只会扩大）。

## 2. USTC GitLab 能力评估

### 2.1 平台底册（实测+公开信息）

| 项 | 实测/来源 |
|---|---|
| 运营方 | 中国科大网络信息中心/超算中心（首页署名；搜索索引快照：「中国科大超级计算中心的Git服务平台」） |
| 登录 | 中国科大统一身份认证，首次登录自动建号 |
| **校外访问新政** | 「9 月 25 日起校外访问本站需要登录；校内不登录可浏览；git 命令行改用访问令牌；校外无账号合作者邮件 gitlab@ustc.edu.cn 申请」（站方公告，搜索快照） |
| git 传输 | **SSH 校内外均可用、校外推荐 SSH**（站方口径）；本机 `ssh -T git@git.ustc.edu.cn` 实测 → `Welcome to GitLab, @kaiyu!`；`git fetch dev` 实测通 |
| API 匿名面 | `/api/v4/version`、`/user` 401；`/projects?visibility=public` 200；`projects/kaiyu%2Fnebflow` 404（**项目为私有**） |
| 实例版本 | 匿名不可得（登录页/API 均不暴露）——**需站方确认**；影响 runner 注册流程形态（§2.2 两版流程都给） |

### 2.2 CI/CD

**shared runner**：匿名 API 401、项目页 404，无法匿名判断站方是否给项目挂了 shared runner——**需登录后 Settings→CI/CD→Runners 查看，或向站方确认**。教育网自管 GitLab 通常 runner 资源有限或不向个人项目开放，方案设计**不把 shared runner 当依赖**。

**自建 runner 路线（主推，评估如下）**：

注册流程（作者一键可办，两种版本形态按实例版本取一）：
- **新版（GitLab 16+，注册 token 已废弃）**：项目 Settings→CI/CD→Runners→**New project runner**（勾 OS/架构、标签）→ 得 `glrt-` 开头 runner token → 宿主机 `gitlab-runner register --url https://git.ustc.edu.cn --token glrt-xxx`。需项目 Owner/Maintainer 权限——**kaiyu 是 nebflow 项目 Owner，满足**。
- **旧版（<16 或站方未关注册 token）**：同页面取 registration token → `gitlab-runner register --url … --registration-token GR1348941xxx`。
- gitlab-runner 安装：macOS `brew install gitlab-runner` + `brew services start gitlab-runner`（或 launchd 用户级，同现 GitHub runner 的 LaunchAgent 手法）；Windows 官方 pkg 装成 Windows 服务（需 admin 一次，操作卡已有前例）。

宿主候选与资源现实性（三机实测）：

| 宿主 | USTC 直连 | GitHub 直连 | 工具链 | 资源 | 评估 |
|---|---|---|---|---|---|
| **Mac 本机**（M2 Pro 12C/16GB/60G free） | ✓ 200 | ✓（走代理 7890） | JDK23 + sbt 1.10.10 + node20 + **docker 29.1.3** 在位 | test 钉 6g heap：与生产实例（本机常驻 Nebflow+密钥库）并存峰值紧但可行（beta.2 jar 本机 93s assembly 实证） | mac 腿 runner 现实；**安全口径沿 2026-10-21 报告**：本机是密钥库宿主，shell executor 对全盘读写，CI 污染即沦陷——用专用用户跑 runner + 敏感 job 上 docker executor |
| **KAI**（Windows） | **✓ 200（2.0s）** | **✗ 000 不通** | **JDK 21.0.12 Temurin 在位** | — | **USTC 路线独家红利**：GitHub 路线下此机连 runner 控制面都进不去；USTC 下可承接 windows 腿 |
| **DESKTOP-IJBBEOT**（Windows 16C/1.5TB） | **✓ 200（2.8s）** | △ 200 但 23s 龟速（Clash 未开的旧账） | JDK21/node20/sbt 已备（2026-10-21 批） | 三机最强 | **重活主力候选**：USTC 直连消除 Clash 依赖，A 线搁置项整体解锁 |

资源现实性结论：sbt 全量 test 在 M 级本机 15–30min（ci.yml 注释既有实测）、6g heap 16GB 机器可容；Windows 16 核机只会更快。自建 runner 分钟**无计费无配额**（自管语义），并发 `concurrent=1` 起步即可。**跑 sbt+JDK21 的 CI 在自建 runner 路线上无资源性阻塞。**

### 2.3 发版面

| GitHub 面 | USTC GitLab 对应 | 可用性 | 说明 |
|---|---|---|---|
| GitHub Release（attach jar） | **GitLab Release** | **Free ✓** | CI `release:` 关键字声明式创建（tag_name+assets links）；attach 走 Generic Packages + asset link。beta/stable 的 prerelease 语义 GitLab 无同名概念——用 tag 命名约定（beta.* 后缀）+ Release 说明字段区分 |
| Packages（未用） | **Generic Packages Registry** | **Free ✓**（官方文档核验：Free/Self-Managed，PUT `/projects/:id/packages/generic/<pkg>/<ver>/<file>`；认证 PAT/project token/CI job token/deploy token） | jar/安装包可入库；但 stand 的下载主面是 OSS——Generic Packages 定位为「GitLab 侧归档面」，OSS 仍是用户下载主面 |
| tag→release 自动化 | 同上 `release:` 关键字 | Free ✓ | release.yml 的「test→tag→Release→OSS→断言」链在 `.gitlab-ci.yml` 全部可表达；CI 内 git push tag 用 CI_JOB_TOKEN（推本仓）或 project access token（protected tag 场景更稳） |
| generate_release_notes 分类 | 无直接等价 | — | 用脚本按 git log 区间生成分组 notes（一次性脚本，半小时级） |

### 2.4 自动化面

| 项 | 可用性 | 说明 |
|---|---|---|
| API token 自助签发 | **✓** | 个人 Access Token：头像→Preferences→Access Tokens（scope: api），作者网页一键；**project access token**（robot 形态，Free 层有）更适合放 CI variable。本次评估无 web 登录态未实测按钮流，属 GitLab 标准能力 |
| webhook | Free ✓ | 覆盖现 GitHub webhook 用途（若有外部集成） |
| pipeline trigger token | Free ✓ | 触发跨 pipeline 等价 workflow_dispatch 场景 |
| pipeline schedule | Free ✓ | cron 定时 |
| **push mirror（USTC→GitHub）** | **Free ✓**（官方文档核验） | **过渡期关键件**：USTC 为准源后，GitHub 面自动跟随（branches/tags/commits） |
| **pull mirror（USTC 拉 GitHub）** | **✗ 仅 Premium/Ultimate**（官方文档原文「Available in the Premium and Ultimate tiers」） | 站方若装 Premium license 则可用——**需站方确认**；缺位不阻塞：初始历史一次性 `git push dev` 已覆盖（dev 远端已在用），后续 GitHub 侧合入的东西由作者手推或 push mirror 反向即可 |

### 2.5 约束面

| 项 | 评估 |
|---|---|
| 可用性/SLA | 校园服务，无公开 SLA；超算中心级运维但优先级在科研——**需站方确认**；缓解：OSS 主下载面 + push mirror 使 GitHub 面始终有全量副本 |
| 配额 | 仓/存储/管道配额无公开文档——**需站方确认**。stand 体量（源码仓 + 42×~110MB jar 若也放 Generic Packages 则数 GB 级）在询问清单里带尺寸问题 |
| 私有仓限制 | kaiyu/nebflow 现为私有且运行正常（dev 日常 push 实证）——私有仓本身无阻碍；校外**网页/API** 访问要登录态/令牌（§2.1 新政），git 走 SSH 不受限 |
| 服务条款风险 | 无公开条款文档——**需站方确认**：商用/个人产品发行物经校园基础设施分发的合规性（jar 主面在 OSS，GitLab 侧只是代码与归档，风险敞口本已很小）；账号资格（作者有统一身份认证 ✓；**外部贡献者没有**——PR 流断点，见 §3.1） |
| CI 分钟 | shared runner 若站方有配额——需站方确认；**自建 runner 分钟无配额**（自管语义），方案不依赖 shared runner 即免疫此项 |

## 3. 迁移方案草案（推荐路线）

**推荐路线一句话**：git 主体已迁（现状即准源），CI 走**自建 runner 三机布局**（Windows 重活 DESKTOP-IJBBEOT + Windows 腿 KAI + macOS 构建本机 Mac），发版走 **GitLab Release + Generic Packages 归档、OSS 用户下载主面不变**，过渡期 **push mirror 单向 USTC→GitHub** 保 GitHub 面不过期；pull mirror 缺位不影响。

### 3.1 会断什么 / 保留什么

**会断：**
1. **外部贡献者 PR 流**（统一身份认证门槛，校外合作者需邮件申请）——GitHub 48 PR 的协作史不可能平移；接受此项是迁的前置决策。
2. `generate_release_notes` 自动分类 notes——换脚本生成。
3. Actions marketplace 生态（setup-java/checkout/softprops 等）——全部换成手写 step。
4. GitHub 自托管 runner pilot（ci-selfhosted.yml + Mac `~/actions-runner`）——被 GitLab runner 取代，退役。
5. badge 与 README Releases 链接指向（改 USTC release 页或 OSS）。

**保留：**
1. **OSS 发布主链原样不动**（install.sh/ps1、latest-version.txt、deps/ 镜像表、mystand.space 入口）——用户侧零感知。
2. main/beta/release 三支模型原样（分支名不变，仅触发器从 Actions 换 GitLab pipeline）。
3. 全部门禁脚本（scripts/check-*.mjs/sh 与平台无关，逐 job 平移即用）。
4. seed 插件 GitHub 公开仓（独立面，不随动）。
5. GitHub 仓本身：push mirror 保持只读镜像 + 42 个历史 Release 存档（或批量搬 GitLab Release 后归档）。

### 3.2 步骤清单（阶段化，每步可回退）

**阶段 0 前置（半天）**
- [ ] 作者网页登录 USTC：确认实例版本（决定 runner 注册流程形态）、项目是否挂了 shared runner、站方 pull mirror license、配额（§4 清单一并问）。
- [ ] 签发 project access token（scope: api + write_repository）→ 备 CI/CD Variables；签发个人 PAT 备运维。
- [ ] OSS key 两枚迁入 GitLab CI/CD Variables（masked）。

**阶段 1 runner 落地（1 天）**
- [ ] DESKTOP-IJBBEOT：装 gitlab-runner（Windows 服务）→ 注册 `glrt-` token → labels `[self-hosted, Windows, X64]` → 装 JDK21/rg（工具链 2026-10-21 批已备大半）。
- [ ] KAI：同法注册（按实机架构给标签）——USTC 直连已实测通，GitHub 时代不可用机直接复用。
- [ ] Mac 本机：`brew install gitlab-runner` → 注册 labels `[self-hosted, macOS, ARM64]` → 专用用户或 docker executor 隔离密钥库风险。
- [ ] pilot：`.gitlab-ci.yml` 最小 pilot（assembly+sha256，等价 ci-selfhosted.yml）跑绿三机。

**阶段 2 CI 平移（2 天，工作量主体）**
- [ ] ci.yml → `.gitlab-ci.yml`：10 个 job 按表平移（stages: gates→lint→compile→test→assembly→smoke→frontend→docker）；matrix 腿 → 按标签拆 job；artifact 传递 → `artifacts:`；checkout → `GIT_DEPTH: 0`（release 面 fetch-depth:0 语义）；setup-java → 直用宿主 JDK21（钉基线）+ Aliyun sbt-launch 自举（既有脚本照搬）。
- [ ] release.yml / auto-release.yml → 两条 release pipeline：tag 步（CI_JOB_TOKEN push）+ `release:` 关键字 + ossutil 上传步（原脚本照搬）+ VERSION 回写 main 改 commits API + deps 断言步原样。
- [ ] release notes 生成脚本（git log 分组，半小时件）。
- [ ] protected branches/tags 设定：main/beta/release + 无 v tag 模式。

**阶段 3 切换与过渡（半天）**
- [ ] USTC push mirror → GitHub（Settings→Repository→Mirroring，credentials 用 GitHub PAT）——GitHub 面进入只读跟随。
- [ ] **追平 origin**：`git push origin dev/main:main` + tags（一次性，解决 399 落后；**先追平再开 mirror**，防 diverge）。
- [ ] README badge/Releases 链接改指 GitLab（或 OSS）；install.sh/ps1 零改动。
- [ ] 观察 1–2 个 beta 发布周期全链（beta push→pipeline→tag→Release→OSS→断言→安装冒烟），稳后 GitHub 仓转 archived（或保留 mirror 长期只读）。

**不迁/退役**：scratch 验证仓归档；Mac `~/actions-runner`（GitHub 自托管）退役；ci-selfhosted.yml 随 GitHub 面冻结。

### 3.3 工作量估计

| 阶段 | 净工时 | 风险点 |
|---|---|---|
| 0 前置 | 0.5d | 站方响应速度（token/license/配额问询） |
| 1 runner | 1d | Windows 服务装制需 admin 一次（既有操作卡可复用） |
| 2 CI 平移 | 2–2.5d | 三方 action 替换的等价性验证；windows 腿 bash 步骤在 GitLab shell executor 的方言差异 |
| 3 切换 | 0.5d | push mirror credential；追平 origin 的时点选择 |
| **合计** | **4–4.5 净工作日**（日历约 1 周，含等待站方） | |

### 3.4 GitHub origin 落后 399 的表态

**必须先追平再谈退役。** 方向不是把 GitHub 追回来，而是把 USTC 推过去：准源已是 dev/main（领先 399），本地 main 与 dev 同步中（差 9 个本周合并）。一次 `git push origin dev/main:main --tags`（阶段 3 首步）即消除分叉；此后 push mirror 自动维持。**在追平之前，任何「GitHub 侧最后状态」的假设都不成立**——这也是双远端现状的最大现实风险（在 GitHub 侧看代码/发 issue，看到的都是 10-21 时代的世界）。

## 4. 需向站方确认清单（gitlab@ustc.edu.cn / 登录后自查）

1. 实例 GitLab 版本与 license 层级（决定 runner 注册流程形态 + pull mirror 是否可用）。
2. 个人项目是否可挂 shared runner、有无分钟/并发限制。
3. 仓与 package/artifact 存储配额（stand: 源码仓 + 预计数 GB 级 jar 归档）。
4. 服务条款：个人软件项目的 CI/发版使用边界；Release/Generic Packages 分发的合规口径。
5. 服务可用性承诺（有无维护窗口公告渠道）。

## 5. 来源与实测存证

- **实测（2026-11-01）**：`ssh -T git@git.ustc.edu.cn` → Welcome @kaiyu；`git fetch origin/dev` + rev-list 计数（origin/main behind dev/main = 399）；curl 匿名 API 四探（401/200/404 见 §2.1）；三机连通性（Mac ✓ / KAI: ustc 200·github 000 / DESKTOP: ustc 200·github 200@23s）；本机 docker 29.1.3、JDK23、sbt 1.10.10；gh api：releases 42、runs 近 3 红、pages 404、`"private": false`。
- **官方文档**：Generic Packages（Free/Self-Managed，docs.gitlab.com/user/packages/generic_packages）；Repository mirroring（Push=Free / Pull=Premium，docs.gitlab.com/user/project/repository/mirror）。
- **站方公告**（搜索快照）：校外访问 9-25 新政、统一身份认证、SSH 校内外可用、校外合作者邮件申请。
- **仓内前史**：reports/20261021_230000_ci-selfhosted-selection__delegate-nebflow-4e75e4bc.md（自托管选型与三机底册）；ci.yml 内 2026-10-24 批注释（托管实测数据、Artifact 配额耗尽存证）。
- **零配置声明**：本批未建 runner、未改 remote、未动 workflows、未签发任何 token；唯一仓库操作 = git fetch（只读）与 docs 文件落盘。
