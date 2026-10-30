---
type: design
status: current
created: 2026-10-30
tags: [imagegen, frontend, blocking-semantics, pop]
---

# 生图交互修正批：阻塞语义 + 单图原位直出（2026-10-30）

- 状态：current（2026-10-30 执行并验证）
- 触发：作者令（照 Codex 玩法定案）+ 截图实证「一次生图渲染两次」
- 关联：[[20261030_cmdcode-provider-removal]]（同批顺手核，只读侦察）

## 1. 病灶与根因

旧形态（imagegen 双层批 2026-10-24）下 `generate_image` 是**非阻塞 job**：
`call` 立即返回 `{jobId, status:"running"}` 句柄，生成跑在后台 fiber。这造成
**两条渲染腿**：

1. WS `imagegenUpdate` 帧把占位卡原位替换为大图；
2. `wakeAgent` 以 ExternalEvent 唤醒模型进新回合，模型拿到路径后**再 Pop 一张
   卡**（缩略图卡）。

两腿各渲染一次 ⇒ 双渲染。且回合不等待，生图期间后续内容并发推进。

## 2. 定案形态（本批落刀）

1. **阻塞语义**：`GenerateImageTool.call` 挂起至生成完成（`IO.blocking`
   一击内联 await，无 fiber）。调用期间消息流原位 = 标准 pending 工具卡
   （spinner + 「生图」标签——`tool.generate_image` locale 新增，纯文本风格
   动效纪律），不并发推进后续内容。
2. **成功 = 原位直出单张图**：结果载荷 = Pop 升级批 `___POP_JSON___` 契约
   （票据腿单 image item，与 `PopTool.payloadJson` 同形）。前端既有
   `isPopPayload` 通用腿原样接管——单图平铺、点击 Canvas 打开、扇卡那套保留；
   **不自动调 Pop 工具**，模型也不必再 Pop（`modelFacingResult` 投影明文注记）。
3. **整生命周期图只出现一次**：WS 推送腿 + ExternalEvent 唤醒腿**整腿退役**
   （`pushFrontend` / `wakeAgent` 删除）；前端 `imagegen.js` 占位模块、
   `main.js` 帧 handler、`chat.css` 占位样式块同步删除；`chat.js` 特判面删除。
   `ImageGenJobs` 台账保留（REST `/api/imagegen` 查询腿 + sidebar 投影仍消费）。
4. **失败面**：`Left(ToolError)`（`IMAGEGEN_JOB`）原位翻错误工具卡。

## 3. 验证锚

- 后端：`GenerateImageToolSpec`（7 例）——真 stub HTTP server + 临时 dataRoot：
  阻塞时长 ≥ stub 延迟、Pop 载荷契约、永不返回 job 句柄、零 WS 帧、台账翻转、
  三面闸、模型面投影。
  `ImageGenSpec` / `ImageGenProbeSpec` / `ImageGenSettingsSpec` 33 例回归绿。
- 前端：`tests/imagegen-blocking.spec.mjs`（4 例，全绿）——真实模块 harness：
  T1 占位阶段零图零残留；T2 完成原位翻转 + 恰一张已装载图（取数恰一次）；
  T3 帧/模块/样式源面退役（含灰度安全网：ws.js 未注册类型帧 = 空表 no-op）；
  T4 失败面。截图存证 `.nebflow/evidence/20261030_imagegen-blocking/`。
- checkJs：total 259 < baseline 260，本批触及件零新增（bgMergedPanel/plugins/
  sidebar 三失败为分支既有漂移，干净 HEAD 复跑逐字相同）。

## 4. cmdcode 链上残留核查（顺手核，只读）

结论：**非老配置残留**。`~/.stand/nebflow.json` 唯一 cmdcode 命中 = 摘除文档
记录的 `disabled: true` 保留件；`agents/Nebula/agent.json` 链（preferred
zhipu + fallback kimi）与 `model-presets.json` fallbacks 均无 cmdcode（仓内
grep 命中均为测试夹具/历史报告）。链上仍见 cmdcode 的根因 = 引擎缺口
`llm/registry.scala` `reserveTier`（约 :422-452）与全局链构建**不读
`disabled` 标记**，把配置实存 provider 全部追加进候选链尾——即
[[20261030_cmdcode-provider-removal]] §5 已列开放项；请求路径不含它（不发请求），
属面板候选列表面残留。按本批令「改动另说」，未动引擎。

## 5. 开放项

- `reserveTier` / `getCandidatesUnfiltered` 的 `disabled` 语义支持（引擎层，
  单独立批，见 cmdcode 摘除文档 §5）。
