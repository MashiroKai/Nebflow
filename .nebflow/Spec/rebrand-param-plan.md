> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Nebflow → NebLink 更名前期准备：全量盘点 + 参数化改造方案

> 状态：待确认 ｜ 日期：2026-08-17 ｜ 范围：纯分析 + 方案，不改代码
> 目标：**现在建参数化基础设施**，让后期改名接近"一键"；本方案不含实际改名动作

---

## 一、盘点结果：引用分布统计

### 1.1 总览图

![引用分布](assets/rebrand_inventory.svg)

### 1.2 分类计数表（按仓库 × 类别）

**主仓 `/Users/dev/Claude code/Nebflow`（archive/scala 分支，git 跟踪文件 625 个）**

| 类别 | 匹配行数/文件数 | 代表性示例（文件:行号） |
|---|---|---|
| Scala 包名 | 328 个文件声明 `package nebflow.*` | `src/main/scala/nebflow/core/paths.scala:1`；顶层包 `nebflow.Main` |
| Scala import | 513 处 `import nebflow.*` | 全仓遍布 |
| Scala 字符串字面量 | ~400 处 | `"nebflow.json"`×24、`"nebflow.agent"`×21、`"/opt/nebflow/Nebflow.jar"`×4、`"nebflow-project"`×12、`"nebflow-rust"`×5 |
| 数据目录 `~/.nebflow` | 源码 9 处 + 测试 7 文件 | `paths.scala:113` `os.home / ".nebflow"`；`install.sh:151,365` |
| 环境变量 `NEBFLOW_*` | 9 个变量 45 处 | `NEBFLOW_HOME`(11)、`NEBFLOW_HEADLESS`(12)、`NEBFLOW_TOKEN`(9)、`NEBFLOW_GATEWAY_PORT`(5)、`NEBFLOW_URL`(4)、`NEBFLOW_VERSION/TELEMETRY/PID/GATEWAY_HOST`(各1) |
| 前端 web 资源 | 118 处 / 37 文件 | `web/index.html:5` `<title>nebflow</title>`；js 文案 `"Nebflow"`×10 |
| 前端 localStorage 键 | 11 个键 | `nebflow_token`(使用 15 次)、`nebflow_pinned`、`nebflow_unread`、`nebflow_model_info`、`nebflow_input_history` 等 |
| 构建/CI/发布 | 159 处 | `build.sbt` `name := "nebflow"`、`organization := "nebflow"`；CI `-Dnebflow.webdist=1`；jar 名 `nebflow-assembly-*.jar` |
| 打包 | packaging/ 17 处 | `build-dmg.sh:62` `--name Nebflow`、`:66 --main-class nebflow.Main`、`:72 --mac-package-name Nebflow`；`build-msi.sh:62-76` 同构 |
| COS 更新源 | 桶 `nebflow-releases-1411212853` | `release.yml:93`、`auto-release.yml:199` coscmd 上传；`install.ps1:20-61`、`install.sh` 下载（两个 endpoint：ap-nanjing + accelerate） |
| 安装命令 URL | **4 处重复硬编码** | `WebSocketRoutes.scala:2538-2541`、`cli/SystemCommands.scala:60-63,110-111`、`neblink/RemoteUpdateAction.scala:23-26`（`https://nebflow.space/install.sh`） |
| 文档 | 84 处 | README 33、CODEBASE.md 25、LICENSE |
| neblink 子系统 | 686 处 / 43 文件 | `X-Neblink-Device` header（`RemoteExecutor.scala:369`）；`/api/device/{code,token,enroll,session,login}`；默认服务器 `https://neblink.nebflow.space`（`RestApiRoutes.scala:2287-2289`） |

**官网 `~/.nebflow/projects/nebflow-website`**

