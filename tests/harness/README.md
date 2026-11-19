# tests/harness/isolated-browser.mjs — 隔离浏览器自动化（命令参考）

作者令（2026-11-07 12:12）：「我们的浏览器自动化能不能优化一下 在隔离环境搞？不影响宿主？」

**这个脚本干什么**：一条命令内自带一个**一次性实例**（独立数据根 + 独立随机端口）与一个
**一次性浏览器档案**（零 cookie / 零 localStorage / 零 IndexedDB / 零 SW / 零 cache），把
`BASE_URL` / token 交给被测 spec；跑完 SIGTERM → 等进程退出 → 等端口释放 → 删临时目录，并
**自证**回收干净。自动化从此不连宿主 `:6138`，宿主控制台零打扰、宿主数据根零写入。

## 1. 快速命令

```bash
# 起隔离实例 + 用它跑一个既有 spec（BASE_URL / NEBFLOW_URL / TOKEN 自动注入）
node tests/harness/isolated-browser.mjs run tests/smoke.spec.mjs

# 连跑 N 次（可重复性取证；每次都是全新 home + 全新端口）
node tests/harness/isolated-browser.mjs run tests/smoke.spec.mjs --n 3 --json

# 隔离不变量自检（宿主零连接 / 零写入 / 回收自证 / 并行两页），18 条 PASS/FAIL
node tests/harness/isolated-browser.mjs doctor            # --full 追加全量文件数指纹（约 +25s×2）

# 自带 harness 的 spec（自起实例，不需要外层注入）→ 直接 node 跑
node tests/isolated-browser-selfproof.spec.mjs

# 交互调试：起一个常驻隔离实例，拿 export 行自己接浏览器/curl
node tests/harness/isolated-browser.mjs up --tag mine      # 打印 BASE_URL / TOKEN / ISO_ROOT
eval "$(node tests/harness/isolated-browser.mjs env --tag mine)"
node tests/harness/isolated-browser.mjs down --tag mine    # 回收（先验身命令行含本实例 home）

# 只读观察宿主：指纹 + 控制台噪音累计
node tests/harness/isolated-browser.mjs fingerprint --full
```

程序化 API（新 spec 推荐直接写这个形态）：

```js
import { withIsolatedBrowser } from './harness/isolated-browser.mjs';

await withIsolatedBrowser(async ({ inst, browser }) => {
  const page = await browser.newPage({ login: true }); // 走真实登录链 ?ticket=
  // …断言…
}); // 出作用域即回收 + 自证
```

可用成员：`startIsolatedInstance()` / `openIsolatedBrowser()` / `withIsolatedBrowser()` /
`hostNoise()` / `attributeNoise()` / `hostFingerprint()` / `hostDiff()` / `readClientIds()` /
`HOST_PORT` / `HOST_ROOT`。

## 2. 三条硬保证（每 run 自证，读数随输出打印）

| 保证 | 机制 | 自证读数 |
|---|---|---|
| 不连宿主 | `baseUrl` 恒为本进程自起实例的随机端口；`context.on('request')` 记账任何落在宿主端口的请求 | `hostHits=0` |
| 不写宿主数据根 | 引擎子进程 `--home <临时根>` + env 同时钉 `STAND_HOME`/`NEBFLOW_HOME`/`NEBFLOW_GATEWAY_PORT`（fork-proof）；浏览器档案 = 临时根下 `profile-*` | 宿主数据根顶层条目新增/消失=0；`--full` 时全量文件数差=0 |
| 跑完即回收 | 自有 pid → SIGTERM → 进程表验证 → 端口释放验证 → 删临时根（路径必须在本基目录下才删） | `进程已退/端口已关/目录已删` 三条 |

宿主噪音**归因口径**：窗口内新增的 `console-takeover` 日志行里，只有引用了**本次 run 的
clientId**（`attributeNoise()` 用端 id 前 8 位比对）才算「我们造成的」。宿主自己的端（作者
手机、面板）在同一窗口的动作单列为 `foreign`，不背锅——实测踩过：不做归因时，作者手机在窗口
内的一次重连会被误记成测试噪音。

## 3. 实测读数（2026-11-07，macOS 本机，宿主控制台 :6138 全程在跑）

| 项 | 读数 | 命令 |
|---|---|---|
| 隔离实例起服（classpath 缓存命中） | **4.3–5.1s** | `run` / `up` 输出的 `启动 NNms` |
| classpath 首次导出（含冷编译本 worktree） | 1m15s（一次性；产物 `target/iso-fullclasspath.txt`） | 冷跑 `up` |
| 隔离 spec 端到端（起实例+浏览器+14 条断言+回收） | **9.0s**，连跑 5 次全绿 | `node tests/isolated-browser-selfproof.spec.mjs` |
| 隔离实例磁盘占用（boot 后、含 agents 种子） | 6.5MB / 432 文件，回收即删 | `du -sh <ISO_ROOT>` |
| 宿主零打扰 | doctor 18/18 PASS：takeover 归因=0、推帧=0、宿主进程 pid 未变、宿主数据根全量文件数 Δ=0 | `doctor --full` |
| 行为等价（同一 spec 同一失败集） | `smoke.spec.mjs`：宿主直连 **8 passed / 4 failed（3.6m）**；隔离实例 **8 passed / 4 failed（55s）**——4 条红是 main 既有红，与隔离无关 | `run tests/smoke.spec.mjs` vs 手工 `BASE_URL=:6138` |

