---
type: fix-report
status: published
date: 2026-10-29
scope: 设置面板 JeV 块 + 生图节「测试可用性」钮（前端两处 + 引擎两探针两路由）/ Flow Map 无涉
---

# JeV 与生图的「测试可用性」按钮批（作者令 2026-10-29 17:53，二派落地）

作者令（逐字）：「然后 Jev/生图，也要像我们LLM这样，有个按钮来测试可用性。」口径基线 = LLM 服务商测试钮（[[index]] 挂链 provider-model-test-button 系列：真实探活、✅+耗时 ms / ❌+HTTP 状态码+错误首行、单飞+测试中禁用+结果保留可重测、未保存表单值可测）。

## 结论（三行）

1. **落点①JeV**：`sidebar.js` JeV 保存行加一枚「测试可用性」（`js/sidebar/jevTestButton.js` 单点：钮+结果块+绑定面，绘制函数直接复用 `modelTestButton.js` 的 `paintTestingLine/paintTestResultLine`——同组件同口径零第二套状态面）；引擎面 `JevProbe.scala` + `POST /api/jev/test`（owner-auth）：laya 走 `/predict→/health` metadata 腿（LayaProvider.probeHealth 同款推导），typesafe 走 1-noul-问题极小真实补全（wire contract 钉形）；keyRef secret 缺席 = 直接「待录入」失败（不发注定 401 的击）。表单当前值（provider/endpoint/keyRef）点击时现读——未保存也测。
2. **落点②生图**：`imagegenSection.js` 保存行加两钮——「测试可用性」（轻探活 = `GET {base}/models` + 适配器鉴权头；401/403 = 死 key 状态码原样可见；404/405 = models 面缺失 → 可达判 + `modelsFaceAbsent` 带回，前端转译「以实跑生成为准」，不谎报鉴权已验证）与「实跑一次最小生成」（真 POST 生成端点，size 256 经适配器吸附枚举、quality=low 预留，回显 ✅+耗时+字节数+格式+缩略图 data URL，产物落 `os.temp.dir` 临时区**绝不进** `<dataRoot>/imagegen` 正式归档）；token 解析单点 `ImageGenProbe.resolveToken`：表单覆写 key > 存储 keyRef > 派生名 `imagegen-<name>-key`（前端 keyRefFor 的引擎侧镜像）。
3. **验证**：新钉 `tests/jev-imagegen-test-buttons.spec.mjs` **14/14 绿**（归位/单飞联动禁用/结果保留/请求形状两腿/401 死 key 状态码可见/缩略图/亮暗双档截图）；scala 侧 `JevProbeSpec` + `ImageGenProbeSpec` 纯函数面+本地 stub 一击全绿；真连冒烟一次成图（cogview-3-flash，HTTP 200 / 10.6s / 63486 bytes jpeg → `/tmp/nebflow-imagegen-test-live/`）；typesafe 线实击得 **HTTP 451 区域封锁**——判决路径真值演示（状态码原样可见正是本钮的本命）。回归：`settings-imagegen-two-forms` / `jev-panel-threefix` / `provider-model-test-button` 三件既有 spec 绿。

## 引擎面（两个探针 + 两个路由，与 ProviderModelProbe 同族纪律）

- `nebflow/gateway/JevProbe.scala`：20s 有界诊断超时（诊断操作显式有界面，业务零超时纪律不适用）；错误文案离手前 sanitize（token → ***）；不取道 TypesafeJevProvider 实例栈——诊断一击不污染 DecisionHealthMonitor 台账。
- `nebflow/llm/imagegen/ImageGenProbe.scala`：轻探活 20s / 实跑 120s（不比业务腿窄）；object 公开位（唯一跨包调用方 = gateway 路由，materialize 复用要求留在 imagegen 包内）。
- `PresenceRoutes.scala`：`POST /jev/test`、`POST /imagegen/test` 两臂 + 各一枚单飞闸（双击/多面板 → 409，前端钮禁用是第一道闸）；`POST /imagegen/test` 挂 `ImageGenSeal.CompiledIn` 门（release 零痕迹口径）。

## 前车之鉴（本批防复发）

前次派发（delegate-nebflow-f23846aa）死于 provider 429 风暴循环退化——本批纪律：真连冒烟**一击即止、零重试**（脚本级与引擎级都不做重试环）；探针单飞闸把「手滑连环点」也封住。

## 证据

- 截图八张：`evidence/20261029_193000_jev-imagegen-test-buttons__delegate-nebflow-48dfd701/`（J1 归位 / J4 401 态 / G1 双钮 / G4 生成成功态 + S1/S2 亮暗双档两对）。
- 真连读数：`[imagegen] HTTP 200 in 10621ms bytes=63486 format=jpeg`；`[jev] HTTP 451（Typesafe is not available in your region）`——451 即该区域本机按钮会显示的 ❌+状态码。
- 已知边界：本实例 secrets 无 `zhipu-api-key`（生图配置的 keyRef 在位性投影为「待录入」）——真连冒烟读的是 `llm.providers.zhipu.apiKey`（imagegen-smoke.py 同源惯例），与面板 keyRef 面无涉。

## 关联

LLM 测试钮口径基线与结果块组件：[[glossary]]（secrets 指针命名与在位性投影）；前批 provider 连接性测试钮见 [[index]] 挂链 settings-panel 系列。入库证据目录与本批 commit 一一对应。