| 类别 | 数量 | 示例 |
|---|---|---|
| nebflow 引用 | 264 处 / 47 文件 | `lib/config.ts` `SITE{name:"nebflow", domain:"nebflow.space"}`（**已是单源，仅需改值**）；landing 组件、docs mdx、`public/install*.sh/ps1` |
| neblink 引用 | 42 处 / 9 文件 | `app/api/profile/route.ts`、`app/api/profile/devices/[deviceId]/update/route.ts` 各自硬编码 `NEBLINK_URL = "https://neblink.nebflow.space"` |

**neblink-server `~/.nebflow/projects/neblink-server`（独立 Rust 仓）**

| 类别 | 数量 | 示例 |
|---|---|---|
| neblink 引用 | 102 处 / 16 文件 | `Cargo.toml` `name = "neblink-server"`；`Caddyfile:11` `neblink.nebflow.space`；`docker-compose.yml` `NEBLINK_DB_PATH/NEBLINK_SERVER_PORT`；CORS 白名单（`src/main.rs`）`nebflow.space` + `neblink.nebflow.space` |
| nebflow 引用 | 54 处 / 13 文件 | `.env.example` `OAUTH_REDIRECT_URL=https://neblink.nebflow.space/api/auth/github/callback`、`APP_PUBLIC_URL`；静态页文案引导用户写 `~/.nebflow/neblink/config.json` |

**全局 `~/.nebflow/`（运行时数据目录本身 + 脚本/配置）**

| 类别 | 数量 | 示例 |
|---|---|---|
| 可执行/脚本 | `bin/nebflow`、`bin/nebflow-restart.sh` | wrapper 指向 jar |
| 主配置 | `nebflow.json`（+`.bak`）、`NEBFLOW.md` | LLM/MCP 配置文件名即品牌 |
| teams | 3 个品牌名团队目录 | `nebflow-project`、`nebflow-website`、`nebflow-rust`（team.json + rules.md + agents/） |
| skills | `skills/nebflow/visual-style` | **被主仓 Scala 引用 `"nebflow/visual-style"`×8——skill 目录名是协议的一部分** |
| flows | `flows/nebflow-review-merge` | flow 定义目录 |
| agents | ~8 个 system.md 提及 Nebflow | Nebula/Explorer 等系统提示词 |
| neblink 运行时 | `~/.nebflow/neblink/{config,device,peers}.json`、`client-id`、`device.json` | 设备身份（`client-id` UUID）——改名时必须保留 |
| daemons.json | cwd 路径含 `~/.nebflow/projects/*` | 路径随数据目录迁移 |

**外部资源（不可 grep，来自 NEBLINK_HANDOVER.md 与 CI 配置）**

| 资源 | 当前值 | 迁移敏感度 |
|---|---|---|
| GitHub org/repo | `MashiroKai/Nebflow`（install 脚本、README、CI、badge） | GitHub transfer 后旧 URL 自动重定向 |
| GitHub OAuth App | Client ID `Ov23liu3nC6jzBmNIQNB`，回调 `https://neblink.nebflow.space/api/auth/github/callback` | 改回调 = 老 JWT/device token 失效 |
| COS 桶 | `nebflow-releases-1411212853`（南京 + 全球加速双 endpoint，`latest-version.txt`/`latest-beta-version.txt`） | 老安装命令依赖旧桶存活 |
| 域名/DNS | `nebflow.space`（阿里云 DNS）；`neblink.nebflow.space` A → VPS `203.0.113.10` | 旧域名需保留 301 |
| VPS 部署 | Vultr Debian，docker compose（`neblink-server` + `neblink-caddy` 容器），`/root/neblink-server/` | Caddy 换域名触发重新签证书 |
| KAI | 源码注释中的个人路径（`C:\Users\Kai`），非品牌 | 无需处理 |
| 未跟踪目录 | `nebflow-rs/`、`typescript/`（本地开发中，未入 git）；`rust-standalone` 分支 | Rust 重写线同样要吃这套 Branding |

