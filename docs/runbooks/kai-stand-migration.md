---
type: runbook
status: active
---

# Runbook：KAI 双产品独立启动（双生实例分家批 · 作者令 2026-10-31 20:57）

- 批次：20261031 双生实例分家批（作者令 2026-10-31 20:57「KAI 机器上 nebflow 和
  stand 要分开启动，端口和 home 本就不一样；现状撞车」；端口判据换代，取代
  20261030 双实例并存批的 8080 选择）。
- 适用范围：KAI（`C:\Users\Kai`，Windows，引擎构建主线）本机 `stand` 与 `nebflow`
  两个产品实例的独立启动。属于**作者重启窗操作** —— 由作者在方便的时刻执行；
  本手册只固化动作与判据。
- 契约落点：`Defaults.brandDefaultPortFor`（引擎品牌感知缺省口单源）+
  `ops/bin/stand-cli-lib.sh` 头契约表 + `REBRAND.md`「双命令并存」节 +
  [[20261031_213900_dual-brand-port-separation__delegate-nebflow-51b364b1.zh-CN]]（本批设计件，含考古结论）。本手册零代码。

相关件：[[kai-dispatch-route]]（KAI 引擎派发路由——派发到 KAI 的 stand 6138 实例）、
[[glossary]]。

## 1. 现状（2026-10-31 实测读数）

- KAI stand 实例：数据根 `C:\Users\Kai\.stand`，网关口 **6138**（pid 14528）。
- KAI nebflow 产品：`C:\Users\Kai\AppData\Local\Nebflow\`，jar =
  `nebflow-assembly-2026.10.26-beta.1.jar`（**10-25 端口对调后构建**：引擎默认口只有
  全局 6138 一个 ⇒ nebflow 裸启动同样落 6138，与 stand 撞车）。9.19 老 jar 已
  `.bak-20261026` 封存。
- 事故症状：跑 `nebflow` 报「stand is already running (same port)」并 focus stand
  实例。两条病根：① 弃用 shim `C:\Users\Kai\AppData\Local\stand\nebflow.cmd` 转发
  stand.cmd（跨品牌拉起总根）；② 两产品引擎兜底口同默认 6138，守卫的旧判据
  「无路径证据的 nebflow 品牌词当自己人」会把对家实例 focus 掉。
- 两产品配置 `~/.nebflow/nebflow.json` / `~/.stand/nebflow.json` 均无 `port` 键
  （分家批起该键成为正式显式换口手段，解析序 --port > env > config > 品牌默认）。
- KAI 会话 env 常驻（作者面板注入，关机自清）：`STAND_HOME=C:\Users\Kai\.stand`、
  `NEBFLOW_HOME=C:\Users\Kai\.nebflow`、`NEBFLOW_GATEWAY_PORT=6138`、
  `STAND_GATEWAY_PORT=6138`。⚠️ `NEBFLOW_GATEWAY_PORT=6138` 会把 nebflow **显式
  钉回 6138**（env 腿优先级高于品牌默认）——分家后必须同步改为 `6140` 或移除。

## 2. 双品牌默认值契约（两机一致）

| 命令 | 数据根（无 env） | 默认端口（引擎品牌缺省） | 入口 |
|---|---|---|---|
| `stand` | `~/.stand`（KAI: `C:\Users\Kai\.stand`） | **6138** | `ops/bin/stand` → `~/.stand/bin/stand` |
| `nebflow` | `~/.nebflow`（KAI: `C:\Users\Kai\.nebflow`） | **6140** | `ops/bin/nebflow` → `~/.nebflow/bin/nebflow` |

两品牌**引擎兜底口错开**（`Defaults.brandDefaultPortFor`：stand=6138 / nebflow=6140），
所以双产品同机并存、互不干扰。考古：nebflow 从无前例的独立默认口——8080 年代它是
全引擎唯一主默认，6138 年代跟着新主默认撞车；6140 是第一个真正独立默认。8080 自此
只属 9.19 自有根实例（经 `NEBFLOW_GATEWAY_PORT=8080` / `--port 8080` 管辖）。

## 3. KAI 启用步骤（作者重启窗，四步）

> 重启 = **停引擎 → 换启动器/jar → 再启**。`~/.nebflow` 数据根**不动**。

1. **删弃用 shim**（根除跨品牌转发；PATH 中 `...\AppData\Local\Nebflow` 本就在
   `...\Local\stand` 之前，删后 `nebflow` 必中真 launcher）：
   ```bat
   del "C:\Users\Kai\AppData\Local\stand\nebflow.cmd"
   ```

2. **部署新 nebflow 品牌构建**到 `C:\Users\Kai\AppData\Local\Nebflow\`：
   - 放入新 `nebflow-assembly-<VERSION>.jar`（含分家批：品牌缺省口 6140）；
   - **移走旧 jar**（`nebflow-assembly-2026.10.26-beta.1.jar`——该 launcher 的
     `for %%f in (...nebflow-assembly-*.jar)` 按枚举序取最后一个匹配，非版本序，
     留旧 jar 可能选错）；
   - `~/.nebflow` 配置**零改动**：新构建裸启动自动落 6140。若要显式钉口，在
     `~/.nebflow\nebflow.json` 顶层加一行 `"port": 6140`（新支持的显式键）。

3. **修正会话 env**（作者面板注入的常驻 env）：`NEBFLOW_GATEWAY_PORT` 由 `6138`
   改为 `6140`（或直接移除——移除后回落品牌默认 6140，效果相同）。

4. **启动与验证**：
   - `nebflow` → 应起在 **6140**（`netstat -ano | findstr :6140` 有 LISTENING）；
   - `stand` 保持 **6138**；两实例并存，互不 focus/互不停杀；
   - 反向验证：显式 `nebflow --port 6138` 撞 stand 时，新守卫会报
     「port 6138 is held by the OTHER instance brand 'stand'」并**拒启**（绝不
     focus 对家）——这正是分家批的三元组判据。

## 4. 验证面

- 引擎 spec（`sbt test` 内）：`GatewayPortDefaultSpec`（品牌缺省口 + 解析链 +
  config `port` 键）、`CliBrandDefaultsSpec`（CLI/引擎双端默认值文本对账）、
  `SingleInstanceGuardSpec`（绝不跨品牌 focus 红线）、`GatewayClientPortSpec`、
  `StaleProcessGuardSpec` / `DaemonSpawnSpec`。
- CLI 纯脚本断言：`bash ops/test/stand-lifecycle.test.sh`（零真实 daemon，case 26
  钉 `stand→~/.stand+6138` / `nebflow→~/.nebflow+6140`）。全绿再重启。

## 5. 不做的事 / 开放项

- **不 push**；改动落仓内工作分支，合并走合并队列。
- 引擎在产运行期间（作者面板会话）不重启 —— 本切换只在作者自己的重启窗执行。
- KAI 的 stand.cmd / nebflow.cmd 安装器包装**不改**（不硬编码端口，端口全由引擎
  侧解析链出）；分家只动引擎 + CLI 默认值 + 删 shim。
- Mac 侧 9.19 自有根实例（8080）不受本批影响；其管辖继续走
  `NEBFLOW_GATEWAY_PORT=8080`。
