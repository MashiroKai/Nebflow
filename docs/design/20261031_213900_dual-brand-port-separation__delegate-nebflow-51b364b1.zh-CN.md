---
type: design-note
status: done
---

# 双生实例分家：品牌感知缺省口 + 三元组守卫 + config `port` 键（2026-10-31 门面级修复）

日期：2026-10-31 · 作者令 20:57（「KAI 机器上 nebflow 和 stand 要分开启动，端口和
home 本就不一样；现状撞车」）+ 任务裁定：判定键 =「品牌+自己的端口+自己的 home」
三元组、绝不跨品牌 focus；给 nebflow 独立默认端口（如 6140）；config 支持显式
`port` 键并写进文档。
状态：已实现（引擎 + CLI + 文档 + spec）；KAI 侧启用步骤见
[[kai-stand-migration]]（runbook）。

相关件：[[20261027_cli-isolation-and-detached-restart-spec.zh-CN]]（10-30 双实例并存
批的上游设计，本文的端口判据换代取代其 8080 选择）、[[kai-dispatch-route]]、
[[glossary]]。

## 1. 事故链（KAI，2026-10-31 实测）

1. KAI 的 stand 实例占 6138（`C:\Users\Kai\.stand`，pid 14528）。
2. KAI 的 nebflow 产品 = `nebflow-assembly-2026.10.26-beta.1.jar` —— **10-25 端口
   对调之后的构建**：彼时引擎代码默认口只有全局一个 `Defaults.GatewayPort`（6138），
   不分品牌 ⇒ nebflow 裸启动同样落 6138。
3. 用户跑 `nebflow`：守卫探 6138，健康面 `product=stand`；旧判据（10-18 rename
   兼容 + 10-30 路径证据分层）在无路径证据时把异品牌词当自家人 ⇒ focus 对家实例，
   报「stand is already running (same port)」然后打开 stand 的门。
   另一条同等病根：弃用 shim `C:\Users\Kai\AppData\Local\stand\nebflow.cmd` 转发
   stand.cmd（跨品牌拉起总根），用户会话 PATH 命中 shim 时症状逐字复现。

## 2. 考古结论（作者申报项）

- 引擎默认口演变：全引擎唯一主默认 8080（创立起）→ 2026-10-23 作者令、10-25
  落地对调为 6138（8080 让渡 9.19 自有根实例；动机链 = 微信 side-car 投递
  tcp_refused 事故）。
- **nebflow 从无前例的独立默认口**：8080 年代它是全引擎唯一主默认（并非独立
  默认）；6138 年代它跟着新主默认撞车。10-30 双实例并存批曾把 nebflow 命令缺省
  选为 8080——那是「9.19 自有根实例的口」的沿用，不是独立默认，且与「两个产品
  各自独立」的裁定不符。**6140 是 nebflow 的第一个真正独立默认**（本批设立）。
- 8080 自此只属 9.19 自有根实例（tag 2026.9.19 的 rebrand 前末版 nebflow 品牌
  release），经 `NEBFLOW_GATEWAY_PORT=8080` / `--port 8080` 管辖；任何品牌引擎
  都不绑定 8080（ProcessManager ProtectedPorts 的 8080/8097 dev-host 硬闸不动）。

## 3. 裁定与改动面

### 3.1 品牌感知缺省口（引擎单源）

`Defaults.brandDefaultPortFor(lowerName)`：纯函数单点 —— `nebflow` → 6140
（`Defaults.NebflowBrandGatewayPort`），其余（含 `stand` 与未知品牌）→ 6138
（`Defaults.GatewayPort`，语义退居「stand/原生默认」）。`Defaults.brandDefaultPort`
= 本构建现读（brand.conf lowerName）。`LegacyGatewayPort = 8080` 常量退役删除。

消费面统一换源（「本引擎默认网关口」语义一律引用 `brandDefaultPort`，不再直接引
`GatewayPort`）：`GatewayConfig.DefaultPort`（绑定兜底）、`GatewayClient.readPort`
（CLI）、doctor（`SystemCommands`）、`ProcessManager` 回落、`Rollback` 复起缺省、
`HookRunner` / `FlowTreeActor` / `NeblinkService` / `PromptSections` /
`RestApiRoutes` / `NeblinkRoutes` / `PresenceRoutes` 构造兜底。

### 3.2 端口解析链（env > config > 品牌默认）

