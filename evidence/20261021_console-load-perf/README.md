# 控制台加载提速 · 两腿瀑布实测与 A 段止损证据（console-load-perf 批 2026-10-21）

- 批次：20261021_003000 起 · 引擎：sbt run（PID 24919，cwd=/Users/kaiyu/Claude code/Stand，10-20 19:20 起）
- 方法：Chrome 155 headless CDP（`measure.mjs`，零依赖，node≥22 内置 WebSocket）；隧道模型 = `Network.emulateNetworkConditions(latency=264ms, 上下行 2.5MB/s)`（沿用 #538/10-19 审计的 264ms 隧道口径）；DCL/loadEvent 取自 `performance.getEntriesByType('navigation')`，另 +3s settle 收尾空闲请求
- 场景矩阵：direct/tunnel × cold(清 HTTP 缓存+cookie)/warm(同 profile 二航) ；「真冷」= 全新 profile 首航（SW 注册后，单场景单 profile 才是真冷，脚本已按此跑）

## ① 现状核验（任务 A1，真引擎 127.0.0.1:8080 实测）

- **dev 树仍在喂**：`/assets/app.js`→404（W1 dispatch 生效）、`/js/**`→200 no-cache、首开 150 个 js 请求/16 波 import 深度（与 10-19 审计一致）
- **gzip 已全开**（任务 A2 = 无需动）：index / `/js/**` / `/vendor/**` 全部 `Content-Encoding: gzip` + `Vary: Accept-Encoding`（逐行见 results/before/*.json 的 gz 列）；brotli 为 http4s Gzip 中间件外的改造，注记为 dist 时代可选，不在本批
- **W2 头**：代码在位（`StaticRoutes.scala:318` `HashedAssetCacheControl`），但 dev 树永不走 `/assets/`（404）——**W2 活体实证只能在 dist 激活后**，已排入 B 卡核验清单
- **monaco 不再预载**（任务 A3 的一半已在 W3 完成）：真引擎首开 `/vendor/monaco/**` 请求 = 0（仅 `/js/viewers/monaco.js` 1.7KB 包装模块进图）；`preloadMonaco/loadMonaco` 全树零调用点（git 258959ce5 已改按需）
- **阻塞 vendor 脚本**：改前 6 个 classic 阻塞脚本（index.html 597-602，原始 1.15MB / 线上 ~302KB gz）

## ② 瀑布数字（DCL / loadEvent，ms；请求数 / 线上字节）

| 腿 | dev 树改前·真引擎 | dev 树改前·替换面 | dev 树改后·替换面 | dist 出产·W2 仿真 |
|---|---|---|---|---|
| 本地冷 | 247/248 · 192req 2.16MB | 296/314 · 195req 2.19MB | 247/254 · 195req 2.18MB（真冷） | **134/147 · 62req 0.87MB**（真冷） |
| 本地温 | 95/109 · 190req 171×304 | 146/160 · 183req 183×304 | ~193 · **0 线上字节（SW）** | ~62 · **0 字节** |
| 隧道冷 | 8976/8978 · 191req | 9192/9195 · 194req | ~9100（真冷，SW 帮不了首访） | **2597/2598 · 62req 0.87MB** |
| 隧道温 | 8256/8269 · 170×304 | 8797/8809 · 183×304 | **~164 · 0 字节（SW）** | ~68 · **0 字节** |

- 替换面 = 同一台零依赖静态服务器（`serve.mjs`，gzip+ETag+no-cache，dist 轮仿真 W2 immutable）分别喂「改前快照 /tmp/nf-web-before」与「改后 worktree 树」，隔离本批改动单一变量
- SW 生效后（sw=true 腿）页面可见请求 0 线上字节：SWR 缓存即答；SW 自身的后台对账流量在 SW target 上，页面 CDP 会话不计入（已注记）
- 验收对照：本地冷 <2s ✓（现状已达标）；本地温 <0.5s ✓（现状达标，SW 后 0 字节更稳）；**远程冷 <8s：dev 树 9.2s ✗ → dist 2.6s ✓（候 B 卡重启激活）**；**远程温 <2s：dev 树 8.8s ✗ → SW 激活后 ~0.16s ✓（代码本批就位，激活候 B 卡重启）**

## ③ A 段改动的前后对照（同法同器）

| 项 | 改前 | 改后 | 实证 |
|---|---|---|---|
| 首开阻塞 vendor 脚本 | 6 classic 阻塞（1.15MB） | 5 defer（非阻塞，执行序不变）+ qrcode 摘除 | 改后 cold 请求清单无 `/vendor/qrcode.js`；defer 语义 = defer 脚本与 module 同队列按文档序执行（main.js 仍最后） |
| 本地冷 DCL | 296ms | 247ms | 解析不再被 vendor 阻塞 |
| qrcode.js | 每次首开都载（58KB 原始） | 首次渲染二维码才注入（`ensureVendorQr`，失败走原 fallback） | `results/standin-*/direct-cold.json` |
| 温访问（两腿） | 171-183 次 304 校验往返（隧道腿 8.8s） | SW SWR 缓存即答 0 字节（隧道腿 ~0.16s） | `results/standin-after/tunnel-warm.json` sw=true |
| katex/lottie | 在册 defer（不按需） | 同左 | katex 服务聊天历史数学块、lottie 被 main.js waitForGlobals 门控——按需化引入首屏回归面，留 dist 打包时代 |

## ④ SW 陈旧防护链活体测试（`test-sw-update.mjs`，ALL PASS 9/9）

1. SW 安装 + 应用正常 boot（marked 全局在、lucide 图标 40 枚）——降级零影响
2. 温航 SW 受控
3. 深层文件改动：当航缓存即答=旧（设计内一跳陈旧窗），`?nfsw=1` 旁路即时见新，后台 reval 后下一航见新
4. index 改动：哈希指纹变更 ⇒ 清静态缓存 + 客户端补一跳（缓存被清后由网络腿重填新资产）——索引即新鲜裁判，永无结构性陈旧
5. 逃生门三件套：硬刷新原生绕过 / `?nfsw=0` / localStorage `stand_sw='0'` 反注销

## ⑤ 目录

- `measure.mjs` 瀑布测量 · `serve.mjs` 替换静态服务器 · `test-sw-update.mjs` SW 链活体测试
- `results/before|standin-before|standin-after|dist-predict|dist-predict-truecold/*.json` 逐请求瀑布（url/状态/线上字节/缓存头/gzip）
- 「改前快照」树：`/tmp/nf-web-before`（改前 worktree 树的 rsync 副本，供复测）