**合计**：主仓 2154 行 nebflow + 686 行 neblink；官网 264 + 42；neblink-server 54 + 102；全局配置 ~31 文件。改名涉及面 ≈ **3200+ 处引用、5 个代码库/部署面、7 类外部资源**。

---

## 二、单源品牌配置设计（Branding）

![Branding 单源架构](assets/rebrand_branding.svg)

### 2.1 单源文件：`brand.conf`（仓库根目录，git 跟踪）

```ini
# —— 唯一品牌事实源。改名 = 改这个文件 + 跑 scripts/rebrand.sh ——
productName    = NebLink          # 展示名（UI、dmg/msi、README）
lowerName      = neblink          # 小写（jar 名、二进制名、包名目标）
domain         = neblink.space    # 主域名（官网、install URL）
githubOrg      = NebLink-Org      # 新 GitHub org（占位，待确认）
githubRepo     = NebLink
homeDirName    = .neblink         # 数据目录名（L3，带迁移）
envPrefix      = NEBLINK          # 环境变量前缀（L3，带双读）
cosBucket      = neblink-releases # COS 桶名（占位）
subsystemName  = NebLink          # 设备互联子系统名（见第五节，待确认）
```

### 2.2 各端读取落点

| 端 | 落点 | 机制 |
|---|---|---|
| **sbt** | 新增 `project/Branding.scala` | sbt 启动时解析 `brand.conf` → `name := brand.productName`、`organization := brand.lowerName`、assembly jar 名、`-D{lower}.webdist=1`。**这是唯一让 jar 名跟随品牌的地方** |
| **CI** | 三个 workflow 开头加一步 `brand: read brand.conf → $GITHUB_ENV` | artifact 名、coscmd `-b`、路径 glob 全部改用 env 变量 |
| **打包** | `packaging/build-{dmg,msi}.sh` 开头 `source brand.conf` 或读同文件 | `--name $productName --main-class {lowerName}.Main` |
| **安装脚本** | `release/install.{sh,ps1}` 改为**构建时从模板渲染**（CI release job 渲染后上传 COS/官网） | COS URL、GitHub URL、wrapper 名 `${HOME}/.local/bin/{lowerName}`、配置目录 |
| **Scala 运行时** | 新增 `src/main/scala/nebflow/core/Branding.scala` object；`brand.conf` 通过 sbt 打进 JAR resources，启动时读取 | 所有字符串字面量（serverUrl 默认值、install 命令 4 处重复、telemetry UA、`"nebflow.json"`）改为引用 `Branding.*` |
| **前端** | gateway 渲染 `index.html` 时注入 `<script>window.__BRAND__={...}</script>`（HTML 是模板而非静态文件，改动点在 gateway 静态资源服务处） | js 内 10 处 `"Nebflow"` 文案、`<title>`、`document.title` 走 `window.__BRAND__.productName` |
| **前端存储键** | 新增 `web/js/branding.js`：`key(k) => \`${__BRAND__.lowerName}_${k}\`` + 启动时旧键迁移 | 11 个 `nebflow_*` localStorage 键收口 |
| **官网** | `lib/config.ts` 的 `SITE` **已是单源**——补 `NEBLINK_URL` 也进 SITE；安装脚本与主仓共用同一渲染源 | 改 1 个常量 |
| **neblink-server** | Rust 侧新增 `brand` 常量模块（或编译期 env）——域名、CORS、静态页文案 | 独立仓，手动同步（checklist 项） |

### 2.3 关键设计决策

1. **Scala 包名不在参数化阶段改**。包名是编译期标识符，无法用配置字符串参数化；参数化阶段保持 `package nebflow.*`，实际改名时由脚本机械重命名（见 L2）。`Branding` object 先落在 `nebflow.core` 下。
2. **`brand.conf` 同时进 JAR**——运行时 Branding 与构建产物读同一文件，避免两处漂移。
3. **skill 目录名 `"nebflow/visual-style"` 是 Scala 字符串引用的路径**，属运行时协议：目录改名的迁移逻辑放 L3（找不到新目录时回落旧目录）。

