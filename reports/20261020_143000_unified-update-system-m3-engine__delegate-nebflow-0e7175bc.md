# 统一更新系统 M3 引擎侧收敛闭环 · 实施批报告

> 批次：统一更新系统 M3（设计已批，作者「按推荐全做」）· 分支 `delegate/delegate-nebflow-0e7175bc` · 五节分节提交
> 依据：`reports/20261020_unified-update-system-design/DESIGN.md`（§4/§5/§7/§8/§9）+ stand-website M1 报告 + link-server M2 提交 `0d18f30`（收侧线格式对齐锚）

## 0. TL;DR

引擎侧 M3 五项全落：①心跳自报 build/channel（BUILD_SHA 构建身份机器可读化）；②VersionCheck 换读官网 manifest（OSS txt 回落，双读过渡期）；③收敛闭环——心跳响应领 `update.target` → 采纳闸（跨通道拒采 + 前向-only，与 link-server M2 服务端口径逐条同构）→ **全复用既有七相位编排器（执行链零改动）**；④互联页本机更新卡（DESIGN #11 推荐落位，不复活设置页更新行）；⑤全链零新增定时器（全部挂既有心跳节拍/事件）。质量闸全绿：新增 spec 35 例 + hotupdate 包 40/40 + neblink 包 805/805 + web 构建零孤儿。manifest 生产未上——引擎读序自然落 OSS txt 回落（只检不装），生产行为零回归。

## 1. 分节交付（每节一提交）

| # | 提交 | 内容 |
|---|---|---|
| ① | `67715d832` | 心跳自报 build/channel + BUILD_SHA 构建身份 |
| ② | `891a4731e` | VersionCheck 换读 manifest + txt 回落 + 日历版序比较器 |
| ③ | `fed2fe457` | 收敛闭环：领账 → 采纳闸 → 编排器 + 校验拒装腿 + install.sh 交接 |
| ④ | `a9a0b7cb9` | 互联页本机统一更新卡（帧订阅，零轮询） |
| ⑤ | `cd9b8deda` | 质量闸 specs 35 例 + 既有撞面重锚 |

## 2. 实施面与依据

### ① 心跳自报（DESIGN §9 M2 引擎半边；link-server `HeartbeatRequest{build,channel}` 已收）

- `Version.build`：build.sbt `resourceGenerators` 构建期写 `BUILD_SHA`（git short sha；git 不可用 ⇒ "dev"），运行期 classpath → `-Dnebflow.buildSha` → "dev" 回退链。组装实测：jar 内 `BUILD_SHA = cd9b8deda`（= 构建提交）。
- `Version.channel`：`~/.nebflow/install-manifest.json` 的 `channel`（install.sh write_manifest 唯一写入点，装机权威）→ 版本串 `-beta` 启发 → stable。派生链抽纯函数 `channelFrom`（可测）；真机实测记录在役（stable）。
- `NeblinkClient.heartbeat` body += `build`/`channel`（serde 单词不变形，与 server M2 收侧字段逐字对齐；旧 server 宽容忽略）。

### ② VersionCheck 换读源（DESIGN §5 兼容序）

- 读序：`https://<domain>/updates/manifest[-beta].json`（brand.conf domain 单源）→ OSS 裸 txt（只检不装）→ `None`（失败静默不变）。域名/指针均带验证注入面（`-Dnebflow.updates.manifestUrl` / `-Dnebflow.updates.pointerUrl`，staging fixture 用）。
- `ReleaseManifest`（schema=1 门 + 宽容解析，未知字段忽略）；jar 完整性面（url/sha256/sizeBytes）供③消费。
- 设置检查帧（WsSystemHandlers）同源切换，帧形逐字段不变。URL 解析全部在 IO 运行期求值（构造期求值读不到注入面——首轮测试抓出后修正）。
- `compareVersions`：与 link-server M2 `cmp_versions` 逐条同构（含 pre 尾字典序——server 如此，引擎同构不"修"）；2026.10 vs 2026.9 数值序正确。

### ③ 收敛闭环（DESIGN §7.2；执行链零改动）

