---
type: design-spec
status: implemented
title: 独立窗口 origin 解析规则钉（本机→网关直连；远端→入口 origin）
date: 2026-10-29
batch: 独立窗口本机加速批（作者 2026-10-29 10:46 报障「独立窗口页面打开有点慢，没走 localhost 加速吗」→ 11:06 升级「完全空白打不开」）
tags: [cardwin, nearpath, loopback, tunnel, console]
---

# 独立窗口 origin 解析规则钉

> 图谱接线：本批是 turn 存活语义规格（turn-liveness-spec，库外存目）同族的隧道腿治理；判据复用本地加速批的
> 环回锚（`/api/health` deviceId 键）与设备快速通道（nearpath 主动探测）；词表见
> [[glossary]]，库入口 [[index]]。

## 0. 一句话规则（钉）

**独立窗口（卡窗）开窗 origin 的唯一裁决：**

| 父页形态 | 弹窗落点 | 凭据 |
| --- | --- | --- |
| 官网入口 origin（隧道腿）∧ 近路面已验证本机网关基址 | `http://<host>:<port>/`（**本机网关直连**，端口服务端下发） | 经 relay 铸一次性 console ticket（兑换后会话 origin=relay-mint ⇒「经官网」跟住） |
| 官网入口 origin ∧ 无验证基址（远端会话/探测 miss） | 入口 origin（相对开窗，**隧道腿现状零变化**） | 同源 cookie 会话（现状） |
| 已在本机网关 origin（环回/LAN 直连腿，含 127.0.0.1↔localhost host 变体） | 同页 origin（相对开窗） | 同源 cookie 会话（现状） |

- 「同一网关」判据 = origin 等值 ∨（双环回 host 且同端口）；**绝不**用
  「hostname 是回环/私网」做题设（127.0.0.1 上可以同时存在入口模拟、别的
  服务 —— 用途判据必须绑「近路面验证过的那个基址」）。
- 端口**永不硬编码**：只来自服务端下发（`/api/neblink/nearpath` 的 `port`
  字段；ws.js 自跳链的 `/api/health/loopback-info` 同源）。

## 1. 根因（为什么弹窗会慢/空白）

开窗链唯一入口 `canvas.js popoutCardWindow` 以**相对 URL** 开窗 ⇒ 弹窗
origin 跟随父页 = 官网入口 origin。本机会话里父页若停在 relay（自跳腿
miss/未跑），弹窗整页（HTML+模块图+WS+文件字节）全走隧道：

- 隧道 = 单上行 FIFO 邮箱（64 槽）+ tunnel guard（per-envelope-IP
  1024 req/60s）。多设备轮询 + SW 对账波 + 大文件传输把上行挤满
  （网关日志 2026-10-29 10:44–11:06：`portal uplink inbox full … depth=64/64`
  连续；`tunnel-per-ip-cap` 429 同窗连拒 238 条）。
- 弹窗 = 冷缓存全量模块图 ⇒ 拥塞管道上慢（10:45「有点慢」）⇒ 模块 fetch
  挂起/失败（11:06「完全空白」：index 静态壳只剩空标题胶囊；canvas.js 的
  引导态面自己也在模块图里 ⇒ 谁都不报错）。

## 2. 实施面（改动层）

| 层 | 文件 | 内容 |
| --- | --- | --- |
| 前端 | `web/js/cardwinDirect.js`（新） | 直连计划（同步读数）+ 基址再验证（nf-reach 品牌锚；环回基址加 `/api/health` deviceId=selfId 双锚）+ 经 relay 铸票 + 导航；任一环失败回落相对 URL；PM origin 记账 |
| 前端 | `web/js/canvas.js` | popoutCardWindow 分腿（直连=预约式同步开窗→异步导航；否则相对 URL 现状）；卡窗通道 postMessage 桥（BroadcastChannel 同源限定 —— 跨源卡窗心跳/合并/update 走 `window.postMessage`，双向 origin 钉定）；合并清账 |
| 前端 | `web/js/nearpath.js` | 探测记录增补 `selfId`（网关自证设备 id，服务端下发）——直连腿第二锚的原料 |
| 前端 | `web/index.html` | 卡窗静态引导看门狗：`card-window-boot` 在场而 15s 后 `body.card-window` 未现且无引导面/真容 ⇒ 注入可见失败卡 + [重试]（`?nfsw=0` 绕 SW）——「空白窗永不静默」 |
| 网关 | `ConsoleTicketRoutes.scala` | 兑换 303 保留 ticket 之外的 query（卡窗深链落地同一卡窗页）；ticket 永不回传；无其余参数 ⇒ `Location: /` 逐字节不变 |

## 3. 安全与归属（「经官网」不丢）

- 票 = relay 铸的一次性 console ticket（120s、single-shot）：兑换会话
  `accountSub` = 父会话账号、`origin = relay-mint` ⇒ 设备标记
  「已认证 · …（经官网）」与父会话同轨；弹窗**绝不**落新匿名会话。
- 直连落点是近路面验证过的本机网关（探测源 IP = 回环/LAN 的机械事实；
  环回基址再加 deviceId 等值锚防同机他实例占口）。
- PM 桥收发双向 origin 钉定（发按开窗记账 origin，收校验 `e.origin`
  等值 + `source` 钉），跨源伪造帧不进卡窗通道分发。
- 隧道闸不豁免的既有裁定不动：远端会话无验证基址 ⇒ 相对开窗走隧道，
  行为零变化（T2 钉）；本批不开任何「隧道腿直连」的后门。

## 4. 验收与证据

- `tests/cardwin-local-direct.spec.mjs`（16 钉全绿）：T1 直连主链（落本机
  origin、深链保真、零隧道子资源 228/228、铸票恰一发、PM 桥双向）；
  T2 回落腿（入口 origin、零铸票）；T3 首开耗时对比（直连 414ms vs 隧道
  2203ms @60ms/req 注入）；T4 本机父页回归；T5 看门狗（模块图挂 ⇒ 15s
  失败卡 + `?nfsw=0` 重试）。
- `ConsoleTicketRoutesSpec`：深链保留 + 无参腿逐字节不变（303/`Location`）。
- 既有回归：cardwin-session-scenarios(11)、canvas-card-window(22)、
  cardwin-content-only(23)、loopback-nearpath、nearpath-dualhost 全绿。
- LIVE 生产证据面：直连弹窗 console 打 `[cardwin] direct-open ok base=…`
  计时行；网关侧 `console ticket redeemed: … origin=relay-mint` 行。

## 5. 已裁决取舍

- 直连预备段（再验证 + 铸票）在**已开的空白窗**内进行（预约式同步开窗保
  popup-blocker 语义），占位「正在打开…」~零点几秒；对比隧道整页加载
  （拥塞时秒级～挂起）为数量级改善。
- 回落腿若发生在直连计划武装后：弹窗落回入口 origin = 与主窗同源 ⇒
  BroadcastChannel 承载通道（PM 腿投递不达 = 天然空转），记账随回落清账。
- SW 不在本批面内：harness 以无 SW 冷缓存口径计首开（sw.js 的 SWR 是
  console-load-perf 批的语义，404 形态见 spec 文件头注）。