---

## 三、分层改造策略

![分层风险](assets/rebrand_layers.svg)

### L1 展示/文案层（低风险，纯参数化）

**内容**：web UI 文案 118 处、`<title>`、README 33 处、CODEBASE、telemetry UA、官网 SITE。
**参数化动作链**：注入 `window.__BRAND__` → js 文案替换为常量引用 → README/UA 走 `Branding` → 官网 SITE 补全字段。
**雷区**：几乎无。唯一注意 favicon/logo.svg 是品牌资产——参数化只能做到文件名引用集中，图标本体要人工出新品（checklist）。

### L2 构建标识层（中成本，机械替换）

**内容**：包名 328 文件、build.sbt、jar 名、jpackage、CI、Makefile、Dockerfile、install 脚本 wrapper 名。
**参数化动作链**：`project/Branding.scala` → CI 读 conf → packaging source conf → install 模板化。
**改名时动作链**（脚本执行）：目录树 `src/main/scala/nebflow → src/main/scala/neblink` + `package/import` 行 sed → build.sbt 由 conf 驱动 → `sbt clean assembly test` 全绿 → dmg/msi 重打包 → 旧 `~/.local/bin/nebflow` 保留一个 shim 指向新二进制。
**雷区**：
- jar 名变化会断掉 `install.ps1:303` 的旧版清理逻辑与 Makefile 安装路径——脚本要同步替换；
- `-Dnebflow.webdist=1` 系统属性名跟着改，CI 与本地习惯命令都要过一遍；
- `CODEBASE.md`/文档里的命令示例（`nebflow start`）全部失效——README 属于 L1 但命令行示例属 L2，一起改。

### L3 运行时兼容层（高风险，必须带迁移逻辑）

| 雷区 | 现状 | 迁移设计 |
|---|---|---|
| **`~/.nebflow` 数据目录** | `paths.scala:109-114`：CLI flag > `NEBFLOW_HOME` > `os.home/.nebflow` | `PathUtil.dataRoot` 改为：新目录存在 → 用新；否则旧目录存在 → **双读期**（用旧 + 打印迁移提示）；首次以新名字启动时执行一次性迁移（copy 而非 move，保留回滚）+ 写 `.migrated` 标记 |
| **环境变量 ×9** | `NEBFLOW_HOME` 等 | `Branding.env(name)`：先查新前缀，miss 再查 `NEBFLOW_` 前缀，双读期 ≥2 个版本 |
| **`nebflow.json`** | 24 处字面量引用 | 文件名走 `Branding.configFileName`；读取时新名 miss → 回落 `nebflow.json`；首次写回新文件名 |
| **localStorage ×11 键** | `nebflow_token` 等 | `branding.js` 启动时：新键 miss 且旧键 hit → 读旧写新 + 删旧。`nebflow_token` 丢失 = 用户被登出，必须迁移 |
| **OAuth 回调** | `https://neblink.nebflow.space/...` | GitHub OAuth App 改回调 URL → server 签发的旧 JWT 的 aud/iss 校验失败 → 存量设备全部掉线。方案：VPS 同时挂新旧两个域名（Caddy 双 server block），旧回调保留到灰度期结束；或新建第二个 OAuth App 双客户端并存 |
| **协议字段** | `X-Neblink-Device` header、`/api/device/*` 路径、`neblinkServer` 配置字段 | header 与路径**恰好已是 neblink/中性命名**，本次改名**不动协议**（只改域名解析）。已有先例：`NeblinkModel.scala:153-158` 对 `coordinator → neblinkServer` 做过向后兼容降级——将来若动协议，复制这个模式 |
| **服务器端** | neblink-server CORS 白名单、JWT 校验 | CORS 加新域名（保留旧域名）；`.env` 域名字段改值 |
| **官网 API 代理** | `NEBLINK_URL` 硬编码 ×2 | 进 SITE；域名切换时 Next.js env var 化（`NEBLINK_SERVER_URL`），部署时注入 |
| **COS 桶** | 旧桶 = 存量用户 `install.sh`/自更新的下载源 | 新桶建好后旧桶**至少保留 12 个月**，内容与新桶同步双写（CI 上传两份）；`latest-beta-version.txt` 指向同一版本号，实现新旧客户端都能自更新到带迁移逻辑的版本 |