- **线格式**：`NeblinkClient` 解码心跳响应 `update.target{version,build,channel,manifestUrl,publishedAt}`（与 server `UpdateTargetView` 逐字）；`heartbeat` 返回 `HeartbeatOutcome(peers, updateTarget)`；挂账畸形 ⇒ 折 None 绝不拖垮心跳。两处既有测试覆写同步返回型。
- **采纳闸**（`NeblinkUpdateConvergence` 新件，neblink 包）：跨通道拒采（beta 不拖 stable，反向同理）→ 前向-only（等值=已收敛/更旧=服务端回拨 ⇒ 不装）→ 过闸即 `orchestrator.request(source=Heartbeat[新枚举字面], confirm=true[自动档=准入闸通过即装], idempotencyKey=heartbeat:<ch>:<ver>[重复心跳自然合并])`。编排器未装配 ⇒ 可见决策（orchestrator-missing）不静默。同 target 重复心跳只在**状态变更**时广播 `updateState` 一帧（防噪）。
- **执行链**：`UpdateOrchestrator.runChain` 一个字节未改；Checking 相位经 `latestVersion` 默认值自动获得 manifest 读序；source 词表 additive（冻结词表测试随扩展重锚）。
- **校验拒装腿**（RemoteUpdateAction，DESIGN §5「装前校验，不符 ⇒ Aborted，绝不入 staging swap」）：manifest 可读且 jar 完整性面齐 ⇒ 拉 jar（5s 超时流式）→ sizeBytes + sha256 逐字节校验；**任一不符 ⇒ Left 拒装，install.sh 不起跑**（编排器既有 Aborted 相位收口，在役包零接触）；通过 ⇒ `NEBFLOW_STAGED_JAR`/`NEBFLOW_EXPECTED_JAR_SHA256` 环境交接（省 64MB 级二次下载）；manifest 不可读（生产过渡期常态）⇒ legacy 路径逐字节照旧；`minCompatibleEngine` 低于 ⇒ 拒装 +「需先手动跨版」；`installCommand` 验证注入面收拢单处（原 defaultInstall 双读属性消除）。
- **install.sh**（`download_jar` +21 行）：交接件采用前**本侧再验一次 sha256**（双向都不信任未验哈希的字节）；cp 进版本目录后同目录 rename（跨盘不撕裂）；变量缺席/验不过 ⇒ 自然落既有下载源，零行为回归。
- **装配**：`SharedResources.updateConvergence` + GatewayMain 构造（编排器之后）+ Discovery 领账钩子（`onUpdateTarget`，None=旧形态；领账失败自吞双层，绝不断名册同步）。

### ④ 面板更新卡（DESIGN §8 + 开放问题 #11 推荐落位）

- 落位 = 互联页（contacts 面板）设备段顶部，与设备行同屏；**不复活** auth-trim 批退役的设置页更新行（updateCheck.js 退役链零触碰）。
- `updateCard.js` 新模块：`serverConfig.update` 快照（连接初始态，自报 version/build/channel + 挂账/决策）+ `updateState`（引擎变更即推）+ `updateProgress`/`updateResult`（既有编排器帧）四源事件馈送，**零轮询零定时器**；快照未到不出空壳；帧到原位重绘。
- 文案 = 新命名空间 `neblink.updateCard.*`（zh-CN/en 全配，相位键与编排器 wire 一一对应；设置页旧键组不回滚）。CSS 走既有 friends.css（无新文件，孤儿闸天然过）。

### ⑤ 零新增定时器（逐点核对）

领账挂心跳腿（既有 ≤30s 节拍）、状态可见挂事件广播 + 连接快照 + /api/health（查询时现读）、编排器相位挂既有广播、无任何新 `IO.sleep` 循环 / scheduleLoop / wall-clock 预算。唯一时限 = 既有 manifest/jar 拉取 5s 连接读取（传输语义，DESIGN §8 明示允许）。

## 3. 质量闸（全绿实测）

| 闸 | 结果 |
|---|---|
| 新增 spec：换读源回落 | `VersionCheckManifestSpec` 12 例（读序三跳/schema 门/解析容错/cmp_versions 同构）✅ |
| 新增 spec：领账/通道拒采/校验失败拒装 | `RemoteUpdateActionStagedJarSpec` 12 例 + `NeblinkHeartbeatUpdateWireSpec` 5 例（线格式逐字对齐 + 畸形不炸心跳）✅ |
| 新增 spec：身份派生链 | `VersionIdentitySpec` 6 例 ✅ |
| neblink 包回归 | **805/805**（2 skip，既有）✅ |
| hotupdate 包回归 | **40/40**（含编排器既有验收 26 例）✅ |
| web 构建零孤儿 | `build-web.mjs`：171 sources 全入 graph，orphans 0 ✅ |
| 组装 | `stand-assembly-2026.9.20.jar`，jar 内 BUILD_SHA = 构建提交 sha ✅ |