## 4. 代价与边界（诚实申报）

**代价**
- 每次 run 多一次实例启动（实测 4.3–5.1s）+ 一个高位随机端口占用（跑完即释放）。可复用
  classpath 缓存加速；但**不能**跨 run 复用同一个 home（可重复性要求干净起步；`keep` 只用于调试）。
- 需要本 worktree 有编译产物：首次会触发 `sbt -batch "export Runtime/fullClasspath"`（含编译，
  实测 1m15s）。若同 worktree 另有 sbt 会话持有锁，该命令可能排队等待。
- 半途 SIGKILL 会留一个临时根在 `$TMPDIR/stand-iso-browser/`：`down` 腿按 home 验身后回收，
  `/tmp` 清理也会兜底。

**仍必须连宿主（不可隔离）**
1. 依赖**作者真实数据**的断言：历史长度、真实会话/项目/文件、真实插件与密钥配置。
2. 真 **LLM** 行为（`--llm-from-host` 只拷配置，仍会真花钱；要零成本就配 mock provider）。
3. **多端/relay 联调**：手机端、NebLink staging/prod 注册、门户登录——涉及真实网络身份。
4. **进程级状态**：重启/守护/daemon/watchdog/单实例门（`InstanceScope`）——那本来就该对宿主取证。
5. 宿主**数据根自愈类**行为（清理、归档、迁移脚本）——隔离实例上跑不出宿主真实脏状态。

## 5. 逐步迁移清单（不要一次性改 20 个 spec）

现状盘点（本批实测）：`tests/` 共 390 个 `*.spec.mjs`，其中 **270 个**自带 `page.route` 桩
（零后端、零宿主）；真正需要活实例的只有 **13 个**（按 `process.env.BASE_URL|NEBFLOW_URL`
统计）：`smoke` / `askuser` / `askuser-attach` / `askuser-refresh-survive` /
`askinput-off-pending-text` / `askuser-lockline-history` / `asset-url-versioning` /
`canvas-mobile-overlay` / `clientconn-wake` / `plugins-panel-redesign` /
`plugins-panel-two-actions` / `session-rail` / `workspace-picker`。

| 阶段 | 事项 | 判据（做完怎么算完） |
|---|---|---|
| P0 ✅ 本批 | harness + `doctor` + `tests/isolated-browser-selfproof.spec.mjs`；`smoke` / `asset-url-versioning` 走 `run` 腿验证 | doctor 18/18 PASS；selfproof 连跑 5 次绿 |
| P1 | 把 13 个 spec 里**写死的默认端口**改成「env 缺失即报错并指向本 harness」——静默默认值（`localhost:8080`/`8094`/`8285`/`8096`）是「换台机器就红」和「误连宿主」的根 | `grep -rn "localhost:8\|127.0.0.1:8" tests/*.spec.mjs` 命中归零；每条报错文案含 `node tests/harness/isolated-browser.mjs run <spec>` |
| P2 | CI：`frontend-smoke` 的「起 jar + 桥接 token」两步换成 `up`/`down`（或 `run` 腿），端口不再固定 | CI 里 `:6138` 只作「被替代项」出现；`run` 腿在 CI 全绿 |
| P3 | standalone destructive 腿（`session-rail` 会轮换 token、吊销全部会话）默认改走隔离实例 | `session-rail` 在隔离实例上绿；宿主会话不再被顺带吊销 |
| P4 | 两个需要夹具 home 的 spec（`askuser-refresh-survive` 的 mock-LLM、`canvas-mobile-overlay` 的 `NEBFLOW_HOME`）把 `tests/fixtures/askuser-refresh/fixture-home.mjs` 的 provision 接进 harness（`llmFromHost` 已留位） | 两条 spec 在零宿主依赖下自足绿 |
| P5 | 若某个临时探针脚本（`reports-evidence/**`）要连宿主：改成 `up` + `BASE_URL=$(…)`，或临时导 `STAND_HOST_PORT` 显式声明 | 宿主日志里「临时探针开控制台」次数归零 |

## 6. 排障

- `[iso] 引擎启动即退出` → 看输出的引擎日志尾行；常见：端口被抢（重跑即可，端口随机）、
  classpath 过期（删 `target/iso-fullclasspath.txt` 重跑会重新导出）。
- `[iso] 端口 N 被非本实例进程占用（拒绝 kill）` → 我们只收命令行含本实例 home 的进程；
  这条报错说明端口被外部进程占了，重跑即可。
- 需要看完整引擎日志：`up --tag X` 会打印 `引擎日志:` 路径；`down` 前可随意 `curl`。
- 需要保留现场：`up --tag X`（常驻）或代码里 `startIsolatedInstance({ keep: true })`。
