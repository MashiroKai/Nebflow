# 两小件批 · 凭据脱敏键表 + 自己头像字节缓存 + 夹具令牌门禁（2026-11-04 21:30 CST）

三件各自一个子目录，**改前 / 改后原始输出**都在里面（sbt 全量日志在本目录根部，子目录里是摘录）。
证据纪律：**任何文件都不含真实凭据值** —— 门禁与摘录只报 `键名 / 长度 / 字符类数`。

## 件一 · 脱敏键表补齐并收归单一真源 → `item1-redact/`

| 读数 | 改前（HEAD=d21aa81ec） | 改后 |
|---|---|---|
| `deviceToken` 文本腿 | 值原样回显（红） | 值被掩码（`raw-visible=false masked-visible=true`） |
| 覆盖 8 个键（文本腿 / 隧道腿 / CLI 谓词 / 卡面谓词） | 4 个 spec 组 7 条断言全红 | 全绿 |
| 普通日志（618 字符，含 `maxTokens` / `budgetTokens` / 已掩码头 / logger 名 `…credential:`） | —— | **`diff=0`**（逐字不变） |
| 配额键 `maxTokens=4096` | —— | 不掩码（`diff=0`） |

- `before-key-tables.log`：改前四份各自的键表原文（`git show HEAD:…`）。
- `red-summary.log` / `red-sbt.log`（根目录）：改前红读数 —— `Failed: Total 13, Failed 8`。
- `green-summary.log` / `green3-final-sbt.log`（根目录）：改后绿读数 —— `Passed: Total 114, Failed 0`，
  其中 `[item1] fake-deviceToken masked: raw-visible=false masked-visible=true len 111 -> 81`、
  `[item1] ordinary-log diff=0 chars=618` 是两条验收读数的机器输出。
- 单一真源 = `src/main/scala/nebflow/shared/SecretKeys.scala`；改前有四份实现、改后四处**全部委托**：
  `core/LlmLogWriter.redactSecrets` · `neblink/RelayTunnelDiagnostics.redact` ·
  `cli/ConfigCommand.isSecretKey` · `core/McpToolGate.GateRedact.isSecretKey`。

## 件二 · `/api/neblink/avatar`（自己的头像）字节缓存 → `item2-avatar-cache/`

| 读数 | 改前 | 改后 |
|---|---|---|
| 两次请求的上游命中数 | **2**（每次都回源；`assertEquals(hits, 2)` 在 HEAD 成立，见 red summary 里那条**通过**的失败腿） | **1**（`assertEquals(hits, 1)` 通过） |
| `X-Avatar-Cache` | 无此头（实读 `None`） | 第一次 `miss`、第二次 `hit` |
| 命中后字节 / `Content-Type` | —— | 逐字节一致（`image/jpeg`） |
| 上游失败 | 502（不入缓存） | 502（不入缓存，第二次仍回源 ⇒ `hits=2`） |

- `before-route-code.log`：HEAD 路由体（`AvatarProxy.fetch` 直接在请求路径上，无缓存判定）。
- `after-route-code.log`：改后路由体（`AvatarByteCache.shared` 优先 + 自证头）。
- `red-summary.log`：改前红（无 `X-Avatar-Cache`）；`green-summary.log`：改后绿（两条腿全过）。

## 件三 · 高熵长令牌字面量门禁 → `item3-fixture-gate/`

| 读数 | 结果 |
|---|---|
| ① 反向验红：`tests/fixtures/` 种一条假长令牌（`deviceToken` 48 字符 + `apiKey` 43 字符） | `EXIT=1`，命中 2 处并点名行号（值不回显） |
| ② 现状全绿（种植件删除后 740 文件） | `EXIT=0`，`no high-entropy long token literals` |
| ③ 门禁对**提交态** HEAD 的回读（导出 HEAD 夹具到临时目录） | `EXIT=1` —— 抓到 **2 处真命中**：`tool-card-leak-on-reload/fixture{,-legacy}.json:123  key=deviceToken  len=43` |
| ④ 作者点名的「不该误报」样本（`msg-token-chip` / `provider-test-modal` / 夹具 13–21 字符占位） | 6 处敏感键字面量，**全绿**（`len<32` / `classes<3` / 占位词） |

- `planted-red/planted-red.log`、`gate-green.log`、`real-hit-repro/real-hit-repro.log`、
  `cited-placeholders/cited-placeholders.log`。
- 🔴 ③ 的两处**不是占位**：43 字符 base64url 形态的 `deviceToken`，来自真实会话转录
  （`85535b139` 落库）。本批已把两处替换为占位词
  `placeholder-device-token-rotated-20261104`（`sanitized-record.log` 记录改动前后长度，
  不含值）。**该值仍留在 git 历史里 ⇒ 按「已暴露」处置：轮换该设备的 deviceToken
  （重登一次即换发）**。
- 门禁脚本：`scripts/check-fixture-secrets.mjs`，已挂进 `make frontend-gates`
  （`FRONTEND_GATES := design-tokens msgstyle self-explaining circular fixture-secrets`）。

## 门禁读数 → `gates/`

| 门禁 | 读数 |
|---|---|
| `make frontend-gates` | `EXIT=0`（含新腿 `fixture-secrets`：740 文件 0 命中） |
| `node scripts/check-js-types.mjs` | `EXIT=0` —— `checkJs: 0 errors (baseline 0, zero-gate files: 3)` |
| `node scripts/check-legacy-domain.mjs` | `EXIT=0`（**改前为红**，红因是别的批次证据日志引用了旧版门禁文案；处置见 `check-legacy-domain.log` + `check-legacy-domain-after.log` 与批报告） |

环境注记：`check-js-types` 需要 `node_modules/typescript`（pinned 5.5.4），worktree 内以符号链接借用主仓
`node_modules`（git 忽略，不入提交）。`sbt scalafmtCheckAll` 在本 worktree 上**改前即红**
（`nebflow/perception/PerceptionService.scala:1211`，本批未触碰该文件，见 `scalafmt-check.log`）；
本批新增的 6 个 Scala 文件已用 `scalafmtOnly` 过格式器（`scalafmt-only-new-files.log`）。

### 提交后复跑（`gates/post-commit-gates.log`，commit `374f1c800`）

`make frontend-gates` / `check-js-types` / `check-legacy-domain` 三条**全部 EXIT=0**，
其中 `fixture-secrets` 扫 760 文件 0 命中、`checkJs: 0 errors (baseline 0)`、
`legacy-domain: 0 bare retired-domain literal(s) outside the whitelist`。