注：`UpdateOrchestratorSpec` 两断言在本批前已红（auth-trim 批删 locale 键未随改该 spec）——本批按 M3 后现实重锚（词表 +heartbeat additive；相位文案断言随新命名空间 `neblink.updateCard.phase.*`），非绕过：断言语义（词表冻结/双表解析/无展示文案内联）逐条保留。

## 4. staging 形态验证（本批替代真实环境腿的部分）

- **编排全链**（spec 内进程内 staging）：领账 → 采纳闸 → 真实编排器 request（`Accepted(heartbeat:stable:…)` 日志在案）→ 冻结即中止（无热重启编排器的沙盒形态）——受理/合并/中止腿真值可见。
- **校验拒装**（file:// fixture）：好 sha ⇒ 交接 env 在场且安装命令收到；坏 sha/坏 size/不可达 ⇒ Left 拒装且**注入的良性安装命令不起跑**；临时件零泄漏。
- **install.sh 交接块**（在库行 python 抽取 + 沙盒 harness）：哈希匹配 ⇒ cp+rename 入版本目录 + 指针记录（ADOPT_OK）；不匹配 ⇒ warn + 落既有下载（REJECT_OK）；`bash -n` 全脚本过。
- **真机读面**：本机 `~/.nebflow/install-manifest.json` channel=stable 在役，`Version.channel` 读路真实命中。

## 5. 生效窗与候补（真实环境腿，候部署后跑）

**生效候重启窗**：本批改动含心跳体加字段、serverConfig/health 加键、web 资产——全部 additive 且新旧互宽容（旧 server 忽略新字段；旧面板忽略新帧），**引擎重启窗部署即可**，无需 link-server 先行（但 link-server M2 先上则领账链立即生效，见 DESIGN #10 兼容矩阵「server 先升、引擎后升」）。

候补腿（manifest 生产未上 / E2E 候部署，本批以 fixture+staging 替代）：
1. manifest 生产部署后：真引擎 `-Dnebflow.updates.manifestUrl` 指生产 → 检查帧/换源读序实测。
2. link-server M2 在役网络：双真机（一新一旧）→ 心跳 `update.target` 领账 → 采纳闸 → 真装 → 新版本心跳回流 → portal `/api/network/{id}/versions` 收敛亮绿（DESIGN §7 离线时序全链）。
3. 真装 + 真重启 round：既有编排器批 4 纪律同样适用（准入闸+排空+八步让渡真链），候维护窗。

## 6. 未做 / 边界

- link-server 与 stand-website 零触碰（M2 已在役收侧；manifest 生产部署候批）。
- beta 通道引擎侧全链对称支持（manifest-beta / pointer-beta 均已接线）但真机 beta 灰度未跑（无 beta 在役设备）。
- 签名验签（DESIGN #9）：采推荐默认，sha256 先行，签名留多用户化。
- 对端设备卡版本显示（peers 列表加 version）：归 link-server/portal 面（M2 已回传），引擎面板设备卡消费未在本批五项内，未动。

## 7. 关键文件清单

`build.sbt` · `src/main/scala/nebflow/Version.scala` · `neblink/NeblinkClient.scala` · `neblink/NeblinkDiscovery.scala` · `neblink/NeblinkUpdateConvergence.scala`（新） · `core/hotupdate/{VersionCheck,UpdateTypes,RemoteUpdateAction,UpdateOrchestrator}.scala` · `core/hotrestart/HealthProbe.scala` · `gateway/{GatewayMain,WebSocketRoutes,HealthRoutes,WsSystemHandlers}.scala` · `agent/SharedResources.scala` · `release/install.sh` · `web/js/updateCard.js`（新） · `web/js/{contacts,locales/zh-CN,locales/en}` · `web/css/friends.css` · specs 五件（tests 侧三新 + 两重锚）