`GatewayConfig.resolvePort` 纯核心（spec 驱全矩阵）：`--port` ＞ `GATEWAY_PORT`
env（品牌双前缀）＞ config `port` 键 ＞ 品牌缺省口；任一腿非法（非数字/越界/**零**——
ip4s `Port.fromInt(0)` 合法但绑 0 = 随机临时口，网关将不可达，统一拒绝）跳过。
`GatewayClient.readPort` 同链同序。config 腿 = `GatewayConfig.configFilePort()`：
读 `<dataRoot>/nebflow.json` 顶层**JSON 数字** `port`（数字字符串不认——circe 宽容
解码被显式 `asNumber` 闸掉）；文件缺失/空/非法一律 None **绝不抛错**（启动闸第一道，
配置故障由后续 `Config.loadServiceConfig` 大声报）。

### 3.3 单实例守卫三元组（绝不跨品牌 focus）

`SingleInstanceGuard.probeOnce` 判据换代：
- 健康面 `product` 是已知品牌（`KnownBrands = {stand, nebflow}`）且 ≠ 本构建品牌
  ⇒ `OtherInstance`（拒启 + 点名），**零路径证据要求**。10-18 rename 兼容腿（「无
  路径证据的 nebflow 词当自家人 focus」）退役：双产品世界里品牌词即身份。
- 同品牌词 ⇒ 自家可 focus，但仍过路径证据兜底腿：listener 命令行落在另一个品牌
  根下（极老构建无品牌词 / 混装 shim 残留）⇒ 升级为 `OtherInstance`。三元组的
  「家」腿在此闭合；「端口」腿恒成立（被探口即冲突口）。
- 引擎侧 `GatewayMain.ensureSingleInstance` 的对家根红线（绝不 kill 对家）10-30
  已立，本批不动。
- 退役成员：`OtherBrandBrandName` 删除；`OtherBrands` 改为 `KnownBrands − OwnBrand`。

### 3.4 CLI 与文档同换代

- `ops/bin/stand-cli-lib.sh`：BRAND-MAP / `stand_resolve_port` / 头契约表
  nebflow 缺省 8080 → **6140**；`ops/bin/nebflow` 头契约同步（标注 9.19 实例经
  env 管辖 8080）；弃用 shim 注释标注「应删除」。
- `ops/README.md`、`REBRAND.md`「双命令并存」节、
  `docs/design/20261027_cli-isolation-...` 端口表、`imagegen-dual-layer-spec` 的
  MCP 端口链 + `plugins/imagegen/scripts/imagegen_mcp_server.py` 缺省（8080 → 6138，
  注明 nebflow 品牌构建 6140）。
- 拒启文案补 config 腿：「pick another port (--port / GATEWAY_PORT / config 'port' key)」。

### 3.5 Spec 钉

- `GatewayPortDefaultSpec`：品牌缺省口矩阵 / 两默认值差开且合法 / 解析链全序 /
  非法腿跳过 / configFilePort 容忍面（临时 dataRoot）。
- `CliBrandDefaultsSpec`：`NebflowBrandGatewayPort=6140` / `legacyPortOf` / CLI
  BRAND-MAP 文本对账 6140。
- `SingleInstanceGuardSpec`：跨品牌零路径证据 ⇒ OtherInstance（红线，两个用例）；
  同品牌 + legacy 形态 ⇒ focus；KnownBrands 钉。
- `GatewayClientPortSpec`：CLI 链 config 腿 + 临时 dataRoot 兜底档。
- `ops/test/stand-lifecycle.test.sh` case 26：`nebflow→~/.nebflow+6140`。

## 4. 行为变化清单（如实申报）

- nebflow 品牌构建的引擎缺省绑定口 6138 → **6140**（显式配过 `--port`/
  env/config 的实例不受影响——三条显式腿优先级都高于品牌默认）。
- 守卫：健康面异品牌词不再 focus（10-30 行为：无路径证据时 focus）⇒ 改拒启点名。
  影响面 = 两个产品显式同配一个口的边角场景；正常双产品（口已错开）零遭遇。
- nebflow CLI（shell）缺省口 8080 → **6140**：裸 `nebflow stop`/`status` 等不再默
  认探 8080；9.19 自有根实例的管辖需显式 `NEBFLOW_GATEWAY_PORT=8080`（或 `--port`）。
- 老构建（健康面无 `product` 字段）的 focus 兼容保留，且新增路径证据升级腿。