**L3 推进原则**：先发"双读版本"（新旧兼容）→ 等待用户自然升级（借 COS 版本文件推送）→ 再发"切换版本"（默认新、告警旧）→ 最后清理。

---

## 四、一键改名脚本设计：`scripts/rebrand.sh`

```
用法: scripts/rebrand.sh --dry-run | --apply [--skip-external]
输入: brand.conf (唯一品牌事实源)
```

**执行链（--apply）**：

1. **前置校验**（可 `--skip-external` 跳过，默认强制）：
   - `brand.conf` 必填字段完整、格式合法（防半改）
   - 新域名 DNS 已解析（`dig +short $domain`）
   - 新 GitHub org 可访问（`curl api.github.com/orgs/$org`）
   - 新 COS 桶已创建且可写
2. **L2 机械替换**：包目录树 rename → `package`/`import` 行替换 → 校验无残留（`rg -i 'nebflow' --glob '*.scala'` 输出为空，白名单注释除外）
3. **字面量收口验证**：`rg '"[^"]*nebflow[^"]*"' --glob '*.scala'` 应只剩 `Branding.scala` 兼容层（旧名回退常量）
4. **构建**：`sbt clean assembly` + `sbt test`（790+ 用例全绿）
5. **冒烟**（硬性，不可跳过）：
   - `java -jar dist/*-assembly-*.jar -s` 真实启动
   - `curl localhost:8080/api/health` 200
   - Playwright：打开页面 → 断言 `window.__BRAND__.productName` 注入、`#chat-input` 渲染、无 console error、WS 连接建立
   - **迁移冒烟**：预先准备旧格式 `~/.nebflow` 测试夹具（旧 config + 旧 localStorage 键）→ 启动 → 断言数据被双读/迁移、登录态保留
6. **打包**：`packaging/build-dmg.sh` + `build-msi.sh`，产物名含新品牌
7. **渲染安装脚本**：模板 + brand.conf → 新 `install.{sh,ps1}` → 人工上传 COS 新桶与官网 `public/`
8. **输出人工 checklist**（终端打印 + 写入 `REBRAND-CHECKLIST.md`）：

```
[ ] 阿里云 DNS:nebflow.space 301 → 新域名;新域名 A/CNAME 记录
[ ] VPS:Caddyfile 加新域名 server block(旧域名保留灰度期);docker compose up -d
[ ] GitHub:新建 org → Settings→Transfer repository(旧 URL 自动重定向)
[ ] GitHub OAuth App:新建/改回调 URL;Client Secret 更新到 VPS .env
[ ] COS:新桶开通;CI 双写开关打开
[ ] 官网:lib/config.ts 改值 → deploy;NEBLINK_SERVER_URL env 注入
[ ] neblink-server:CORS 白名单 + .env 域名;git remote 换新 org
[ ] 新 logo/favicon 三件套(favicon.svg/logo.svg/og.png)人工设计
[ ] 版本公告:README 更名说明 + 旧桶版本文件推送迁移版
```

**--dry-run**：只执行 2-3 步的 diff 输出（受影响文件清单 + 替换预览），不落盘——用于审阅改动面。

---

## 五、neblink 专项：子系统撞名决策

若产品名升格为 NebLink，"neblink"同时是设备互联子系统名（neblink-server、`neblink/` Scala 包、`~/.nebflow/neblink/` 目录、`X-Neblink-Device` header），产生语义挤压。两条路线：

| | 路线 A：子系统改名（如 DeviceLink） | 路线 B：子系统沿用 neblink |
|---|---|---|
| 语义 | 产品=NebLink，互联=DeviceLink，层次清晰 | "NebLink 的 link 功能就是 neb link"，自洽 |
| 协议影响 | `X-DeviceLink-Device` header 改名 → **存量已配对设备全部失联**，server 端需双读灰度 | 零影响 |
| 数据目录 | `~/.neblink/neblink/ → ~/.neblink/devicelink/` 多一层迁移 | 零迁移（目录恰好同名） |
| 仓库/域名 | neblink-server → devicelink-server；子域名 `device.新域名` 或保留 | 仓库可更名 `neblink-server → neblink-server`（不动）或迁 org |
| 参数化成本 | Branding 多 `subsystemName` 字段 + 协议常量集中化（现在散在 RemoteExecutor/NeblinkClient/RestApiRoutes 三处） | 仅需 Branding 收口 |
| 建议 | 长期更干净，但**不建议与产品改名同批做** | **推荐先走 B**，A 留作后续独立项目 |

无论 A/B：把 `X-Neblink-Device`、`/api/device/*`、`neblinkServer` 字段名收口到一个 `Protocol.scala` 常量对象——这是参数化本体的应做项，也是未来任何协议改名的前提。

---

## 六、工作量评估（参数化本体，不含实际改名）

| 批次 | 内容 | 交付物 | 预估 | 验收要点 |
|---|---|---|---|---|
| **批 1** | L1：`Branding.scala` + JAR 打包 conf + 前端 `window.__BRAND__` 注入 + web 文案/`<title>`/README/UA 收口 + 官网 SITE 补全 | 主仓 PR | ~1 天 | `rg -i nebflow src/main/resources/web` 仅剩 branding 相关；curl 首页含注入；改 conf 值→UI 文案随变（手动验证） |
| **批 2** | L2：`project/Branding.scala` + CI 读 conf + packaging/install 模板化 + Makefile/Dockerfile | 主仓 PR | ~1.5 天 | brand.conf 改 productName→`sbt assembly` jar 名随变（验证后还原）；CI 硬编码品牌清零（`rg -i nebflow .github/` 仅 conf 读取处） |
| **批 3** | L3：PathUtil 双读+迁移、env 前缀双读、config 双名、localStorage 迁移、`Protocol.scala` 收口、skill 目录回退 | 主仓 PR + 迁移单测 | ~2 天 | 旧 `~/.nebflow` 夹具下启动→数据/登录态保留；`NEBFLOW_HOME` 仍被识别；旧 localStorage 键迁移单测；冒烟链（真实启动+curl+Playwright） |
| **批 4** | `scripts/rebrand.sh`（dry-run/apply/验证链）+ `REBRAND.md` 人工 checklist + neblink-server Rust 侧品牌常量 | 主仓 + server 仓 | ~1 天 | `--dry-run` 输出完整替换清单不落盘；`--apply` 在临时 clone 上跑通全链路 |

合计 **~5.5 个工作日**，4 个批次按序派发 nebflow-project（批 1/2 可并行，批 3 依赖批 1，批 4 依赖全部）。

---

## 七、待确认参数（4 项）

| # | 问题 | 影响 |
|---|---|---|
| 1 | 新域名具体是什么（neblink.space?） | brand.conf `domain`、install URL、官网部署 |
| 2 | neblink 子系统路线 A/B | 第五节：是否多一批协议迁移 |
| 3 | 改名时 Scala 包名是否一并改（`package nebflow → neblink`） | L2 脚本范围；不改则包名永久保留旧名（内部细节，无用户可见影响） |
| 4 | 新 GitHub org 名称 | brand.conf、仓库 transfer、badge |

> 以上均为 brand.conf 的值——**参数化方案本身不依赖这些答案**，可先实施批 1-4，改名时再填值。
